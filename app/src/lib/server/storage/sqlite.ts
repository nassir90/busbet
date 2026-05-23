import { createClient } from '@libsql/client';
import type { Client, InValue } from '@libsql/client';
import { randomUUID } from 'node:crypto';
import type { StorageBackend } from './interface.js';
import type { Stop, Departure, User, Wager, TrackedTrip, PlaceWagerInput, WagerResult } from './types.js';

function openDb(path: string): Client {
	return createClient({ url: `file:${path}` });
}

async function initSchema(db: Client): Promise<void> {
	await db.executeMultiple(`
		CREATE TABLE IF NOT EXISTS stops (
			stop_id   TEXT PRIMARY KEY,
			stop_code TEXT,
			stop_name TEXT,
			stop_lat  REAL,
			stop_lon  REAL
		);
		CREATE INDEX IF NOT EXISTS idx_stops_code ON stops(stop_code);
		CREATE INDEX IF NOT EXISTS idx_stops_name ON stops(stop_name);

		CREATE TABLE IF NOT EXISTS routes (
			route_id         TEXT PRIMARY KEY,
			route_short_name TEXT,
			route_long_name  TEXT,
			agency_id        TEXT
		);

		CREATE TABLE IF NOT EXISTS trips (
			trip_id       TEXT PRIMARY KEY,
			route_id      TEXT,
			service_id    TEXT,
			trip_headsign TEXT,
			direction_id  INTEGER
		);
		CREATE INDEX IF NOT EXISTS idx_trips_route   ON trips(route_id);
		CREATE INDEX IF NOT EXISTS idx_trips_service ON trips(service_id);

		CREATE TABLE IF NOT EXISTS stop_times (
			trip_id        TEXT,
			stop_id        TEXT,
			stop_sequence  INTEGER,
			arrival_time   TEXT,
			departure_time TEXT,
			PRIMARY KEY (trip_id, stop_sequence)
		);
		CREATE INDEX IF NOT EXISTS idx_st_stop ON stop_times(stop_id);
		CREATE INDEX IF NOT EXISTS idx_st_trip ON stop_times(trip_id);

		CREATE TABLE IF NOT EXISTS calendar (
			service_id TEXT PRIMARY KEY,
			monday     INTEGER,
			tuesday    INTEGER,
			wednesday  INTEGER,
			thursday   INTEGER,
			friday     INTEGER,
			saturday   INTEGER,
			sunday     INTEGER,
			start_date TEXT,
			end_date   TEXT
		);

		CREATE TABLE IF NOT EXISTS calendar_dates (
			service_id     TEXT,
			date           TEXT,
			exception_type INTEGER,
			PRIMARY KEY (service_id, date)
		);

		CREATE TABLE IF NOT EXISTS _meta (
			key   TEXT PRIMARY KEY,
			value TEXT
		);

		CREATE TABLE IF NOT EXISTS users (
			user_id    TEXT PRIMARY KEY,
			balance    REAL NOT NULL DEFAULT 100.0,
			created_at TEXT NOT NULL
		);

		CREATE TABLE IF NOT EXISTS wagers (
			wager_id            TEXT PRIMARY KEY,
			user_id             TEXT NOT NULL,
			trip_id             TEXT NOT NULL,
			stop_id             TEXT NOT NULL,
			scheduled_departure TEXT NOT NULL,
			scheduled_date      TEXT NOT NULL,
			wager_type          TEXT NOT NULL,
			drift_minutes       INTEGER,
			stake               REAL NOT NULL,
			status              TEXT NOT NULL DEFAULT 'open',
			placed_at           TEXT NOT NULL,
			resolved_at         TEXT,
			payout              REAL
		);
		CREATE INDEX IF NOT EXISTS idx_wagers_user ON wagers(user_id);
		CREATE INDEX IF NOT EXISTS idx_wagers_trip ON wagers(trip_id, stop_id, status);

		CREATE TABLE IF NOT EXISTS tracked_trips (
			trip_id                  TEXT NOT NULL,
			stop_id                  TEXT NOT NULL,
			scheduled_departure      TEXT NOT NULL,
			scheduled_date           TEXT NOT NULL,
			last_estimated_departure TEXT,
			last_seen_at             TEXT,
			status                   TEXT NOT NULL DEFAULT 'tracking',
			PRIMARY KEY (trip_id, stop_id)
		);
	`);
}

function minsToGtfsTime(totalMins: number): string {
	const h = Math.floor(totalMins / 60);
	const m = totalMins % 60;
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

export async function createSqliteBackend(dbPath: string): Promise<StorageBackend> {
	const db = openDb(dbPath);
	await initSchema(db);

	return {
		async isSeeded() {
			const r = await db.execute("SELECT value FROM _meta WHERE key = 'seeded'");
			return (r.rows[0]?.value as string | undefined) === '1';
		},

		async searchStops(query: string) {
			const like = `%${query.toUpperCase()}%`;
			const r = await db.execute({
				sql: `SELECT * FROM stops
				      WHERE UPPER(stop_name) LIKE ? OR stop_code LIKE ?
				      LIMIT 20`,
				args: [like, like]
			});
			return r.rows as unknown as Stop[];
		},

		async getStopByCode(code: string) {
			const r = await db.execute({
				sql: 'SELECT * FROM stops WHERE stop_code = ?',
				args: [code]
			});
			return r.rows.length ? (r.rows[0] as unknown as Stop) : null;
		},

		async getActiveServiceIds(dayName: string, date: string) {
			const added = (
				await db.execute({
					sql: `SELECT service_id FROM calendar_dates
					      WHERE date = ? AND exception_type = 1`,
					args: [date]
				})
			).rows.map((r) => r.service_id as string);

			const removedRows = (
				await db.execute({
					sql: `SELECT service_id FROM calendar_dates
					      WHERE date = ? AND exception_type = 2`,
					args: [date]
				})
			).rows.map((r) => r.service_id as string);
			const removed = new Set(removedRows);

			const regular = (
				await db.execute({
					sql: `SELECT service_id FROM calendar
					      WHERE ${dayName} = 1 AND start_date <= ? AND end_date >= ?`,
					args: [date, date]
				})
			).rows
				.map((r) => r.service_id as string)
				.filter((id) => !removed.has(id));

			return [...new Set([...regular, ...added])];
		},

		async getOrCreateUser(userId: string): Promise<User> {
			const r = await db.execute({ sql: 'SELECT * FROM users WHERE user_id = ?', args: [userId] });
			if (r.rows.length) return r.rows[0] as unknown as User;
			const now = new Date().toISOString();
			await db.execute({
				sql: 'INSERT INTO users (user_id, balance, created_at) VALUES (?, 100.0, ?)',
				args: [userId, now]
			});
			return { user_id: userId, balance: 100.0, created_at: now };
		},

		async placeWager(input: PlaceWagerInput): Promise<Wager> {
			const user = await (this as StorageBackend).getOrCreateUser(input.userId);
			if (user.balance < input.stake) throw new Error('Insufficient balance');
			const wagerId = randomUUID();
			const now = new Date().toISOString();
			await db.batch([
				{
					sql: 'UPDATE users SET balance = balance - ? WHERE user_id = ?',
					args: [input.stake, input.userId]
				},
				{
					sql: `INSERT INTO wagers
					      (wager_id, user_id, trip_id, stop_id, scheduled_departure, scheduled_date,
					       wager_type, drift_minutes, stake, status, placed_at, resolved_at, payout)
					      VALUES (?,?,?,?,?,?,?,?,?,'open',?,NULL,NULL)`,
					args: [wagerId, input.userId, input.tripId, input.stopId,
					       input.scheduledDeparture, input.scheduledDate,
					       input.wagerType, input.driftMinutes, input.stake, now]
				},
				{
					sql: `INSERT OR IGNORE INTO tracked_trips
					      (trip_id, stop_id, scheduled_departure, scheduled_date,
					       last_estimated_departure, last_seen_at, status)
					      VALUES (?,?,?,?,NULL,NULL,'tracking')`,
					args: [input.tripId, input.stopId, input.scheduledDeparture, input.scheduledDate]
				}
			], 'write');
			return {
				wager_id: wagerId, user_id: input.userId, trip_id: input.tripId,
				stop_id: input.stopId, scheduled_departure: input.scheduledDeparture,
				scheduled_date: input.scheduledDate, wager_type: input.wagerType,
				drift_minutes: input.driftMinutes, stake: input.stake,
				status: 'open', placed_at: now, resolved_at: null, payout: null
			};
		},

		async getOpenWagers(tripId: string, stopId: string): Promise<Wager[]> {
			const r = await db.execute({
				sql: `SELECT * FROM wagers WHERE trip_id = ? AND stop_id = ? AND status = 'open'`,
				args: [tripId, stopId]
			});
			return r.rows as unknown as Wager[];
		},

		async settleWagers(tripId: string, stopId: string, results: WagerResult[], outcome: 'arrived' | 'canceled'): Promise<void> {
			const now = new Date().toISOString();
			const statements: Array<{ sql: string; args: InValue[] }> = [];
			for (const { wagerId, status, payout } of results) {
				statements.push({
					sql: 'UPDATE wagers SET status = ?, payout = ?, resolved_at = ? WHERE wager_id = ?',
					args: [status, payout, now, wagerId]
				});
				if (payout > 0) {
					statements.push({
						sql: 'UPDATE users SET balance = balance + ? WHERE user_id = (SELECT user_id FROM wagers WHERE wager_id = ?)',
						args: [payout, wagerId]
					});
				}
			}
			statements.push({
				sql: `UPDATE tracked_trips SET status = ? WHERE trip_id = ? AND stop_id = ?`,
				args: [outcome === 'canceled' ? 'canceled' : 'arrived', tripId, stopId]
			});
			if (statements.length) await db.batch(statements, 'write');
		},

		async getUserWagers(userId: string): Promise<Wager[]> {
			const r = await db.execute({
				sql: 'SELECT * FROM wagers WHERE user_id = ? ORDER BY placed_at DESC LIMIT 30',
				args: [userId]
			});
			return r.rows as unknown as Wager[];
		},

		async getTrackedTrips(): Promise<TrackedTrip[]> {
			const r = await db.execute(`SELECT * FROM tracked_trips WHERE status = 'tracking'`);
			return r.rows as unknown as TrackedTrip[];
		},

		async updateTrackedTrip(tripId: string, stopId: string, patch: {
			lastEstimatedDeparture?: string | null;
			lastSeenAt?: string | null;
			status?: 'tracking' | 'arrived' | 'canceled';
		}): Promise<void> {
			const sets: string[] = [];
			const args: InValue[] = [];
			if (patch.lastEstimatedDeparture !== undefined) {
				sets.push('last_estimated_departure = ?');
				args.push(patch.lastEstimatedDeparture);
			}
			if (patch.lastSeenAt !== undefined) {
				sets.push('last_seen_at = ?');
				args.push(patch.lastSeenAt);
			}
			if (patch.status !== undefined) {
				sets.push('status = ?');
				args.push(patch.status);
			}
			if (!sets.length) return;
			args.push(tripId, stopId);
			await db.execute({
				sql: `UPDATE tracked_trips SET ${sets.join(', ')} WHERE trip_id = ? AND stop_id = ?`,
				args
			});
		},

		async getScheduledDepartures(stopId, activeServiceIds, windowMinutes = 105, hourOffset = 0) {
			if (activeServiceIds.length === 0) return [];

			const now = new Date();
			// hourOffset=24 shifts the query window into GTFS extended-time territory
			// (e.g. 24:29 = 00:29 on a service that started the previous calendar day)
			const currentMins = now.getHours() * 60 + now.getMinutes() + hourOffset * 60;
			const endMins = currentMins + windowMinutes;

			const placeholders = activeServiceIds.map(() => '?').join(',');
			const r = await db.execute({
				sql: `SELECT
				        st.trip_id,
				        st.stop_id,
				        st.stop_sequence,
				        st.departure_time,
				        r.route_short_name,
				        t.trip_headsign
				      FROM stop_times st
				      JOIN trips t  ON t.trip_id  = st.trip_id
				      JOIN routes r ON r.route_id = t.route_id
				      WHERE st.stop_id = ?
				        AND t.service_id IN (${placeholders})
				        AND st.departure_time >= ?
				        AND st.departure_time <= ?
				      ORDER BY st.departure_time
				      LIMIT 30`,
				args: [
					stopId,
					...activeServiceIds,
					minsToGtfsTime(currentMins),
					minsToGtfsTime(endMins)
				]
			});

			return r.rows.map((row): Departure => {
				const rawTime = row.departure_time as string;
				const rawHour = parseInt(rawTime.substring(0, 2));
				const displayHour = rawHour >= 24 ? rawHour - 24 : rawHour;
				const scheduledDisplay = `${String(displayHour).padStart(2, '0')}:${rawTime.substring(3, 5)}`;
				return {
					trip_id: row.trip_id as string,
					stop_id: row.stop_id as string,
					stop_sequence: row.stop_sequence as number,
					route_short_name: row.route_short_name as string,
					trip_headsign: (row.trip_headsign as string) ?? 'Unknown',
					scheduled_departure: scheduledDisplay,
					estimated_departure: null,
					delay_seconds: null,
					realtime: false
				};
			});
		}
	};
}
