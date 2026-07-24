import { createClient } from '@libsql/client';
import type { Client } from '@libsql/client';
import type { GtfsStorage, Stop, Departure, StopTime, RouteDirection, RouteStop, ShapePoint } from './types.js';

function openDb(path: string): Client {
	return createClient({ url: `file:${path}` });
}

function minsToGtfsTime(totalMins: number): string {
	const h = Math.floor(totalMins / 60);
	const m = totalMins % 60;
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

export function createSqliteBackend(dbPath: string): GtfsStorage {
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

		async getScheduledDepartures(stopId, activeServiceIds, windowMinutes = 105, hourOffset = 0, atDate?: Date) {
			if (activeServiceIds.length === 0) return [];

			const now = atDate ?? new Date();
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
				        t.direction_id,
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
					direction_id: row.direction_id as number,
					trip_headsign: (row.trip_headsign as string) ?? 'Unknown',
					scheduled_departure: scheduledDisplay,
					estimated_departure: null,
					delay_seconds: null,
					realtime: false
				};
			});
		},

		async getTripDetail(tripId: string) {
			const r = await db.execute({
				sql: `SELECT
				        st.stop_sequence,
				        st.arrival_time,
				        st.departure_time,
				        s.stop_id,
				        s.stop_code,
				        s.stop_name,
				        s.stop_lat,
				        s.stop_lon,
				        t.trip_headsign,
				        t.direction_id,
				        r.route_short_name
				      FROM stop_times st
				      JOIN stops s ON s.stop_id = st.stop_id
				      JOIN trips t ON t.trip_id = st.trip_id
				      JOIN routes r ON r.route_id = t.route_id
				      WHERE st.trip_id = ?
				      ORDER BY st.stop_sequence`,
				args: [tripId]
			});
			if (r.rows.length === 0) return null;

			const formatTime = (raw: string) => {
				const rawHour = parseInt(raw.substring(0, 2));
				const displayHour = rawHour >= 24 ? rawHour - 24 : rawHour;
				return `${String(displayHour).padStart(2, '0')}:${raw.substring(3, 5)}`;
			};

			const first = r.rows[0];
			return {
				trip_id: tripId,
				route_short_name: first.route_short_name as string,
				trip_headsign: (first.trip_headsign as string) ?? 'Unknown',
				direction_id: first.direction_id as number,
				stops: r.rows.map((row) => ({
					stop_sequence: row.stop_sequence as number,
					stop_id: row.stop_id as string,
					stop_code: row.stop_code as string,
					stop_name: row.stop_name as string,
					stop_lat: (row.stop_lat as number | null) ?? null,
					stop_lon: (row.stop_lon as number | null) ?? null,
					scheduled_arrival: formatTime(row.arrival_time as string),
					scheduled_departure: formatTime(row.departure_time as string),
					estimated_arrival: null,
					estimated_departure: null,
					delay_seconds: null,
					realtime: false,
				}))
			};
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

		async searchRoutes(query: string): Promise<RouteDirection[]> {
			const like = `%${query.toUpperCase()}%`;

			// Step 1: scan routes (412 rows) — fast regardless of LIKE pattern
			const routeRows = await db.execute({
				sql: `SELECT route_id, route_short_name FROM routes
				      WHERE UPPER(route_short_name) LIKE ? LIMIT 10`,
				args: [like]
			});
			if (!routeRows.rows.length) return [];

			// Steps 2+3: per route, fetch directions (idx_trips_route) then first/last
			// stops (primary key). All routes processed in parallel.
			const perRoute = await Promise.all(
				(routeRows.rows as unknown as { route_id: string; route_short_name: string }[]).map(async (route) => {
					const dirRows = await db.execute({
						sql: `SELECT direction_id, MIN(trip_id) AS rep_trip
						      FROM trips WHERE route_id = ? GROUP BY direction_id`,
						args: [route.route_id]
					});
					return Promise.all(
						(dirRows.rows as unknown as { direction_id: number; rep_trip: string }[]).map(async (dir) => {
							const [first, last] = await Promise.all([
								db.execute({
									sql: `SELECT s.stop_name FROM stop_times st
									      JOIN stops s ON s.stop_id = st.stop_id
									      WHERE st.trip_id = ? ORDER BY st.stop_sequence LIMIT 1`,
									args: [dir.rep_trip]
								}),
								db.execute({
									sql: `SELECT s.stop_name FROM stop_times st
									      JOIN stops s ON s.stop_id = st.stop_id
									      WHERE st.trip_id = ? ORDER BY st.stop_sequence DESC LIMIT 1`,
									args: [dir.rep_trip]
								})
							]);
							return {
								route_short_name: route.route_short_name,
								direction_id: dir.direction_id,
								from_stop: (first.rows[0]?.stop_name as string) ?? '',
								to_stop: (last.rows[0]?.stop_name as string) ?? ''
							} as RouteDirection;
						})
					);
				})
			);

			return perRoute.flat().sort((a, b) =>
				a.route_short_name.localeCompare(b.route_short_name) || a.direction_id - b.direction_id
			);
		},

		async getRouteStops(routeShortName: string, directionId: number): Promise<RouteStop[]> {
			const r = await db.execute({
				sql: `SELECT st.stop_sequence, s.stop_code, s.stop_name
				      FROM stop_times st
				      JOIN stops s ON st.stop_id = s.stop_id
				      WHERE st.trip_id = (
				        SELECT MIN(t.trip_id) FROM trips t
				        JOIN routes r ON r.route_id = t.route_id
				        WHERE r.route_short_name = ? AND t.direction_id = ?
				      )
				      ORDER BY st.stop_sequence`,
				args: [routeShortName, directionId]
			});
			return r.rows as unknown as RouteStop[];
		},

		async getRoutesForStop(stopCode: string): Promise<string[]> {
			const r = await db.execute({
				sql: `SELECT DISTINCT r.route_short_name
				      FROM routes r
				      JOIN trips t ON t.route_id = r.route_id
				      JOIN stop_times st ON st.trip_id = t.trip_id
				      JOIN stops s ON s.stop_id = st.stop_id
				      WHERE s.stop_code = ?
				      LIMIT 50`,
				args: [stopCode]
			});
			return r.rows.map((row) => row.route_short_name as string);
		},

		/**
		 * Scheduled first departure and last arrival per trip. The realtime feed can't tell us
		 * whether a bus is running — NTA reports currentStatus IN_TRANSIT_TO and
		 * currentStopSequence 0 for every vehicle — so the schedule is the usable signal.
		 */
		async getTripSpans(tripIds: string[]) {
			const out = new Map<string, { first_departure: string; last_arrival: string }>();
			if (!tripIds.length) return out;
			const placeholders = tripIds.map(() => '?').join(',');
			const r = await db.execute({
				sql: `SELECT trip_id, MIN(departure_time) AS first_departure, MAX(arrival_time) AS last_arrival
				      FROM stop_times WHERE trip_id IN (${placeholders}) GROUP BY trip_id`,
				args: tripIds
			});
			for (const row of r.rows) {
				out.set(row.trip_id as string, {
					first_departure: row.first_departure as string,
					last_arrival: row.last_arrival as string,
				});
			}
			return out;
		},

		async getShape(shapeId: string): Promise<ShapePoint[]> {
			const r = await db.execute({
				sql: 'SELECT lat, lon FROM shapes WHERE shape_id = ? ORDER BY seq',
				args: [shapeId]
			});
			return r.rows as unknown as ShapePoint[];
		},

		/**
		 * Geometry for the road a trip follows. Trip-scoped rather than route-scoped: a route has
		 * several shapes across its trips, so a route-level lookup would have to pick one
		 * arbitrarily or merge them.
		 */
		async getShapeForTrip(tripId: string): Promise<ShapePoint[]> {
			const r = await db.execute({
				sql: `SELECT s.lat, s.lon
				      FROM shapes s
				      JOIN trips t ON t.shape_id = s.shape_id
				      WHERE t.trip_id = ?
				      ORDER BY s.seq`,
				args: [tripId]
			});
			return r.rows as unknown as ShapePoint[];
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
