/**
 * Extracts vehicle positions from archived feeds for given route short names.
 *
 * Usage:
 *   npx tsx scripts/extract-positions.ts C2 C1 L53 L51 P29
 *   npx tsx scripts/extract-positions.ts --list          # list all routes in data
 *
 * Requires data/busbet.db (static GTFS) for route name → trip_id / route_id lookup.
 * Output: ../gtfsr-historical-view/static/positions.json (set OUTPUT env var to override)
 */

import { readFileSync, readdirSync, writeFileSync } from 'fs';
import { gunzipSync } from 'zlib';
import { createClient } from '@libsql/client';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const DATA_DIR    = process.env.DATA_DIR ?? './data';
const DATABASE_URL = process.env.DATABASE_URL ?? `${DATA_DIR}/busbet.db`;
const VEHICLE_DIR = `${DATA_DIR}/vehicles`;
const FEED_DIR    = `${DATA_DIR}/feeds`;
const OUTPUT      = process.env.OUTPUT ?? '../gtfsr-historical-view/static/positions.json';

const args = process.argv.slice(2);

// --list mode: scan one feed and print all route IDs, resolved to short names where possible
if (args.includes('--list')) {
	const files = readdirSync(VEHICLE_DIR).filter(f => f.endsWith('.pb.gz')).sort();
	if (files.length === 0) { console.log('No vehicle archives found.'); process.exit(0); }
	const raw = gunzipSync(readFileSync(`${VEHICLE_DIR}/${files[files.length - 1]}`));
	const feed = FeedMessage.decode(new Uint8Array(raw));
	const routes = new Map<string, number>();
	for (const e of feed.entity) {
		const rid = e.vehicle?.trip?.routeId;
		if (rid) routes.set(rid, (routes.get(rid) ?? 0) + 1);
	}

	// Try to resolve route_id → short name from DB
	const db = createClient({ url: `file:${DATABASE_URL}` });
	const idToName = new Map<string, string>();
	const res = await db.execute('SELECT route_id, route_short_name FROM routes');
	for (const row of res.rows) idToName.set(row.route_id as string, row.route_short_name as string);
	db.close();

	// Also build a suffix map (feed may use different operator prefix)
	const suffixToName = new Map<string, string>();
	for (const [rid, name] of idToName) {
		const suffix = rid.split('_').slice(1).join('_');
		if (suffix) suffixToName.set(suffix, name);
	}

	const sorted = [...routes.entries()].sort((a, b) => b[1] - a[1]);
	console.log(`${sorted.length} routes in latest feed:\n`);
	for (const [id, count] of sorted) {
		const suffix = id.split('_').slice(1).join('_');
		const name = idToName.get(id) ?? suffixToName.get(suffix) ?? '';
		console.log(`  ${name.padEnd(8)} ${id.padEnd(20)} ${count} vehicles`);
	}
	process.exit(0);
}

if (args.length === 0) {
	console.error('Usage: npx tsx scripts/extract-positions.ts C2 C1 L53 L51');
	console.error('       npx tsx scripts/extract-positions.ts --list');
	process.exit(1);
}

const routeNames = args;

// Build trip_id → route_name lookup from static DB
const db = createClient({ url: `file:${DATABASE_URL}` });
const routeByTripId = new Map<string, string>();

for (const name of routeNames) {
	const r = await db.execute({
		sql: `SELECT t.trip_id FROM trips t
		      JOIN routes r ON r.route_id = t.route_id
		      WHERE r.route_short_name = ?`,
		args: [name]
	});
	for (const row of r.rows) routeByTripId.set(row.trip_id as string, name);
	console.log(`${name}: ${r.rows.length} trip_ids`);
}

// Also match by routeId in the feed (some vehicles may use route_id directly)
// Build both exact route_id match and suffix-based match (feed operator prefix may differ)
const routeIdToName = new Map<string, string>();
const routeSuffixToName = new Map<string, string>();

for (const name of routeNames) {
	const r = await db.execute({
		sql: 'SELECT route_id FROM routes WHERE route_short_name = ?',
		args: [name]
	});
	for (const row of r.rows) {
		const rid = row.route_id as string;
		routeIdToName.set(rid, name);
		const suffix = rid.split('_').slice(1).join('_');
		if (suffix) routeSuffixToName.set(suffix, name);
	}
}
db.close();

console.log(`\nTotal trip_ids to match: ${routeByTripId.size}`);
console.log(`Route IDs to match: ${[...routeIdToName.entries()].map(([id, n]) => `${n}=${id}`).join(', ')}`);

type Position = {
	ts: number;
	tripId: string;
	route: string;
	directionId: number | null;
	vehicleId: string | null;
	lat: number;
	lon: number;
	delaySec: number | null;
};

const vFiles = readdirSync(VEHICLE_DIR).filter(f => f.endsWith('.pb.gz')).sort();
const tFiles = readdirSync(FEED_DIR).filter(f => f.endsWith('.pb.gz')).sort();
console.log(`\nVehicle archives: ${vFiles.length}`);
console.log(`TripUpdate archives: ${tFiles.length}`);

// Index TripUpdate files by feed timestamp
const tripFileByTs = new Map<number, string>();
for (const f of tFiles) {
	const feedTs = parseInt(f.split('_').pop()!.replace('.pb.gz', ''));
	if (!isNaN(feedTs)) tripFileByTs.set(feedTs, f);
}

const positions: Position[] = [];

for (let i = 0; i < vFiles.length; i++) {
	const raw = gunzipSync(readFileSync(`${VEHICLE_DIR}/${vFiles[i]}`));
	const feed = FeedMessage.decode(new Uint8Array(raw));
	const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;

	// Load matching TripUpdates for delay data
	let delayByTripId: Map<string, number> | null = null;
	if (feedTs !== null) {
		const tFile = tripFileByTs.get(feedTs);
		if (tFile) {
			const tRaw = gunzipSync(readFileSync(`${FEED_DIR}/${tFile}`));
			const tFeed = FeedMessage.decode(new Uint8Array(tRaw));
			delayByTripId = new Map();
			for (const e of tFeed.entity) {
				const tu = e.tripUpdate;
				if (!tu?.trip?.tripId) continue;
				const d = tu.stopTimeUpdate?.[0]?.arrival?.delay
					?? tu.stopTimeUpdate?.[0]?.departure?.delay ?? null;
				if (d !== null) delayByTripId.set(tu.trip.tripId, d as number);
			}
		}
	}

	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.position) continue;

		// Skip zero/invalid coordinates (GPS unavailable, end of service)
		if (v.position.latitude === 0 && v.position.longitude === 0) continue;
		if (Math.abs(v.position.latitude) < 1) continue;

		const tripId = v.trip?.tripId ?? '';
		const feedRouteId = v.trip?.routeId ?? '';
		const feedSuffix = feedRouteId.split('_').slice(1).join('_');

		const routeName = routeByTripId.get(tripId)
			?? routeIdToName.get(feedRouteId)
			?? routeSuffixToName.get(feedSuffix)
			?? null;
		if (!routeName) continue;

		positions.push({
			ts: v.timestamp ? parseInt(v.timestamp as string) : (feedTs ?? 0),
			tripId,
			route: routeName,
			directionId: v.trip?.directionId ?? null,
			vehicleId: v.vehicle?.id ?? null,
			lat: v.position.latitude,
			lon: v.position.longitude,
			delaySec: delayByTripId?.get(tripId) ?? null,
		});
	}

	if ((i + 1) % 20 === 0 || i === vFiles.length - 1) {
		console.log(`  ${i + 1}/${vFiles.length} files — ${positions.length} positions`);
	}
}

positions.sort((a, b) => a.ts - b.ts);

// Remove large jumps within a trip (vehicle reused on different route, GPS glitch)
const byTrip = new Map<string, Position[]>();
for (const p of positions) {
	let arr = byTrip.get(p.tripId);
	if (!arr) { arr = []; byTrip.set(p.tripId, arr); }
	arr.push(p);
}

const cleaned: Position[] = [];
let removed = 0;
for (const [, pts] of byTrip) {
	pts.sort((a, b) => a.ts - b.ts);
	cleaned.push(pts[0]);
	for (let i = 1; i < pts.length; i++) {
		const dlat = Math.abs(pts[i].lat - pts[i - 1].lat);
		const dlon = Math.abs(pts[i].lon - pts[i - 1].lon);
		// >0.05 degrees (~5km) in one jump = anomaly
		if (dlat > 0.05 || dlon > 0.05) {
			removed++;
			continue;
		}
		cleaned.push(pts[i]);
	}
}
if (removed > 0) console.log(`Removed ${removed} anomalous jumps`);

const timestamps = [...new Set(cleaned.map(p => p.ts))].sort((a, b) => a - b);

writeFileSync(OUTPUT, JSON.stringify({ timestamps, positions: cleaned }));
console.log(`\nWrote ${cleaned.length} positions across ${timestamps.length} snapshots to ${OUTPUT}`);
