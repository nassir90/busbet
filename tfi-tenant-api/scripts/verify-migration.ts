/**
 * Regression guard for the one promise this service makes about user data:
 * a database created by an *older* build must come forward intact.
 *
 * Builds a throwaway database in the pre-migration shape (bare `CREATE TABLE IF NOT
 * EXISTS`, no user_version), puts rows in it, runs the real migrator, and asserts the
 * rows are still there with sane values for columns that did not exist when they were
 * written. Run it before deploying any schema change.
 *
 *   npx tsx scripts/verify-migration.ts
 */
import { createClient } from '@libsql/client';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { migrate, LATEST_VERSION } from '../src/migrations.js';
import { createReportsStore } from '../src/reports.js';

const failures: string[] = [];
function check(label: string, ok: boolean, detail = '') {
	console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${label}${detail ? ` — ${detail}` : ''}`);
	if (!ok) failures.push(label);
}

/** The exact schema shipped before migrations existed. Frozen on purpose: do not "fix" it. */
const LEGACY_SCHEMA = [
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
];

async function main() {
	const dir = mkdtempSync(join(tmpdir(), 'tfi-migration-'));
	const path = join(dir, 'legacy.db');

	try {
		// 1. A database as an old build left it.
		const legacy = createClient({ url: `file:${path}` });
		await legacy.batch(LEGACY_SCHEMA, 'write');
		await legacy.execute({
			sql: `INSERT INTO reports (kind, trip_id, route_short_name, stop_code, service_date,
			      scheduled_departure, actual_time, delay_seconds, reported_at)
			      VALUES ('arrived', 'LEGACY-1', '46A', '767', '20260601', '08:15', '08:19', 240, 1780000000)`,
			args: [],
		});
		await legacy.execute(`INSERT INTO report_undos (report_id, undone_at, reason) VALUES (1, 1780000100, 'legacy undo')`);
		const before = await legacy.execute('SELECT COUNT(*) AS n FROM reports');
		check('legacy database seeded', Number(before.rows[0].n) === 1);

		// 2. Upgrade in place.
		const result = await migrate(legacy);
		check('adopted at version 0', result.from === 0, `from=${result.from}`);
		check(`migrated to v${LATEST_VERSION}`, result.to === LATEST_VERSION, `to=${result.to}`);

		// 3. The row is still there, and the new column has a defensible value.
		const after = await legacy.execute('SELECT * FROM reports');
		check('legacy row survived', after.rows.length === 1);
		const row = after.rows[0] as any;
		check('legacy payload intact', row?.trip_id === 'LEGACY-1' && Number(row?.delay_seconds) === 240);
		check("pre-existing rows default to source='user'", row?.source === 'user', `source=${row?.source}`);
		const undos = await legacy.execute('SELECT COUNT(*) AS n FROM report_undos');
		check('undo markers survived', Number(undos.rows[0].n) === 1);

		// 4. Re-running is a no-op — boots are frequent, migrations must not be.
		const second = await migrate(legacy);
		check('re-migration is a no-op', second.applied.length === 0);

		// 5. The new build can write the new kind against the upgraded database.
		const store = createReportsStore(path);
		const id = await store.insert({
			kind: 'boarded', trip_id: 'NEW-1', route_short_name: '46A', stop_code: '767',
			stop_sequence: 12, service_date: '20260602', scheduled_departure: '08:15',
			actual_time: '08:17', actual_epoch: 1780200000, delay_seconds: 120,
			feed_delay_seconds: 60, reported_at: 1780200030, source: 'user',
		});
		const both = await store.list({ limit: 10, includeUndone: true });
		check('new kind writes against upgraded db', id > 1 && both.length === 2);

		// 6. A fresh database ends up in the same place as an upgraded one — otherwise
		//    dev and production diverge and only production has the bug.
		const freshPath = join(dir, 'fresh.db');
		const fresh = createClient({ url: `file:${freshPath}` });
		await migrate(fresh);
		const shape = async (c: typeof fresh) =>
			(await c.execute(`SELECT name, type FROM pragma_table_info('reports') ORDER BY name`))
				.rows.map((r) => `${r.name}:${r.type}`).join(',');
		const upgradedShape = await shape(legacy);
		const freshShape = await shape(fresh);
		check('fresh schema == upgraded schema', upgradedShape === freshShape,
			upgradedShape === freshShape ? '' : `\n    upgraded: ${upgradedShape}\n    fresh:    ${freshShape}`);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}

	console.log(failures.length ? `\n${failures.length} check(s) FAILED` : '\nall checks passed');
	process.exit(failures.length ? 1 : 0);
}

main().catch((err) => { console.error(err); process.exit(1); });
