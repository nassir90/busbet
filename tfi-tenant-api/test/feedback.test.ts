/**
 * The feedback folder: what it accepts, what it writes, and the size cap.
 *
 * Run:  npm test
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createFeedbackStore } from '../src/feedback.js';

const PNG_1x1 = Buffer.from(
	'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
	'base64',
);

function freshDir() {
	const dir = mkdtempSync(join(tmpdir(), 'feedback-test-'));
	process.on('exit', () => rmSync(dir, { recursive: true, force: true }));
	return dir;
}

test('stores the record and its screenshot side by side', () => {
	const dir = freshDir();
	const store = createFeedbackStore(dir, 1_000_000);
	const r = store.save({
		description: 'The slider is weird',
		screen: 'Settings',
		context: '{"screen":"Settings"}',
		app_version: '1.13 (14)',
		boxes: [{ index: 1, left: 0.1, top: 0.2, right: 0.5, bottom: 0.6, label: 'slider' }],
		screenshot_base64: PNG_1x1.toString('base64'),
	});
	assert.ok('ok' in r);
	const files = readdirSync(dir).sort();
	assert.deepEqual(files, [`${r.id}.json`, `${r.id}.png`]);
	const record = JSON.parse(readFileSync(join(dir, `${r.id}.json`), 'utf8'));
	assert.equal(record.description, 'The slider is weird');
	assert.deepEqual(record.context, { screen: 'Settings' });
	assert.equal(record.screenshot, `${r.id}.png`);
	assert.deepEqual(readFileSync(join(dir, `${r.id}.png`)), PNG_1x1);
});

test('text alone is enough; nothing at all is not', () => {
	const store = createFeedbackStore(freshDir(), 1_000_000);
	assert.ok('ok' in store.save({ description: 'Love the maps' }));
	const empty = store.save({ description: '   ' });
	assert.ok('error' in empty && empty.status === 400);
});

test('refuses what is not a screenshot', () => {
	const store = createFeedbackStore(freshDir(), 1_000_000);
	const r = store.save({ description: 'x', screenshot_base64: Buffer.from('<html>').toString('base64') });
	assert.ok('error' in r && r.status === 415);
});

test('refuses malformed boxes', () => {
	const store = createFeedbackStore(freshDir(), 1_000_000);
	const r = store.save({ description: 'x', boxes: [{ index: 1, left: -1, top: 0, right: 2, bottom: 1 }] });
	assert.ok('error' in r && r.status === 400);
});

test('the folder cap refuses with 507 and writes nothing', () => {
	const dir = freshDir();
	const store = createFeedbackStore(dir, 600);
	assert.ok('ok' in store.save({ description: 'a'.repeat(100) }));
	const before = readdirSync(dir).length;
	const r = store.save({ description: 'b'.repeat(400) });
	assert.ok('error' in r && r.status === 507);
	assert.equal(readdirSync(dir).length, before);
});

test('the cap counts what was already in the folder at startup', () => {
	const dir = freshDir();
	writeFileSync(join(dir, 'old.json'), 'x'.repeat(900));
	const store = createFeedbackStore(dir, 1000);
	assert.equal(store.usedBytes, 900);
	const r = store.save({ description: 'too much for what is left' });
	assert.ok('error' in r && r.status === 507);
});
