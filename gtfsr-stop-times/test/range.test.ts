/**
 * rangeSteps drives /departures/{code}/range and /vehicles/{code}/range, which are public. Its
 * old `t += step` loop never ended when `t` was too large for the step to change it, so one GET
 * with start=end=1e300 ran the server out of memory. These pin the bounds that stop that.
 *
 * Run:  node --import tsx --test test/*.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { rangeSteps } from '../src/snapshots.js';

const T = 1_793_600_000; // 2026-11-02

test('samples start..end inclusive at the step', () => {
	assert.deepEqual(rangeSteps(T, T + 120, 60), [T, T + 60, T + 120]);
	assert.deepEqual(rangeSteps(T, T + 100, 30), [T, T + 30, T + 60, T + 90]);
	assert.deepEqual(rangeSteps(T, T, 60), [T]);
	assert.equal(rangeSteps(T, T + 999 * 30, 30)?.length, 1000);
});

test('rejects instants a step cannot move, and steps below a second', () => {
	// Each of these used to loop until the heap ran out.
	assert.equal(rangeSteps(1e300, 1e300, 60), null);
	assert.equal(rangeSteps(1e21, 1e21, 30), null);
	assert.equal(rangeSteps(T, T, 0.0000001), null);
	assert.equal(rangeSteps(T, T + 10, 0.5), null);
});

test('rejects out-of-bounds, inverted, non-finite and oversized ranges', () => {
	assert.equal(rangeSteps(0, 60, 30), null, 'before 2000');
	assert.equal(rangeSteps(T, 5_000_000_000, 60), null, 'after 2100');
	assert.equal(rangeSteps(T + 60, T, 30), null, 'end before start');
	assert.equal(rangeSteps(NaN, T, 30), null);
	assert.equal(rangeSteps(T, Infinity, 30), null);
	assert.equal(rangeSteps(T, T + 60, NaN), null);
	assert.equal(rangeSteps(T, T + 1000 * 30, 30), null, '1001 steps');
});
