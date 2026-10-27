/**
 * The snapshot index used to re-list the feeds directory every 15s on the request path. It now
 * lists once and watches; these check that new snapshots still show up well inside that old
 * TTL, that a half-written one is not served early, and that a removal is picked up.
 *
 * Run:  npm test
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, unlinkSync } from 'fs';
import { join } from 'path';
import { tmpdir } from 'os';
import { selectAt, SETTLE_MS } from '../src/snapshots.js';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const name = (ts: number) => `${new Date(ts * 1000).toISOString().replace(/[:.]/g, '-')}_${ts}.pb.gz`;

test('a new snapshot is selected once it settles, without waiting out a re-list TTL', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'busbet-snap-test-'));
	try {
		const t0 = 1_791_500_000;
		writeFileSync(join(dir, name(t0)), '');
		assert.equal(selectAt(dir, t0 + 60, 300).cur?.ts, t0);

		writeFileSync(join(dir, name(t0 + 30)), '');
		assert.equal(selectAt(dir, t0 + 60, 300).cur?.ts, t0, 'not served before it settles');

		await sleep(SETTLE_MS + 500);
		const sel = selectAt(dir, t0 + 60, 300);
		assert.equal(sel.cur?.ts, t0 + 30);
		assert.equal(sel.prev?.ts, t0);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

test('a removed snapshot drops out after the background re-list', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'busbet-snap-test-'));
	try {
		const t0 = 1_791_600_000;
		writeFileSync(join(dir, name(t0)), '');
		writeFileSync(join(dir, name(t0 + 30)), '');
		assert.equal(selectAt(dir, t0 + 60, 300).cur?.ts, t0 + 30);

		unlinkSync(join(dir, name(t0 + 30)));
		await sleep(SETTLE_MS + 500);
		selectAt(dir, t0 + 60, 300); // sees the removal and kicks off the re-list
		await sleep(200);
		assert.equal(selectAt(dir, t0 + 60, 300).cur?.ts, t0);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});
