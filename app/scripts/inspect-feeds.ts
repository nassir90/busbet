import { readFileSync, readdirSync } from 'fs';
import { gunzipSync } from 'zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

function latest(dir: string): string {
	const files = readdirSync(dir).sort();
	return dir + '/' + files[files.length - 1];
}

function load(path: string) {
	return FeedMessage.decode(new Uint8Array(gunzipSync(readFileSync(path))));
}

const vFeed = load(latest('data/vehicles'));
const tFeed = load(latest('data/feeds'));

// --- VehiclePositions structure ---
console.log('=== VehiclePositions ===');
console.log(`Entities: ${vFeed.entity.length}\n`);
for (const e of vFeed.entity.slice(0, 3)) {
	console.log(JSON.stringify(e.vehicle, null, 2));
	console.log('');
}

// --- TripUpdates structure ---
console.log('=== TripUpdates (first entity) ===');
const tu = tFeed.entity[0];
console.log(JSON.stringify({
	id: tu.id,
	trip: tu.tripUpdate?.trip,
	vehicle: tu.tripUpdate?.vehicle,
	stopTimeUpdateCount: tu.tripUpdate?.stopTimeUpdate?.length,
	firstStop: tu.tripUpdate?.stopTimeUpdate?.[0],
}, null, 2));

// --- Join on trip_id ---
const vTripIds = new Set(vFeed.entity.map(e => e.vehicle?.trip?.tripId).filter(Boolean));
const tTripIds = new Set(tFeed.entity.map(e => e.tripUpdate?.trip?.tripId).filter(Boolean));
let overlap = 0;
for (const id of vTripIds) { if (tTripIds.has(id as string)) overlap++; }

console.log('\n=== Overlap ===');
console.log(`VehiclePositions trip_ids: ${vTripIds.size}`);
console.log(`TripUpdates trip_ids:      ${tTripIds.size}`);
console.log(`In both:                   ${overlap}`);
