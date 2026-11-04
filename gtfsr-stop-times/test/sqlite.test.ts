/**
 * Stop lookups against a small timetable in a temporary SQLite file: a stop found by id or by
 * code carries the route types serving it, the same on every call.
 *
 * Run:  node --import tsx --test test/*.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'fs';
import { join } from 'path';
import { tmpdir } from 'os';
import { createClient } from '@libsql/client';
import { createSqliteBackend } from '../src/sqlite.js';

async function seed(path: string) {
	const db = createClient({ url: `file:${path}` });
	await db.executeMultiple(`
		CREATE TABLE stops (stop_id TEXT PRIMARY KEY, stop_code TEXT, stop_name TEXT, stop_lat REAL, stop_lon REAL);
		CREATE TABLE routes (route_id TEXT PRIMARY KEY, route_short_name TEXT, route_long_name TEXT, agency_id TEXT, route_type INTEGER);
		CREATE TABLE trips (trip_id TEXT PRIMARY KEY, route_id TEXT, service_id TEXT, trip_headsign TEXT, direction_id INTEGER, shape_id TEXT);
		CREATE TABLE stop_times (trip_id TEXT, stop_id TEXT, stop_sequence INTEGER, arrival_time TEXT, departure_time TEXT, PRIMARY KEY (trip_id, stop_sequence));
		INSERT INTO stops VALUES ('8220DB000001', '1', 'Bus stop', 53.3, -6.2);
		INSERT INTO stops VALUES ('8250IR0031', '0', 'Station', 53.4, -6.1);
		INSERT INTO stops VALUES ('8250IR0032', '0', 'Other station', 53.5, -6.0);
		INSERT INTO stops VALUES ('8220DB000002', '2', 'Lonely stop', 53.2, -6.3);
		INSERT INTO routes VALUES ('R1', '1', 'Route 1', 'A', 3);
		INSERT INTO routes VALUES ('R2', '2', 'Route 2', 'A', 3);
		INSERT INTO routes VALUES ('RL', 'Green', 'Luas', 'L', 0);
		INSERT INTO routes VALUES ('RD', 'DART', 'Rail', 'I', 2);
		INSERT INTO routes VALUES ('RN', 'N', 'Untyped', 'A', NULL);
		INSERT INTO trips VALUES ('t1', 'R1', 's', 'H', 0, NULL);
		INSERT INTO trips VALUES ('t2', 'R2', 's', 'H', 0, NULL);
		INSERT INTO trips VALUES ('t3', 'RL', 's', 'H', 0, NULL);
		INSERT INTO trips VALUES ('t4', 'RD', 's', 'H', 0, NULL);
		INSERT INTO trips VALUES ('t5', 'RN', 's', 'H', 0, NULL);
		INSERT INTO stop_times VALUES ('t1', '8220DB000001', 1, '10:00:00', '10:00:00');
		INSERT INTO stop_times VALUES ('t2', '8220DB000001', 1, '10:05:00', '10:05:00');
		INSERT INTO stop_times VALUES ('t3', '8220DB000001', 1, '10:10:00', '10:10:00');
		INSERT INTO stop_times VALUES ('t5', '8220DB000001', 1, '10:15:00', '10:15:00');
		INSERT INTO stop_times VALUES ('t4', '8250IR0031', 1, '10:00:00', '10:00:00');
		INSERT INTO stop_times VALUES ('t3', '8250IR0032', 2, '10:20:00', '10:20:00');
	`);
	db.close();
}

test('a stop by id or code carries the sorted route types that serve it', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'busbet-sqlite-test-'));
	try {
		const path = join(dir, 'gtfs.db');
		await seed(path);
		const storage = createSqliteBackend(path);

		const byCode = await storage.getStopByCode('1');
		assert.deepEqual(byCode, {
			stop_id: '8220DB000001', stop_code: '1', stop_name: 'Bus stop', stop_lat: 53.3, stop_lon: -6.2,
			route_types: [0, 3],
		});
		// The same answer on a repeat, and by id.
		assert.deepEqual(await storage.getStopByCode('1'), byCode);
		assert.deepEqual(await storage.getStopByCode('8220DB000001'), byCode);

		// Stop codes are shared: the lowest stop_id wins, and an id picks one station exactly.
		assert.deepEqual((await storage.getStopByCode('0'))?.route_types, [2]);
		assert.deepEqual((await storage.getStopByCode('8250IR0032'))?.route_types, [0]);
		assert.deepEqual((await storage.getStopByCode('8250IR0032'))?.stop_name, 'Other station');

		assert.deepEqual((await storage.getStopByCode('2'))?.route_types, []);
		assert.equal(await storage.getStopByCode('none'), null);
		assert.equal(await storage.getStopByCode('8220DB000009'), null);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});
