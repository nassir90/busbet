/**
 * Departures with the realtime overlay, end to end: a snapshot directory holding one encoded
 * TripUpdates feed, a stub timetable, and the delays the response must carry. These pin the
 * exact semantics of the overlay (which stop time update applies, how a missing or empty
 * arrival/departure reads, how an absent trip id is treated) so the feed can be read
 * differently without any response changing.
 *
 * Run:  node --import tsx --test test/*.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'fs';
import { join } from 'path';
import { tmpdir } from 'os';
import { gzipSync } from 'zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import { getDepartures, getDeparturesBatch } from '../src/departures.js';
import type { Departure, GtfsStorage, Stop } from '../src/types.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const T = 1_793_790_000;
const name = (ts: number) => `${new Date(ts * 1000).toISOString().replace(/[:.]/g, '-')}_${ts}.pb.gz`;

function dep(trip_id: string, stop_sequence: number, scheduled_departure: string): Departure {
	return {
		trip_id, stop_id: 'S1', stop_sequence, route_short_name: '1', route_type: 3, direction_id: 0,
		trip_headsign: 'H', scheduled_departure, estimated_departure: null, delay_seconds: null, realtime: false,
	};
}

const STOPS: Record<string, Stop> = {
	'1001': { stop_id: 'S1', stop_code: '1001', stop_name: 'One', route_types: [3] },
	'1002': { stop_id: 'S2', stop_code: '1002', stop_name: 'Two', route_types: [3] },
};

const storage = {
	async getStopByCode(code: string) { return STOPS[code] ?? null; },
	async getActiveServiceIds() { return ['svc']; },
	async getScheduledDepartures(stopId: string) {
		if (stopId !== 'S1') return [dep('other', 1, '12:00')];
		return [
			dep('trip-a', 5, '12:00'),  // update at seq 3 and 7: the one at 3 applies
			dep('trip-b', 2, '12:30'),  // update only at seq 4: nothing applies yet
			dep('trip-c', 1, '23:58'),  // empty departure event reads as a delay of 0
			dep('trip-d', 1, '12:10'),  // arrival-only delay, negative (rounds to -1 min)
			dep('trip-e', 1, '12:20'),  // no update in the feed
			dep('trip-f', 1, '12:40'),  // updates arrive out of sequence order
		];
	},
} as unknown as GtfsStorage;

function writeFeed(dir: string, ts: number, entity: transit_realtime.IFeedEntity[]) {
	const msg = FeedMessage.create({ header: { gtfsRealtimeVersion: '2.0', timestamp: ts }, entity });
	writeFileSync(join(dir, name(ts)), gzipSync(FeedMessage.encode(msg).finish()));
}

test('realtime delays are applied per stop sequence, exactly as before', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'busbet-dep-test-'));
	try {
		writeFeed(dir, T, [
			{ id: '1', tripUpdate: { trip: { tripId: 'trip-a' }, stopTimeUpdate: [
				{ stopSequence: 3, departure: { delay: 120 } },
				{ stopSequence: 7, departure: { delay: 600 } },
			] } },
			{ id: '2', tripUpdate: { trip: { tripId: 'trip-b' }, stopTimeUpdate: [{ stopSequence: 4, arrival: { delay: 60 } }] } },
			{ id: '3', tripUpdate: { trip: { tripId: 'trip-c' }, stopTimeUpdate: [{ stopSequence: 1, departure: {}, arrival: { delay: 300 } }] } },
			{ id: '4', tripUpdate: { trip: { tripId: 'trip-d' }, stopTimeUpdate: [{ stopSequence: 1, arrival: { delay: -90 } }] } },
			{ id: '5', tripUpdate: { trip: { tripId: 'trip-f' }, stopTimeUpdate: [
				{ stopSequence: 9, departure: { delay: 900 } },
				{ stopSequence: 1, departure: { delay: 30 } },
				{ stopSequence: 2, departure: { delay: 60 } },
			] } },
			{ id: '6', tripUpdate: { trip: { tripId: 'trip-e' }, stopTimeUpdate: [{ stopSequence: 1, stopId: 'X' }] } },
			{ id: '7', tripUpdate: { trip: {}, stopTimeUpdate: [{ stopSequence: 1, departure: { delay: 5 } }] } },
			{ id: '8', vehicle: { trip: { tripId: 'trip-e' }, position: { latitude: 53.3, longitude: -6.2 } } },
		]);
		const r = await getDepartures('1001', storage, dir, T + 10);
		assert.ok(r);
		assert.equal(r.feed_timestamp, T);
		assert.equal(r.stale, false);
		assert.deepEqual(r.departures.map((d) => [d.trip_id, d.delay_seconds, d.estimated_departure, d.realtime]), [
			['trip-a', 120, '12:02', true],
			['trip-b', null, null, false],
			['trip-c', 0, '23:58', true],
			['trip-d', -90, '12:09', true],  // -90s rounds to -1 min
			['trip-e', null, null, false],
			['trip-f', 30, '12:41', true],
		]);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});

test('a stale or missing snapshot leaves the schedule untouched', async () => {
	const dir = mkdtempSync(join(tmpdir(), 'busbet-dep-test-'));
	try {
		writeFeed(dir, T, [
			{ id: '1', tripUpdate: { trip: { tripId: 'trip-a' }, stopTimeUpdate: [{ stopSequence: 1, departure: { delay: 120 } }] } },
		]);
		const stale = await getDepartures('1001', storage, dir, T + 1000, 300);
		assert.equal(stale?.stale, true);
		assert.equal(stale?.feed_timestamp, T);
		assert.ok(stale?.departures.every((d) => !d.realtime && d.delay_seconds === null));

		const before = await getDepartures('1001', storage, dir, T - 1);
		assert.equal(before?.stale, true);
		assert.equal(before?.feed_timestamp, null);

		const batch = await getDeparturesBatch(['1001', 'nope', '1002'], storage, dir, T + 5);
		assert.deepEqual([...batch.keys()], ['1001', '1002']);
		assert.equal(batch.get('1001')?.departures[0].delay_seconds, 120);
		assert.equal(batch.get('1002')?.departures[0].realtime, false);
	} finally {
		rmSync(dir, { recursive: true, force: true });
	}
});
