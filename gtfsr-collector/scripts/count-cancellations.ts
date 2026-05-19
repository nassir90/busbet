/**
 * Count trip cancellations in archived GTFS-R TripUpdate feeds.
 *
 * Scans all .pb.gz files in data/feeds/ for entities where
 * trip_update.trip.schedule_relationship === 3 (CANCELED),
 * filtered to specific routes.
 *
 * Usage:
 *   npx tsx scripts/count-cancellations.ts
 */

import { readFileSync, readdirSync } from 'fs';
import { gunzipSync } from 'zlib';
import { createClient } from '@libsql/client';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const DATA_DIR     = process.env.DATA_DIR ?? './data';
const DATABASE_URL = process.env.DATABASE_URL ?? `${DATA_DIR}/busbet.db`;
const FEED_DIR     = `${DATA_DIR}/feeds`;

const TARGET_ROUTES = (process.argv.slice(2).length > 0) ? process.argv.slice(2) : ['C2', 'C1', 'P29', 'L53', 'L51'];

// GTFS-R schedule_relationship enum value for CANCELED
const CANCELED = 3;

// --- Look up route_id suffixes from static GTFS DB ---
const db = createClient({ url: `file:${DATABASE_URL}` });

const routeRes = await db.execute({
	sql: `SELECT route_id, route_short_name FROM routes WHERE route_short_name IN (${TARGET_ROUTES.map(() => '?').join(',')})`,
	args: TARGET_ROUTES,
});
db.close();

if (routeRes.rows.length === 0) {
	console.error('No matching routes found in DB');
	process.exit(1);
}

// Build a map of route_id suffix -> route_short_name
const routeSuffixToName = new Map<string, string>();
for (const row of routeRes.rows) {
	const routeId = row.route_id as string;
	const shortName = row.route_short_name as string;
	const suffix = routeId.split('_').slice(1).join('_');
	routeSuffixToName.set(suffix, shortName);
	console.log(`  Route ${shortName}: DB route_id=${routeId}, matching suffix=${suffix}`);
}

function matchRoute(feedRouteId: string): string | null {
	for (const [suffix, name] of routeSuffixToName) {
		if (feedRouteId.includes(suffix)) return name;
	}
	return null;
}

// --- Scan feeds ---
const feedFiles = readdirSync(FEED_DIR).filter(f => f.endsWith('.pb.gz')).sort();
console.log(`\nScanning ${feedFiles.length} feed archives for cancellations on routes ${TARGET_ROUTES.join(', ')}...\n`);

type Cancellation = {
	tripId: string;
	routeName: string;
	feedRouteId: string;
	feedTimestamp: number;
	fileName: string;
};

const cancellations: Cancellation[] = [];
// Track unique (tripId, date) pairs to avoid counting the same cancellation from multiple feed snapshots
const seen = new Set<string>();

let filesProcessed = 0;
let entitiesScanned = 0;
let totalCancelled = 0;

for (const file of feedFiles) {
	filesProcessed++;
	if (filesProcessed % 500 === 0) {
		process.stdout.write(`  ...processed ${filesProcessed}/${feedFiles.length} files\r`);
	}

	try {
		const raw = gunzipSync(readFileSync(`${FEED_DIR}/${file}`));
		const feed = FeedMessage.decode(new Uint8Array(raw));
		const feedTs = feed.header?.timestamp?.toNumber?.() ?? 0;

		for (const entity of feed.entity) {
			entitiesScanned++;
			const tu = entity.tripUpdate;
			if (!tu?.trip) continue;

			// Check schedule_relationship at the trip level
			const schedRel = tu.trip.scheduleRelationship;
			if (schedRel !== CANCELED) continue;

			totalCancelled++;

			const feedRouteId = tu.trip.routeId ?? '';
			const routeName = matchRoute(feedRouteId);
			if (!routeName) continue;

			const tripId = tu.trip.tripId ?? 'unknown';
			const date = new Date(feedTs * 1000).toISOString().split('T')[0];
			const key = `${tripId}:${date}`;

			if (seen.has(key)) continue;
			seen.add(key);

			cancellations.push({
				tripId,
				routeName,
				feedRouteId,
				feedTimestamp: feedTs,
				fileName: file,
			});
		}
	} catch (err) {
		// Skip corrupt files
		console.error(`  Warning: failed to decode ${file}: ${err}`);
	}
}

console.log(`\nDone. Processed ${filesProcessed} files, ${entitiesScanned} entities.`);
console.log(`Total CANCELED entities across all routes: ${totalCancelled}`);
console.log(`Unique cancellations on target routes: ${cancellations.length}\n`);

if (cancellations.length === 0) {
	console.log('No cancellations found for routes ' + TARGET_ROUTES.join(', ') + '.');
	process.exit(0);
}

// --- Group by route ---
const byRoute = new Map<string, Cancellation[]>();
for (const c of cancellations) {
	let arr = byRoute.get(c.routeName);
	if (!arr) { arr = []; byRoute.set(c.routeName, arr); }
	arr.push(c);
}

console.log('=' .repeat(80));
console.log('CANCELLATIONS PER ROUTE');
console.log('=' .repeat(80));

for (const route of TARGET_ROUTES) {
	const items = byRoute.get(route) ?? [];
	console.log(`\n  Route ${route}: ${items.length} cancellation(s)`);
}

// --- Group by date within each route ---
console.log('\n' + '=' .repeat(80));
console.log('CANCELLATIONS BY DATE AND ROUTE');
console.log('=' .repeat(80));

for (const route of TARGET_ROUTES) {
	const items = byRoute.get(route) ?? [];
	if (items.length === 0) {
		console.log(`\n--- Route ${route}: no cancellations ---`);
		continue;
	}

	// Group by date
	const byDate = new Map<string, Cancellation[]>();
	for (const c of items) {
		const date = new Date(c.feedTimestamp * 1000).toISOString().split('T')[0];
		let arr = byDate.get(date);
		if (!arr) { arr = []; byDate.set(date, arr); }
		arr.push(c);
	}

	const sortedDates = [...byDate.keys()].sort();
	console.log(`\n--- Route ${route} (${items.length} total) ---`);

	for (const date of sortedDates) {
		const dayItems = byDate.get(date)!;
		console.log(`\n  ${date} (${dayItems.length} cancellation${dayItems.length > 1 ? 's' : ''}):`);
		for (const c of dayItems) {
			const time = new Date(c.feedTimestamp * 1000).toISOString().replace('T', ' ').substring(0, 19);
			console.log(`    Trip: ${c.tripId}  |  Feed timestamp: ${time} UTC  |  Feed route_id: ${c.feedRouteId}`);
		}
	}
}

console.log('\n' + '=' .repeat(80));
