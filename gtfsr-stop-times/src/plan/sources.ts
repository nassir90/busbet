/**
 * Where the planner's timetable comes from. Production reads the SQLite database load-gtfs.ts
 * already maintains; the CSV source reads an extracted GTFS directory, which is what the
 * benchmarks and the on-device export use, so they need no database.
 */
import { createReadStream, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';

export interface StopRow { stop_id: string; stop_code: string; stop_name: string; stop_lat: number; stop_lon: number }
export interface RouteRow { route_id: string; route_short_name: string; route_long_name: string; route_type: number; agency_id: string }
export interface TripRow { trip_id: string; route_id: string; service_id: string; trip_headsign: string; shape_id: string; direction_id: number }
export interface StopTimeRow { trip_id: string; stop_id: string; stop_sequence: number; arrival_time: string; departure_time: string }
export interface ShapePoint { lat: number; lon: number }

export interface TimetableSource {
	readonly label: string;
	stops(): Promise<StopRow[]>;
	routes(): Promise<RouteRow[]>;
	/** Service ids running on a `YYYYMMDD` date: calendar.txt by weekday, then calendar_dates. */
	activeServiceIds(date: string): Promise<Set<string>>;
	trips(serviceIds: Set<string>): Promise<TripRow[]>;
	/** Calls [onRow] for every stop_time of the given trips, in no particular order. */
	stopTimes(tripIds: Set<string>, onRow: (row: StopTimeRow) => void): Promise<void>;
	shape(shapeId: string): Promise<ShapePoint[]>;
}

const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

/** 0 = Sunday for a `YYYYMMDD` date. Dates have no zone, so UTC arithmetic is exact. */
export function weekdayOf(date: string): number {
	return new Date(Date.UTC(+date.slice(0, 4), +date.slice(4, 6) - 1, +date.slice(6, 8))).getUTCDay();
}

export function addDays(date: string, days: number): string {
	const d = new Date(Date.UTC(+date.slice(0, 4), +date.slice(4, 6) - 1, +date.slice(6, 8)));
	d.setUTCDate(d.getUTCDate() + days);
	return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}`;
}

// ---------------------------------------------------------------------------------------------
// CSV (an extracted GTFS zip)
// ---------------------------------------------------------------------------------------------

/** Splits one CSV line, honouring double quotes. GTFS from the NTA quotes only the odd headsign. */
export function splitCsvLine(line: string): string[] {
	if (!line.includes('"')) return line.split(',');
	const out: string[] = [];
	let cur = '';
	let quoted = false;
	for (let i = 0; i < line.length; i++) {
		const c = line[i];
		if (quoted) {
			if (c === '"') {
				if (line[i + 1] === '"') { cur += '"'; i++; }
				else quoted = false;
			} else cur += c;
		} else if (c === '"') quoted = true;
		else if (c === ',') { out.push(cur); cur = ''; }
		else cur += c;
	}
	out.push(cur);
	return out;
}

function readCsv(path: string): Record<string, string>[] {
	const text = readFileSync(path, 'utf8').replace(/^﻿/, '');
	const lines = text.split(/\r?\n/).filter((l) => l.length > 0);
	const header = splitCsvLine(lines[0]);
	return lines.slice(1).map((l) => {
		const cells = splitCsvLine(l);
		const row: Record<string, string> = {};
		header.forEach((h, i) => (row[h] = cells[i] ?? ''));
		return row;
	});
}

export function csvSource(dir: string): TimetableSource {
	let calendar: Record<string, string>[] | null = null;
	let calendarDates: Record<string, string>[] | null = null;
	let allTrips: TripRow[] | null = null;

	return {
		label: `csv:${dir}`,
		async stops() {
			return readCsv(join(dir, 'stops.txt')).map((r) => ({
				stop_id: r.stop_id,
				stop_code: r.stop_code,
				stop_name: r.stop_name,
				stop_lat: parseFloat(r.stop_lat),
				stop_lon: parseFloat(r.stop_lon),
			}));
		},
		async routes() {
			return readCsv(join(dir, 'routes.txt')).map((r) => ({
				route_id: r.route_id,
				route_short_name: r.route_short_name,
				route_long_name: r.route_long_name,
				route_type: parseInt(r.route_type, 10),
				agency_id: r.agency_id,
			}));
		},
		async activeServiceIds(date: string) {
			calendar ??= readCsv(join(dir, 'calendar.txt'));
			calendarDates ??= readCsv(join(dir, 'calendar_dates.txt'));
			const day = WEEKDAYS[weekdayOf(date)];
			const ids = new Set<string>();
			for (const c of calendar) {
				if (c[day] === '1' && c.start_date <= date && c.end_date >= date) ids.add(c.service_id);
			}
			for (const cd of calendarDates) {
				if (cd.date !== date) continue;
				if (cd.exception_type === '1') ids.add(cd.service_id);
				else ids.delete(cd.service_id);
			}
			return ids;
		},
		async trips(serviceIds: Set<string>) {
			allTrips ??= readCsv(join(dir, 'trips.txt')).map((r) => ({
				trip_id: r.trip_id,
				route_id: r.route_id,
				service_id: r.service_id,
				trip_headsign: r.trip_headsign,
				shape_id: r.shape_id,
				direction_id: parseInt(r.direction_id || '0', 10),
			}));
			return allTrips.filter((t) => serviceIds.has(t.service_id));
		},
		async stopTimes(tripIds: Set<string>, onRow: (row: StopTimeRow) => void) {
			const rl = createInterface({ input: createReadStream(join(dir, 'stop_times.txt')), crlfDelay: Infinity });
			let header: string[] | null = null;
			let iTrip = 0, iStop = 0, iSeq = 0, iArr = 0, iDep = 0;
			for await (const line of rl) {
				if (!header) {
					header = splitCsvLine(line.replace(/^﻿/, ''));
					iTrip = header.indexOf('trip_id');
					iStop = header.indexOf('stop_id');
					iSeq = header.indexOf('stop_sequence');
					iArr = header.indexOf('arrival_time');
					iDep = header.indexOf('departure_time');
					continue;
				}
				// Cheap prefilter: the trip id is the first column in every NTA file.
				const comma = line.indexOf(',');
				if (iTrip === 0 && !tripIds.has(line.slice(0, comma))) continue;
				const c = splitCsvLine(line);
				if (!tripIds.has(c[iTrip])) continue;
				onRow({
					trip_id: c[iTrip],
					stop_id: c[iStop],
					stop_sequence: parseInt(c[iSeq], 10),
					arrival_time: c[iArr],
					departure_time: c[iDep],
				});
			}
		},
		async shape(shapeId: string) {
			// shapes.txt is ~290 MB; scanning it per request is far too slow outside a benchmark.
			// The CSV source is for experiments, so legs fall back to stop-to-stop lines.
			void shapeId;
			return [];
		},
	};
}

// ---------------------------------------------------------------------------------------------
// SQLite (the database load-gtfs.ts maintains)
// ---------------------------------------------------------------------------------------------

/**
 * Uses node:sqlite rather than @libsql/client: the timetable build reads ~1M stop_times rows,
 * and libsql's execute() materialises a whole result set as objects before returning, where
 * node:sqlite's iterate() streams them.
 */
export async function sqliteSource(dbPath: string): Promise<TimetableSource> {
	const { DatabaseSync } = await import('node:sqlite');
	const db = new DatabaseSync(dbPath, { readOnly: true });

	return {
		label: `sqlite:${dbPath}`,
		async stops() {
			return db.prepare('SELECT stop_id, stop_code, stop_name, stop_lat, stop_lon FROM stops').all() as unknown as StopRow[];
		},
		async routes() {
			return db
				.prepare('SELECT route_id, route_short_name, route_long_name, route_type, agency_id FROM routes')
				.all() as unknown as RouteRow[];
		},
		async activeServiceIds(date: string) {
			const day = WEEKDAYS[weekdayOf(date)];
			const ids = new Set<string>();
			for (const r of db
				.prepare(`SELECT service_id FROM calendar WHERE ${day} = 1 AND start_date <= ? AND end_date >= ?`)
				.all(date, date) as { service_id: string }[]) ids.add(r.service_id);
			for (const r of db
				.prepare('SELECT service_id, exception_type FROM calendar_dates WHERE date = ?')
				.all(date) as { service_id: string; exception_type: number }[]) {
				if (r.exception_type === 1) ids.add(r.service_id);
				else ids.delete(r.service_id);
			}
			return ids;
		},
		async trips(serviceIds: Set<string>) {
			if (serviceIds.size === 0) return [];
			const ids = [...serviceIds];
			const rows = db
				.prepare(
					`SELECT trip_id, route_id, service_id, COALESCE(trip_headsign,'') AS trip_headsign,
					        COALESCE(shape_id,'') AS shape_id, COALESCE(direction_id,0) AS direction_id
					 FROM trips WHERE service_id IN (${ids.map(() => '?').join(',')})`,
				)
				.all(...ids);
			return rows as unknown as TripRow[];
		},
		async stopTimes(tripIds: Set<string>, onRow: (row: StopTimeRow) => void) {
			const stmt = db.prepare(
				'SELECT trip_id, stop_id, stop_sequence, arrival_time, departure_time FROM stop_times WHERE trip_id = ?',
			);
			for (const id of tripIds) {
				for (const r of stmt.iterate(id)) onRow(r as unknown as StopTimeRow);
			}
		},
		async shape(shapeId: string) {
			return db.prepare('SELECT lat, lon FROM shapes WHERE shape_id = ? ORDER BY seq').all(shapeId) as unknown as ShapePoint[];
		},
	};
}
