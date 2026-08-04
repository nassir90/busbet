import { createClient } from '@libsql/client';
import { migrate } from './migrations.js';
import type { ReportKind } from './derive.js';

/** A user-submitted observation of a boarding, an arrival, or a cancellation. */
export interface ReportInput {
	kind: ReportKind;
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
	source?: string;                 // 'user' (default) | 'simulated' | ...
}

export interface ListOptions {
	serviceDate?: string | null;
	limit?: number;
	includeUndone?: boolean;
	source?: string | null;
}

/** Append-only store of boarding/arrival/cancellation reports. */
export function createReportsStore(dbPath: string) {
	const db = createClient({ url: `file:${dbPath}` });
	let ready: Promise<void> | null = null;

	// Memoised, so it is safe to call eagerly at boot (to fail loudly on a bad deploy)
	// and again on every operation (so scripts that skip boot are never unmigrated).
	function ensure(): Promise<void> {
		if (!ready) {
			ready = migrate(db).then((result) => {
				for (const m of result.applied) console.log(`[reports] migrated to v${m.version} (${m.name})`);
			});
		}
		return ready;
	}

	return {
		/** Exposed for scripts that need the schema up to date before querying directly. */
		ready: ensure,
		db,

		async insert(r: ReportInput): Promise<number> {
			await ensure();
			const result = await db.execute({
				sql: `INSERT INTO reports
				      (kind, trip_id, route_short_name, stop_code, stop_sequence, service_date,
				       scheduled_departure, actual_time, actual_epoch, delay_seconds, feed_delay_seconds,
				       reported_at, source)
				      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
				args: [
					r.kind, r.trip_id, r.route_short_name, r.stop_code, r.stop_sequence, r.service_date,
					r.scheduled_departure, r.actual_time, r.actual_epoch, r.delay_seconds, r.feed_delay_seconds,
					r.reported_at, r.source ?? 'user',
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

		async list(opts: ListOptions = {}) {
			await ensure();
			const { serviceDate = null, limit = 200, includeUndone = false, source = null } = opts;
			const args: (string | number)[] = [];
			const clauses: string[] = [];
			if (serviceDate) { clauses.push('r.service_date = ?'); args.push(serviceDate); }
			if (source) { clauses.push('r.source = ?'); args.push(source); }
			if (!includeUndone) clauses.push('u.report_id IS NULL');
			args.push(limit);
			const r = await db.execute({
				sql: `SELECT r.*, u.undone_at, u.reason AS undo_reason, u.report_id IS NOT NULL AS undone
				      FROM reports r
				      LEFT JOIN report_undos u ON u.report_id = r.id
				      ${clauses.length ? `WHERE ${clauses.join(' AND ')}` : ''}
				      ORDER BY r.id DESC
				      LIMIT ?`,
				args,
			});
			return r.rows;
		},
	};
}

export type ReportsStore = ReturnType<typeof createReportsStore>;
