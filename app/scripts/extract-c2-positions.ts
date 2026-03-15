/**
 * Extracts C2 vehicle positions from archived feeds into a JSON file
 * for the map visualiser.
 *
 * Usage: npx tsx scripts/extract-c2-positions.ts
 * Output: data/c2-positions.json
 */

import { readFileSync, readdirSync, writeFileSync } from 'fs';
import { gunzipSync } from 'zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const C2_ROUTE_ID = '5446_123831';
const VEHICLE_DIR = './data/vehicles';
const FEED_DIR = './data/feeds';
const OUTPUT = './data/c2-positions.json';

type Position = {
	ts: number;          // unix seconds
	tripId: string;
	directionId: number | null;
	vehicleId: string | null;
	lat: number;
	lon: number;
	delaySec: number | null; // from matching TripUpdate, if available
};

// Load all vehicle archive files sorted by time
const vFiles = readdirSync(VEHICLE_DIR).filter(f => f.endsWith('.pb.gz')).sort();
const tFiles = readdirSync(FEED_DIR).filter(f => f.endsWith('.pb.gz')).sort();

console.log(`Vehicle archives: ${vFiles.length}`);
console.log(`TripUpdate archives: ${tFiles.length}`);

// Index TripUpdate files by feed timestamp for joining
const tripUpdatesByFeedTs = new Map<number, string>();
for (const f of tFiles) {
	const feedTs = parseInt(f.split('_').pop()!.replace('.pb.gz', ''));
	if (!isNaN(feedTs)) tripUpdatesByFeedTs.set(feedTs, f);
}

const positions: Position[] = [];

for (let i = 0; i < vFiles.length; i++) {
	const vFile = vFiles[i];
	const raw = gunzipSync(readFileSync(`${VEHICLE_DIR}/${vFile}`));
	const feed = FeedMessage.decode(new Uint8Array(raw));
	const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;

	// Try to load the closest TripUpdates feed for delay data
	let delayByTripId: Map<string, number> | null = null;
	if (feedTs !== null) {
		// Find the TripUpdates file with the closest timestamp
		const tFile = tripUpdatesByFeedTs.get(feedTs);
		if (tFile) {
			const tRaw = gunzipSync(readFileSync(`${FEED_DIR}/${tFile}`));
			const tFeed = FeedMessage.decode(new Uint8Array(tRaw));
			delayByTripId = new Map();
			for (const e of tFeed.entity) {
				const tu = e.tripUpdate;
				if (!tu?.trip?.tripId) continue;
				// Use the first stopTimeUpdate's delay as representative
				const d = tu.stopTimeUpdate?.[0]?.arrival?.delay
					?? tu.stopTimeUpdate?.[0]?.departure?.delay
					?? null;
				if (d !== null) delayByTripId.set(tu.trip.tripId, d as number);
			}
		}
	}

	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.trip?.routeId || v.trip.routeId !== C2_ROUTE_ID) continue;
		if (!v.position) continue;

		const tripId = v.trip.tripId ?? '';
		positions.push({
			ts: v.timestamp ? parseInt(v.timestamp as string) : (feedTs ?? 0),
			tripId,
			directionId: v.trip.directionId ?? null,
			vehicleId: v.vehicle?.id ?? null,
			lat: v.position.latitude,
			lon: v.position.longitude,
			delaySec: delayByTripId?.get(tripId) ?? null,
		});
	}

	if ((i + 1) % 20 === 0 || i === vFiles.length - 1) {
		console.log(`  processed ${i + 1}/${vFiles.length} files, ${positions.length} C2 positions`);
	}
}

// Sort by timestamp
positions.sort((a, b) => a.ts - b.ts);

// Get unique timestamps for the timeline
const timestamps = [...new Set(positions.map(p => p.ts))].sort((a, b) => a - b);

const output = { timestamps, positions };
writeFileSync(OUTPUT, JSON.stringify(output));
console.log(`\nWrote ${positions.length} positions across ${timestamps.length} snapshots to ${OUTPUT}`);
