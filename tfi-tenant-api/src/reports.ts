import { createClient } from '@libsql/client';

/** A user-submitted observation of an actual arrival or a cancellation. */
export interface ReportInput {
	kind: 'arrived' | 'cancelled';
	trip_id: string;
	route_short_name: string | null;
	stop_code: string;
	stop_sequence: number | null;
	service_date: string;            // YYYYMMDD (GTFS service day)
	scheduled_departure: string | null;
	actual_time: string | null;      // HH:MM as reported
	actual_epoch: number | null;     // derived unix seconds
	delay_seconds: number | null;    // actual − scheduled
	feed_delay_seconds: number | null;
	reported_at: number;             // unix seconds
}

/** Append-only store of arrival/cancellation reports. */
export function createReportsStore(dbPath: string) {
	const db = createClient({ url: `file:${dbPath}` });
	let ready: Promise<void> | null = null;

	function ensure(): Promise<void> {
		if (!ready) {
			ready = db.execute(`
				CREATE TABLE IF NOT EXISTS reports (
					id                  INTEGER PRIMARY KEY AUTOINCREMENT,
					kind                TEXT    NOT NULL,
					trip_id             TEXT    NOT NULL,
					route_short_name    TEXT,
					stop_code           TEXT    NOT NULL,
					stop_sequence       INTEGER,
					service_date        TEXT    NOT NULL,
					scheduled_departure TEXT,
					actual_time         TEXT,
					actual_epoch        INTEGER,
					delay_seconds       INTEGER,
					feed_delay_seconds  INTEGER,
					reported_at         INTEGER NOT NULL
				)
			`).then(() => undefined);
		}
		return ready;
	}

	return {
		async insert(r: ReportInput): Promise<void> {
			await ensure();
			await db.execute({
				sql: `INSERT INTO reports
				      (kind, trip_id, route_short_name, stop_code, stop_sequence, service_date,
				       scheduled_departure, actual_time, actual_epoch, delay_seconds, feed_delay_seconds, reported_at)
				      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				args: [
					r.kind, r.trip_id, r.route_short_name, r.stop_code, r.stop_sequence, r.service_date,
					r.scheduled_departure, r.actual_time, r.actual_epoch, r.delay_seconds, r.feed_delay_seconds, r.reported_at,
				],
			});
		},

		async list(serviceDate: string | null, limit = 200) {
			await ensure();
			const r = serviceDate
				? await db.execute({ sql: `SELECT * FROM reports WHERE service_date = ? ORDER BY id DESC LIMIT ?`, args: [serviceDate, limit] })
				: await db.execute({ sql: `SELECT * FROM reports ORDER BY id DESC LIMIT ?`, args: [limit] });
			return r.rows;
		},
	};
}
