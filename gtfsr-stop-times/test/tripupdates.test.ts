/**
 * The trip-updates map read straight off an encoded feed must equal the one built from the
 * fully decoded feed, for whatever the wire can carry: repeated fields, events without delays,
 * negative and oversized varints, unknown fields and groups, and feeds missing required fields.
 *
 * Run:  node --import tsx --test test/*.test.ts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import protobuf from 'protobufjs/minimal.js';
import { tripUpdatesFromDecoded, tripUpdatesFromEncoded } from '../src/gtfs.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

let seed = 12345;
const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const pick = <T>(xs: T[]): T => xs[Math.floor(rnd() * xs.length)];

function randomFeed(): transit_realtime.IFeedMessage {
	const entity: transit_realtime.IFeedEntity[] = [];
	const n = Math.floor(rnd() * 40);
	for (let i = 0; i < n; i++) {
		if (rnd() < 0.1) { entity.push({ id: String(i), vehicle: { trip: { tripId: `t${i}` }, position: { latitude: 53, longitude: -6 } } }); continue; }
		const stopTimeUpdate: transit_realtime.TripUpdate.IStopTimeUpdate[] = [];
		const m = Math.floor(rnd() * 8);
		for (let j = 0; j < m; j++) {
			const delay = () => pick([0, 1, -1, 60, -90, 2 ** 31 - 1, -(2 ** 31), 123456]);
			const stu: transit_realtime.TripUpdate.IStopTimeUpdate = {};
			if (rnd() < 0.8) stu.stopSequence = pick([0, 1, 2, 3, 7, 40, 2 ** 32 - 1]);
			if (rnd() < 0.5) stu.stopId = `s${j}`;
			if (rnd() < 0.6) stu.arrival = rnd() < 0.8 ? { delay: delay() } : rnd() < 0.5 ? {} : { time: 1_793_790_000 };
			if (rnd() < 0.6) stu.departure = rnd() < 0.8 ? { delay: delay() } : rnd() < 0.5 ? {} : { uncertainty: 3 };
			if (rnd() < 0.3) stu.scheduleRelationship = 1;
			stopTimeUpdate.push(stu);
		}
		const trip: transit_realtime.ITripDescriptor = {};
		// Duplicate trip ids across entities: the later entity wins only if it has updates.
		if (rnd() < 0.9) trip.tripId = pick(['', `t${i}`, `t${Math.floor(i / 2)}`, 'ünïcödé.1']);
		if (rnd() < 0.5) { trip.routeId = 'R'; trip.startTime = '08:00:00'; trip.startDate = '20261104'; }
		entity.push({ id: String(i), tripUpdate: { trip, stopTimeUpdate, timestamp: 1_793_790_000, vehicle: { id: 'v' } } });
	}
	return { header: { gtfsRealtimeVersion: '2.0', incrementality: 0, timestamp: 1_793_790_000 }, entity };
}

const same = (raw: Uint8Array) => {
	assert.deepEqual(tripUpdatesFromEncoded(raw), tripUpdatesFromDecoded(FeedMessage.decode(raw)));
};

test('reads the same trip updates as the full decode, over random feeds', () => {
	for (let i = 0; i < 300; i++) same(FeedMessage.encode(FeedMessage.create(randomFeed())).finish());
});

test('matches the decoder on repeated, unknown and oddly encoded fields', () => {
	// Hand-built wire: header, then an entity whose trip update repeats its trip descriptor,
	// repeats an arrival (the last wins), carries unknown fields of every wire type, a group,
	// a 10-byte stop sequence, and a negative delay on the departure.
	const tag = (field: number, wire: number) => (field << 3) | wire;
	const w = protobuf.Writer.create();
	w.uint32(tag(1, 2)).fork().uint32(tag(1, 2)).string('2.0').ldelim();
	w.uint32(tag(2, 2)).fork()
		.uint32(tag(1, 2)).string('e1')
		.uint32(tag(3, 2)).fork()
			.uint32(tag(1, 2)).fork().uint32(tag(1, 2)).string('first').ldelim()
			.uint32(tag(1, 2)).fork().uint32(tag(1, 2)).string('second').uint32(tag(5, 2)).string('R').ldelim()
			.uint32(tag(2, 2)).fork()
				.uint32(tag(1, 0)).uint64(4294967296 + 5)                  // stop_sequence 2^32+5: low 32 bits
				.uint32(tag(2, 2)).fork().uint32(tag(1, 0)).int32(10).ldelim()
				.uint32(tag(2, 2)).fork().uint32(tag(1, 0)).int32(20).ldelim()  // second arrival replaces the first
				.uint32(tag(15, 0)).uint32(7)                               // unknown varint
				.uint32(tag(16, 1)).fixed64(1)                              // unknown fixed64
				.uint32(tag(17, 5)).fixed32(1)                              // unknown fixed32
				.uint32(tag(18, 2)).string('unknown bytes')                 // unknown length-delimited
				.uint32(tag(19, 3)).uint32(tag(1, 0)).uint32(1).uint32(tag(19, 4))  // unknown group { field 1 }
			.ldelim()
			.uint32(tag(2, 2)).fork()
				.uint32(tag(1, 0)).uint32(2)
				.uint32(tag(3, 2)).fork().uint32(tag(1, 0)).int32(-45).ldelim()
				.uint32(tag(2, 2)).fork().uint32(tag(1, 0)).int32(99).ldelim()  // arrival present, departure wins
			.ldelim()
			.uint32(tag(2, 2)).fork().uint32(tag(1, 0)).uint32(1).uint32(tag(4, 2)).string('stop').ldelim()  // no event: dropped
			.uint32(tag(2, 2)).fork().uint32(tag(3, 2)).fork().ldelim().ldelim()                            // empty departure: 0 at seq 0
		.ldelim()
	.ldelim();
	const raw = w.finish();
	same(raw);
	assert.deepEqual([...tripUpdatesFromEncoded(raw)], [
		['second', [{ stopSequence: 0, delay: 0 }, { stopSequence: 2, delay: -45 }, { stopSequence: 5, delay: 20 }]],
	]);
});

test('throws where the decoder throws', () => {
	const bad: Uint8Array[] = [
		new Uint8Array([0x12, 0x04, 0x0a, 0x02, 0x65, 0x31]),                            // no header
		new Uint8Array([0x0a, 0x00]),                                                     // header without a version
		new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30, 0x12, 0x02, 0x1a, 0x00]),  // entity without an id, trip update without a trip
		new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30, 0x12, 0x06, 0x0a, 0x02, 0x65, 0x31, 0x1a, 0x00]),  // trip update without a trip
		new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30, 0x12, 0x09]),          // truncated
		new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30, 0x12, 0x03, 0x0a, 0x01]),  // truncated string
		new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30, 0x1e]),                // wire type 6
	];
	for (const raw of bad) {
		assert.throws(() => FeedMessage.decode(raw), 'decoder');
		assert.throws(() => tripUpdatesFromEncoded(raw), 'reader');
	}
	// A valid feed with no entities is empty, from both.
	same(new Uint8Array([0x0a, 0x05, 0x0a, 0x03, 0x32, 0x2e, 0x30]));
});
