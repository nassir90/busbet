import type { Client } from '@libsql/client';

/**
 * Forward-only schema migrations for the reports database.
 *
 * The failure mode this exists to prevent: the store used to bootstrap itself with a
 * bare `CREATE TABLE IF NOT EXISTS`. That silently does nothing once the table exists,
 * so any later schema change either never lands on a live database or gets "fixed" by
 * dropping the table — throwing away every observation collected so far. User-generated
 * reports are unrecoverable (nobody re-reports a bus from three weeks ago), so the
 * schema has to be able to move forward *underneath* the existing rows.
 *
 * Rules that keep that promise:
 *   - Migrations are append-only. Never edit a released migration; add a new one.
 *   - Migrations are additive. New columns get defaults; no DROP COLUMN, no destructive
 *     rewrite of rows. A column that becomes obsolete is left in place and ignored.
 *   - Each migration runs in one transaction together with its `PRAGMA user_version`
 *     bump (user_version is transactional in SQLite), so a crash mid-migration rolls
 *     back cleanly and the migration is retried on next boot rather than half-applied.
 */
export interface Migration {
	version: number;
	name: string;
	statements: string[];
}

export const MIGRATIONS: Migration[] = [
	{
		version: 1,
		name: 'baseline',
		// Deliberately IF NOT EXISTS: databases created before migrations existed already
		// have exactly this shape, so version 1 adopts them in place instead of failing or
		// (worse) needing them to be recreated empty.
		statements: [
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
		],
	},
	{
		version: 2,
		name: 'report-source-and-indexes',
		statements: [
			// Provenance. Simulated and backfilled rows live in the same table as real ones
			// (so they exercise the same pipeline) and must be separable at analysis time.
			// Rows that predate this column are, by definition, real user reports.
			`ALTER TABLE reports ADD COLUMN source TEXT NOT NULL DEFAULT 'user'`,
			`CREATE INDEX IF NOT EXISTS reports_service_date ON reports(service_date)`,
			`CREATE INDEX IF NOT EXISTS reports_trip_stop ON reports(trip_id, stop_code)`,
		],
	},
	// Note: the `boarded` kind ("the user actually got on this bus", as distinct from
	// "the bus turned up") needed no migration — `kind` is an open TEXT domain precisely
	// so that new observation types do not require a schema change.
];

/** Schema version this build expects; compare against a live `/schema` to spot a stale deploy. */
export const LATEST_VERSION = MIGRATIONS[MIGRATIONS.length - 1].version;

export interface MigrationResult {
	from: number;
	to: number;
	applied: { version: number; name: string }[];
}

/** Bring `db` up to the latest schema version. Idempotent; safe to call on every boot. */
export async function migrate(db: Client, migrations: Migration[] = MIGRATIONS): Promise<MigrationResult> {
	// Audit trail of what ran and when — invaluable when a live database behaves
	// differently from a fresh one and you need to know which step it stopped at.
	await db.execute(`CREATE TABLE IF NOT EXISTS schema_migrations (
		version    INTEGER PRIMARY KEY,
		name       TEXT    NOT NULL,
		applied_at INTEGER NOT NULL
	)`);

	const current = await db.execute('PRAGMA user_version');
	const from = Number(Object.values(current.rows[0] ?? {})[0] ?? 0);
	const pending = migrations.filter((m) => m.version > from).sort((a, b) => a.version - b.version);

	const applied: { version: number; name: string }[] = [];
	for (const m of pending) {
		await db.batch(
			[
				...m.statements,
				{
					sql: `INSERT OR REPLACE INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)`,
					args: [m.version, m.name, Math.floor(Date.now() / 1000)],
				},
				// Interpolated rather than bound: SQLite does not accept parameters in a
				// PRAGMA. The value is a literal from this file, never user input.
				`PRAGMA user_version = ${m.version}`,
			],
			'write',
		);
		applied.push({ version: m.version, name: m.name });
	}

	return { from, to: pending.length ? pending[pending.length - 1].version : from, applied };
}
