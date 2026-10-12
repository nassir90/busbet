/**
 * The journey planner on a five-stop toy network small enough to work out by hand:
 *
 *   A ──R1──▶ B ──R1──▶ C            (08:00, 08:30)
 *                       C' ──R2──▶ D  (08:25, 08:40)   C' is 200 m from C
 *   A ─────────R3 (slow, direct)───▶ D  (08:05 → 08:50)
 *
 * Run:  npm test
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
// CommonJS package: default import, then reach in (see src/snapshots.ts).
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
const { transit_realtime } = GtfsRealtimeBindings as any;
import { Planner } from '../src/plan/planner.js';
import type { TimetableSource, StopTimeRow } from '../src/plan/sources.js';
import { epochZeroOf } from '../src/plan/timetable.js';

const DATE = '20261013';
const T = (hhmm: string) => `${hhmm}:00`;

// Stops ~2 km apart north-south, except C' which is 200 m east of C.
const STOPS = [
	{ stop_id: 'A', stop_code: '1', stop_name: 'A', stop_lat: 53.30, stop_lon: -6.30 },
	{ stop_id: 'B', stop_code: '2', stop_name: 'B', stop_lat: 53.32, stop_lon: -6.30 },
	{ stop_id: 'C', stop_code: '3', stop_name: 'C', stop_lat: 53.34, stop_lon: -6.30 },
	{ stop_id: 'C2', stop_code: '4', stop_name: "C'", stop_lat: 53.34, stop_lon: -6.297 },
	{ stop_id: 'D', stop_code: '5', stop_name: 'D', stop_lat: 53.36, stop_lon: -6.297 },
];

const TRIPS = [
	{ trip_id: 'r1a', route_id: 'R1', times: [['A', '08:00'], ['B', '08:10'], ['C', '08:20']] },
	{ trip_id: 'r1b', route_id: 'R1', times: [['A', '08:30'], ['B', '08:40'], ['C', '08:50']] },
	{ trip_id: 'r2a', route_id: 'R2', times: [['C2', '08:25'], ['D', '08:35']] },
	{ trip_id: 'r2b', route_id: 'R2', times: [['C2', '08:40'], ['D', '08:50']] },
	{ trip_id: 'r3a', route_id: 'R3', times: [['A', '08:05'], ['D', '08:50']] },
];

function toySource(): TimetableSource {
	return {
		label: 'toy',
		async stops() { return STOPS; },
		async routes() {
			return ['R1', 'R2', 'R3'].map((r) => ({ route_id: r, route_short_name: r, route_long_name: '', route_type: 3, agency_id: 'X' }));
		},
		async activeServiceIds(date) { return new Set(date === DATE ? ['S'] : []); },
		async trips(ids) {
			return ids.has('S')
				? TRIPS.map((t) => ({ trip_id: t.trip_id, route_id: t.route_id, service_id: 'S', trip_headsign: '', shape_id: '', direction_id: 0 }))
				: [];
		},
		async stopTimes(tripIds, onRow: (r: StopTimeRow) => void) {
			for (const t of TRIPS) {
				if (!tripIds.has(t.trip_id)) continue;
				t.times.forEach(([stop, time], i) => onRow({
					trip_id: t.trip_id, stop_id: stop, stop_sequence: i + 1, arrival_time: T(time), departure_time: T(time),
				}));
			}
		},
		async shape() { return []; },
	};
}

const zero = epochZeroOf(DATE, 'Europe/Dublin');
const at = (hhmm: string) => zero + +hhmm.slice(0, 2) * 3600 + +hhmm.slice(3) * 60;
// 50 m from A, and 50 m from D.
const FROM = { lat: 53.30045, lon: -6.30 };
const TO = { lat: 53.36045, lon: -6.297 };

const routesOf = (legs: { route?: string }[]) => legs.filter((l) => l.route).map((l) => l.route).join('+');

test('finds the change via a walking transfer and the slower direct bus', async () => {
	const res = await new Planner(toySource()).plan({ from: FROM, to: TO, departAt: at('07:55') });
	const summary = res.itineraries.map((i) => routesOf(i.legs));
	assert.ok(summary.includes('R1+R2'), summary.join(', '));
	assert.ok(summary.includes('R3'), summary.join(', '));
	const viaChange = res.itineraries.find((i) => routesOf(i.legs) === 'R1+R2')!;
	// Walk to A, R1 to C, walk to C', R2 to D, walk to the destination.
	assert.deepEqual(viaChange.legs.map((l) => l.mode), ['walk', 'bus', 'walk', 'bus', 'walk']);
	assert.equal(viaChange.legs[1].trip_id, 'r1a');
	assert.equal(viaChange.legs[3].trip_id, 'r2a');
	assert.equal(viaChange.transfers, 1);
	// Leaves the origin just in time for the 08:00, not at 07:55.
	assert.ok(viaChange.departure > at('07:58') && viaChange.departure < at('08:00'));
});

test('a realtime delay that breaks the connection moves it to the next bus', async () => {
	const feed = transit_realtime.FeedMessage.create({
		header: { gtfsRealtimeVersion: '2.0', timestamp: at('07:50') },
		entity: [{
			id: '1',
			tripUpdate: { trip: { tripId: 'r1a' }, stopTimeUpdate: [{ stopSequence: 1, departure: { delay: 300 }, arrival: { delay: 300 } }] },
		}],
	});
	const res = await new Planner(toySource()).plan({ from: FROM, to: TO, departAt: at('07:55'), feed });
	// R1 now reaches C at 08:25: too late for the 08:25 from C', so nothing can use r2a. The change
	// via the 08:40 ties with R3 at 08:50 with one more change, so it may be filtered out.
	for (const i of res.itineraries) assert.ok(!i.legs.some((l) => l.trip_id === 'r2a'), routesOf(i.legs));
	const change = res.itineraries.find((i) => routesOf(i.legs) === 'R1+R2');
	if (change) {
		assert.equal(change.legs.find((l) => l.route === 'R2')!.trip_id, 'r2b');
		assert.equal(change.legs[1].delay_s, 300);
		assert.equal(change.legs[1].realtime, true);
	}
	assert.equal(res.realtime.used, true);
	assert.equal(res.realtime.updated_trips, 1);
});

test('a cancelled trip is never used', async () => {
	const feed = transit_realtime.FeedMessage.create({
		header: { gtfsRealtimeVersion: '2.0', timestamp: at('07:50') },
		entity: [{ id: '1', tripUpdate: { trip: { tripId: 'r3a', scheduleRelationship: 3 } } }],
	});
	const res = await new Planner(toySource()).plan({ from: FROM, to: TO, departAt: at('07:55'), feed });
	for (const i of res.itineraries) assert.ok(!i.legs.some((l) => l.trip_id === 'r3a'));
});

test('nothing leaves after the last bus', async () => {
	const res = await new Planner(toySource()).plan({ from: FROM, to: TO, departAt: at('09:00') });
	assert.equal(res.itineraries.filter((i) => i.legs.some((l) => l.mode !== 'walk')).length, 0);
});
