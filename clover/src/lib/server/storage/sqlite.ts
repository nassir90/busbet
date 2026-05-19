import { createClient } from '@libsql/client';
import type { Client } from '@libsql/client';
import type { StorageBackend } from './interface.js';
import type { Stop, Departure, StopTime } from './types.js';

function openDb(path: string): Client {
	return createClient({ url: `file:${path}` });
}

function minsToGtfsTime(totalMins: number): string {
	const h = Math.floor(totalMins / 60);
	const m = totalMins % 60;
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

export async function createSqliteBackend(dbPath: string): Promise<StorageBackend> {
	const db = openDb(dbPath);

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

		async getScheduledDepartures(stopId, activeServiceIds, windowMinutes = 90, hourOffset = 0) {
			if (activeServiceIds.length === 0) return [];

			const now = new Date();
			const currentMins = now.getHours() * 60 + now.getMinutes() + hourOffset * 60;
			const endMins = currentMins + windowMinutes;

			const placeholders = activeServiceIds.map(() => '?').join(',');
			const r = await db.execute({
				sql: `SELECT
				        st.trip_id,
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
					route_short_name: row.route_short_name as string,
					trip_headsign: (row.trip_headsign as string) ?? 'Unknown',
					scheduled_departure: scheduledDisplay,
					estimated_departure: null,
					delay_seconds: null,
					realtime: false
				};
			});
		},

		async getTripStopTimes(tripId: string): Promise<StopTime[]> {
			const r = await db.execute({
				sql: `SELECT st.trip_id, st.stop_id, st.stop_sequence, st.arrival_time, st.departure_time
				      FROM stop_times st
				      WHERE st.trip_id = ?
				      ORDER BY st.stop_sequence`,
				args: [tripId]
			});
			return r.rows as unknown as StopTime[];
		},

		async getRouteTrips(routeShortName: string, directionId?: number): Promise<string[]> {
			const dirClause = directionId !== undefined ? 'AND t.direction_id = ?' : '';
			const args: (string | number)[] = [routeShortName];
			if (directionId !== undefined) args.push(directionId);
			const r = await db.execute({
				sql: `SELECT t.trip_id FROM trips t
				      JOIN routes r ON r.route_id = t.route_id
				      WHERE r.route_short_name = ? ${dirClause}`,
				args
			});
			return r.rows.map((row) => row.trip_id as string);
		}
	};
}
