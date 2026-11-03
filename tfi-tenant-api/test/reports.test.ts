/**
 * What POST /report accepts, and the undo token that stops anyone undoing anyone's report.
 *
 * Run:  npm test
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { validateReport, validateUndo } from '../src/validate.js';
import { createReportsStore, newUndoToken } from '../src/reports.js';

const NOW = Date.UTC(2026, 10, 3, 9, 30) / 1000;  // 2026-11-03 09:30 UTC
const GOOD = {
	kind: 'boarded', trip_id: '6016_12345', stop_code: '1358', route_short_name: '46A',
	service_date: '20261103', actual_time: '09:28', reported_at: NOW - 60,
};

test('accepts what the app sends', () => {
	assert.deepEqual(validateReport(GOOD, NOW), GOOD);
	const cancelled = validateReport({ ...GOOD, kind: 'cancelled', actual_time: null }, NOW);
	assert.ok(!('error' in cancelled) && cancelled.actual_time === null);
	const noRoute = validateReport({ ...GOOD, route_short_name: '' }, NOW);
	assert.ok(!('error' in noRoute) && noRoute.route_short_name === null);
});

test('refuses oversized and wrongly typed fields', () => {
	const bad: Record<string, unknown>[] = [
		{ trip_id: 'x'.repeat(65) },
		{ trip_id: { nested: true } },
		{ trip_id: 'a b' },
		{ stop_code: '1'.repeat(17) },
		{ stop_code: 1358 },
		{ route_short_name: 'R'.repeat(33) },
		{ route_short_name: ['46A'] },
		{ kind: 'teleported' },
	];
	for (const fields of bad) {
		assert.ok('error' in validateReport({ ...GOOD, ...fields }, NOW), JSON.stringify(fields));
	}
	assert.ok('error' in validateReport([GOOD], NOW));
	assert.ok('error' in validateReport('report', NOW));
});

test('refuses times and dates that are not real or not near today', () => {
	for (const actual_time of ['9:28', '24:00', '09:60', '0928', null, 928]) {
		assert.ok('error' in validateReport({ ...GOOD, actual_time }, NOW), String(actual_time));
	}
	for (const service_date of ['20261399', '20260230', '2026113', '20251103', '20261110', 20261103]) {
		assert.ok('error' in validateReport({ ...GOOD, service_date }, NOW), String(service_date));
	}
	assert.ok(!('error' in validateReport({ ...GOOD, service_date: '20261102' }, NOW)), 'yesterday is fine');
});

test('keeps a report from a phone with a wrong clock, with the server time', () => {
	const r = validateReport({ ...GOOD, reported_at: 1e300 }, NOW);
	assert.ok(!('error' in r) && r.reported_at === NOW);
	assert.ok('error' in validateReport({ ...GOOD, reported_at: 'yesterday' }, NOW));
});

test('undo needs the token issued with the report', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'reports-test-'));
	try {
		const store = createReportsStore(join(dir, 'reports.db'));
		const undo = newUndoToken();
		const id = await store.insert({
			...GOOD, kind: 'boarded', stop_sequence: null, scheduled_departure: null, actual_epoch: null,
			delay_seconds: null, feed_delay_seconds: null, undo_token_hash: undo.hash,
		});
		assert.equal(await store.undoTokenMatches(id, newUndoToken().token), false);
		assert.equal(await store.undoTokenMatches(id, undo.token), true);
		assert.equal(await store.undoTokenMatches(id + 1, undo.token), null);

		// Rows with no token (simulated, or from before tokens) cannot be undone over HTTP.
		const legacy = await store.insert({
			...GOOD, kind: 'boarded', stop_sequence: null, scheduled_departure: null, actual_epoch: null,
			delay_seconds: null, feed_delay_seconds: null,
		});
		assert.equal(await store.undoTokenMatches(legacy, undo.token), false);
		store.db.close();
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

test('the undo body is checked before the database is', () => {
	assert.ok('error' in validateUndo(null));
	assert.ok('error' in validateUndo({}));
	assert.ok('error' in validateUndo({ undo_token: 'x'.repeat(65) }));
	assert.ok('error' in validateUndo({ undo_token: 42 }));
	assert.deepEqual(validateUndo({ undo_token: 'abc_DEF-123' }), { undo_token: 'abc_DEF-123' });
});
