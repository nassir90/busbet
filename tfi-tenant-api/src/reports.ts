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
			ready = db.batch([
				`CREATE TABLE IF NOT EXISTS reports (
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
				)`,
				`CREATE TABLE IF NOT EXISTS report_undos (
					report_id           INTEGER PRIMARY KEY,
					undone_at           INTEGER NOT NULL,
					reason              TEXT,
					FOREIGN KEY(report_id) REFERENCES reports(id)
				)`,
			], 'write').then(() => undefined);
		}
		return ready;
	}

	return {
		async insert(r: ReportInput): Promise<number> {
			await ensure();
			const result = await db.execute({
				sql: `INSERT INTO reports
				      (kind, trip_id, route_short_name, stop_code, stop_sequence, service_date,
				       scheduled_departure, actual_time, actual_epoch, delay_seconds, feed_delay_seconds, reported_at)
				      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				args: [
					r.kind, r.trip_id, r.route_short_name, r.stop_code, r.stop_sequence, r.service_date,
					r.scheduled_departure, r.actual_time, r.actual_epoch, r.delay_seconds, r.feed_delay_seconds, r.reported_at,
				],
			});
			return Number(result.lastInsertRowid);
		},

		async undo(id: number, undoneAt = Math.floor(Date.now() / 1000), reason: string | null = null) {
			await ensure();
			const existing = await db.execute({ sql: `SELECT id FROM reports WHERE id = ?`, args: [id] });
			if (existing.rows.length === 0) return { found: false, undone: false };

			const result = await db.execute({
				sql: `INSERT OR IGNORE INTO report_undos (report_id, undone_at, reason) VALUES (?, ?, ?)`,
				args: [id, undoneAt, reason],
			});
			return { found: true, undone: result.rowsAffected > 0 };
		},

		async list(serviceDate: string | null, limit = 200, includeUndone = false) {
			await ensure();
			const where = [
				serviceDate ? 'r.service_date = ?' : null,
				includeUndone ? null : 'u.report_id IS NULL',
			].filter(Boolean).join(' AND ');
			const args: (string | number)[] = serviceDate ? [serviceDate] : [];
			args.push(limit);
			const r = await db.execute({
				sql: `SELECT r.*, u.undone_at, u.reason AS undo_reason, u.report_id IS NOT NULL AS undone
				      FROM reports r
				      LEFT JOIN report_undos u ON u.report_id = r.id
				      ${where ? `WHERE ${where}` : ''}
				      ORDER BY r.id DESC
				      LIMIT ?`,
				args,
			});
			return r.rows;
		},
	};
}
