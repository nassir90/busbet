/**
 * Stream-processes archived GTFS-R feeds to build a detailed timeline
 * for a specific trip. Cross-references GPS positions (closest approach
 * to each stop) with retrospective TripUpdate delays.
 *
 * Usage:
 *   npx tsx scripts/backtest-trip.ts <route> <trip_suffix> [--dir 0|1] [--from HH:MM] [--to HH:MM]
 *
 * Examples:
 *   npx tsx scripts/backtest-trip.ts P29 27398
 *   npx tsx scripts/backtest-trip.ts P29 27398 --dir 1 --from 07:00 --to 09:00
 *   npx tsx scripts/backtest-trip.ts C2 12345 --dir 0
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
const VEHICLE_DIR  = `${DATA_DIR}/vehicles`;
const FEED_DIR     = `${DATA_DIR}/feeds`;

// --- Parse args ---
const args = process.argv.slice(2);
const routeName  = args[0];
const tripSuffix = args[1];

function argVal(flag: string): string | undefined {
	const idx = args.indexOf(flag);
	return idx >= 0 && idx + 1 < args.length ? args[idx + 1] : undefined;
}

const dirFilter = argVal('--dir') != null ? parseInt(argVal('--dir')!) : null;
const fromTime  = argVal('--from') ?? null;  // HH:MM
const toTime    = argVal('--to') ?? null;    // HH:MM

if (!routeName || !tripSuffix) {
	console.error('Usage: npx tsx scripts/backtest-trip.ts <route> <trip_suffix> [--dir 0|1] [--from HH:MM] [--to HH:MM]');
	process.exit(1);
}

// --- Load schedule + route info ---
const db = createClient({ url: `file:${DATABASE_URL}` });

// Get route_id so we can filter feeds by route (avoid cross-route trip suffix collisions)
const routeRes = await db.execute({
	sql: 'SELECT route_id FROM routes WHERE route_short_name = ?',
	args: [routeName]
});
if (routeRes.rows.length === 0) {
	console.error(`Route ${routeName} not found in DB`);
	process.exit(1);
}
const dbRouteId = routeRes.rows[0].route_id as string;
// Extract suffix for matching against feed (operator prefix may differ)
const routeIdSuffix = dbRouteId.split('_').slice(1).join('_');

// Look up trip + direction from DB
const tripQuery = dirFilter !== null
	? { sql: `SELECT t.trip_id, t.direction_id FROM trips t
	          JOIN routes r ON r.route_id = t.route_id
	          WHERE r.route_short_name = ? AND t.trip_id LIKE ? AND t.direction_id = ?`,
	    args: [routeName, `%_${tripSuffix}`, dirFilter] }
	: { sql: `SELECT t.trip_id, t.direction_id FROM trips t
	          JOIN routes r ON r.route_id = t.route_id
	          WHERE r.route_short_name = ? AND t.trip_id LIKE ?`,
	    args: [routeName, `%_${tripSuffix}`] };

const tripRes = await db.execute(tripQuery);
if (tripRes.rows.length === 0) {
	console.error(`No trip found for route ${routeName} with suffix ${tripSuffix}${dirFilter !== null ? ` dir=${dirFilter}` : ''}`);
	process.exit(1);
}
const dbTripId = tripRes.rows[0].trip_id as string;
const tripDirection = tripRes.rows[0].direction_id as number;
const dirLabel = tripDirection === 0 ? 'Outbound' : tripDirection === 1 ? 'Inbound' : `dir=${tripDirection}`;

const stRes = await db.execute({
	sql: `SELECT st.stop_sequence, s.stop_id, s.stop_name, st.arrival_time, st.departure_time,
	             s.stop_lat, s.stop_lon
	      FROM stop_times st
	      JOIN stops s ON s.stop_id = st.stop_id
	      WHERE st.trip_id = ?
	      ORDER BY st.stop_sequence`,
	args: [dbTripId]
});
db.close();

type ScheduledStop = {
	seq: number;
	stopId: string;
	name: string;
	arrival: string;
	departure: string;
	lat: number;
	lon: number;
};
const schedule: ScheduledStop[] = stRes.rows.map(r => ({
	seq: r.stop_sequence as number,
	stopId: r.stop_id as string,
	name: r.stop_name as string,
	arrival: r.arrival_time as string,
	departure: r.departure_time as string,
	lat: r.stop_lat as number,
	lon: r.stop_lon as number,
}));

console.log(`Trip: ${dbTripId} (${routeName}), route_id suffix: ${routeIdSuffix}, direction: ${dirLabel}`);
if (fromTime || toTime) console.log(`Time window: ${fromTime ?? '—'} → ${toTime ?? '—'}`);
console.log(`Schedule: ${schedule.length} stops`);
console.log(`  ${schedule[0].name} (${schedule[0].departure}) → ${schedule[schedule.length - 1].name} (${schedule[schedule.length - 1].arrival})\n`);

// --- Helpers ---
function matchesTripSuffix(feedTripId: string): boolean {
	return feedTripId.endsWith(`_${tripSuffix}`);
}

function matchesRoute(feedRouteId: string): boolean {
	return feedRouteId.includes(routeIdSuffix);
}

function matchesDirection(feedDirId: number | null | undefined): boolean {
	if (dirFilter === null) return true;
	return feedDirId === tripDirection;
}

// Time window filter (applied to feed timestamps)
const fromTs = fromTime ? (() => {
	const [h, m] = fromTime.split(':').map(Number);
	return h * 3600 + m * 60;
})() : null;
const toTs = toTime ? (() => {
	const [h, m] = toTime.split(':').map(Number);
	return h * 3600 + m * 60;
})() : null;

// Convert unix timestamp to UTC seconds-of-day
function utcSecOfDay(unixTs: number): number {
	const d = new Date(unixTs * 1000);
	return d.getUTCHours() * 3600 + d.getUTCMinutes() * 60 + d.getUTCSeconds();
}

function inTimeWindow(unixTs: number): boolean {
	if (fromTs === null && toTs === null) return true;
	const secOfDay = utcSecOfDay(unixTs);
	if (fromTs !== null && secOfDay < fromTs) return false;
	if (toTs !== null && secOfDay > toTs) return false;
	return true;
}

// Format unix timestamp as HH:MM:SS UTC
function unixToTimeUTC(unixTs: number): string {
	const d = new Date(unixTs * 1000);
	return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}:${String(d.getUTCSeconds()).padStart(2, '0')}`;
}

function timeToSeconds(t: string): number {
	const [h, m, s] = t.split(':').map(Number);
	return h * 3600 + m * 60 + (s || 0);
}

function secondsToTime(sec: number): string {
	const h = Math.floor(sec / 3600);
	const m = Math.floor((sec % 3600) / 60);
	const s = Math.round(sec % 60);
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

function formatDelay(sec: number): string {
	const abs = Math.abs(Math.round(sec));
	const m = Math.floor(abs / 60);
	const s = abs % 60;
	const sign = sec >= 0 ? '+' : '-';
	if (m === 0 && s < 30) return 'on time';
	if (s === 0) return `${sign}${m}m`;
	return `${sign}${m}m${s}s`;
}

// Haversine distance in meters
function distanceM(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const R = 6371000;
	const dLat = (lat2 - lat1) * Math.PI / 180;
	const dLon = (lon2 - lon1) * Math.PI / 180;
	const a = Math.sin(dLat / 2) ** 2 +
		Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
		Math.sin(dLon / 2) ** 2;
	return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// --- Collect GPS positions (filtered by route) ---
type GpsPoint = { ts: number; lat: number; lon: number; vehicleId?: string };

const vFiles = readdirSync(VEHICLE_DIR).filter(f => f.endsWith('.pb.gz')).sort();
const tFiles = readdirSync(FEED_DIR).filter(f => f.endsWith('.pb.gz')).sort();

console.log(`Processing ${vFiles.length} vehicle archives...`);
const gpsTrail: GpsPoint[] = [];

for (let i = 0; i < vFiles.length; i++) {
	const raw = gunzipSync(readFileSync(`${VEHICLE_DIR}/${vFiles[i]}`));
	const feed = FeedMessage.decode(new Uint8Array(raw));
	const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;

	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.trip?.tripId || !matchesTripSuffix(v.trip.tripId)) continue;
		// Filter by route + direction to avoid cross-route collisions
		if (!v.trip.routeId || !matchesRoute(v.trip.routeId)) continue;
		if (!matchesDirection(v.trip.directionId)) continue;
		if (!v.position || (v.position.latitude === 0 && v.position.longitude === 0)) continue;
		if (Math.abs(v.position.latitude) < 1) continue;

		const ts = v.timestamp ? parseInt(v.timestamp as string) : (feedTs ?? 0);
		if (!inTimeWindow(ts)) continue;

		gpsTrail.push({
			ts,
			lat: v.position.latitude,
			lon: v.position.longitude,
			vehicleId: v.vehicle?.id ?? undefined,
		});
	}
}

gpsTrail.sort((a, b) => a.ts - b.ts);

// Deduplicate by timestamp
const seen = new Set<number>();
const trail: GpsPoint[] = [];
for (const p of gpsTrail) {
	if (!seen.has(p.ts)) { seen.add(p.ts); trail.push(p); }
}

const vehicleId = trail.find(p => p.vehicleId)?.vehicleId ?? 'unknown';
if (trail.length > 0) {
	const firstTime = unixToTimeUTC(trail[0].ts);
	const lastTime = unixToTimeUTC(trail[trail.length - 1].ts);
	console.log(`GPS points: ${trail.length} unique, vehicle #${vehicleId}`);
	console.log(`GPS coverage: ${firstTime} → ${lastTime}`);
} else {
	console.log(`GPS points: 0 (no VehiclePosition data for this route+trip)`);
}

// --- Collect TripUpdate delay reports (filtered by route) ---
// The feed reports retrospective per-stop delays. We collect ALL observations
// per stop across all feed snapshots, then use the latest one.
type FeedStopObs = {
	feedTs: number;
	stopSeq: number;
	stopId: string;
	arrivalDelay: number | null;
	departureDelay: number | null;
};

console.log(`Processing ${tFiles.length} TripUpdate archives...`);
const feedObs: FeedStopObs[] = [];

for (let i = 0; i < tFiles.length; i++) {
	const raw = gunzipSync(readFileSync(`${FEED_DIR}/${tFiles[i]}`));
	const feed = FeedMessage.decode(new Uint8Array(raw));
	const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;
	if (feedTs === null) continue;

	for (const entity of feed.entity) {
		const tu = entity.tripUpdate;
		if (!tu?.trip?.tripId || !matchesTripSuffix(tu.trip.tripId)) continue;
		if (!tu.trip.routeId || !matchesRoute(tu.trip.routeId)) continue;
		if (!matchesDirection(tu.trip.directionId)) continue;

		for (const stu of (tu.stopTimeUpdate ?? [])) {
			feedObs.push({
				feedTs,
				stopSeq: (stu.stopSequence ?? 0) as number,
				stopId: (stu.stopId ?? '') as string,
				arrivalDelay: stu.arrival?.delay != null ? stu.arrival.delay as number : null,
				departureDelay: stu.departure?.delay != null ? stu.departure.delay as number : null,
			});
		}
	}
}

console.log(`TripUpdate observations: ${feedObs.length}\n`);

// --- Reference date for schedule → unix conversion ---
// Use midnight of the date when the trip runs. Prefer GPS data date, else feed data date.
let refTs = trail.length > 0 ? trail[0].ts : (feedObs.length > 0 ? feedObs[0].feedTs : 0);
if (refTs === 0) { console.log('No data found.'); process.exit(0); }
// Use UTC midnight — GTFS schedule times are local but NTA feeds are on UTC+0 (Ireland)
// and the Eire timezone confuses Node.js into a +1h offset.
const refDate = new Date(refTs * 1000);
const midnightTs = Date.UTC(refDate.getUTCFullYear(), refDate.getUTCMonth(), refDate.getUTCDate()) / 1000;

// --- Compute per-stop results ---
type StopResult = {
	stop: ScheduledStop;
	scheduledTs: number;
	// GPS-derived
	gpsDist: number | null;     // meters from stop at closest approach
	gpsArrTs: number | null;    // unix ts of closest approach
	gpsDelay: number | null;    // gpsArrTs - scheduledTs
	// Feed-derived (retrospective)
	feedDelay: number | null;   // seconds, from latest TripUpdate
	feedArrTs: number | null;   // scheduledTs + feedDelay
};

const results: StopResult[] = [];
let searchStart = 0;

for (const stop of schedule) {
	const scheduledTs = midnightTs + timeToSeconds(stop.departure);

	// --- GPS closest approach ---
	let bestDist = Infinity;
	let bestIdx = -1;
	const windowStart = Math.max(0, searchStart - 3);

	for (let i = windowStart; i < trail.length; i++) {
		const d = distanceM(trail[i].lat, trail[i].lon, stop.lat, stop.lon);
		if (d < bestDist) { bestDist = d; bestIdx = i; }
	}

	const THRESHOLD = 150;
	let gpsDist: number | null = null;
	let gpsArrTs: number | null = null;
	let gpsDelay: number | null = null;

	if (bestIdx >= 0 && bestDist <= THRESHOLD) {
		gpsDist = bestDist;
		gpsArrTs = trail[bestIdx].ts;
		gpsDelay = gpsArrTs - scheduledTs;
		searchStart = bestIdx;
	}

	// --- Feed delay (use latest observation for this stop) ---
	let feedDelay: number | null = null;
	let feedArrTs: number | null = null;

	const forStop = feedObs.filter(o => o.stopSeq === stop.seq || o.stopId === stop.stopId);
	if (forStop.length > 0) {
		// Latest feed snapshot wins (retrospective data is most complete)
		forStop.sort((a, b) => b.feedTs - a.feedTs);
		feedDelay = forStop[0].arrivalDelay ?? forStop[0].departureDelay;
		if (feedDelay !== null) {
			feedArrTs = scheduledTs + feedDelay;
		}
	}

	results.push({ stop, scheduledTs, gpsDist, gpsArrTs, gpsDelay, feedDelay, feedArrTs });
}

// --- Report ---
console.log('='.repeat(110));
const dateStr = `${refDate.getUTCDate()}/${refDate.getUTCMonth() + 1}/${refDate.getUTCFullYear()}`;
console.log(`BACKTEST: ${routeName} trip ${tripSuffix}  |  ${dirLabel}  |  Vehicle #${vehicleId}  |  ${dateStr}`);
console.log('='.repeat(110));
console.log('');
console.log(
	`${'#'.padEnd(4)} ${'Stop'.padEnd(24)} ${'Sched'.padEnd(9)} ` +
	`${'Feed Δ'.padEnd(10)} ${'Feed Arr'.padEnd(10)} ` +
	`${'GPS Arr'.padEnd(10)} ${'GPS Δ'.padEnd(10)} ${'Dist'.padEnd(6)} ` +
	`${'Best Arr'.padEnd(10)} ${'Best Δ'.padEnd(10)}`
);
console.log('─'.repeat(110));

for (const r of results) {
	const sched = r.stop.departure.substring(0, 8);

	// Feed columns
	const feedDelayStr = r.feedDelay !== null ? formatDelay(r.feedDelay) : '—';
	const feedArrStr = r.feedArrTs !== null ? secondsToTime(r.feedArrTs - midnightTs) : '—';

	// GPS columns
	const gpsArrStr = r.gpsArrTs !== null ? secondsToTime(r.gpsArrTs - midnightTs) : '—';
	const gpsDelayStr = r.gpsDelay !== null ? formatDelay(r.gpsDelay) : '—';
	const distStr = r.gpsDist !== null ? `${Math.round(r.gpsDist)}m` : '—';

	// Best estimate: prefer feed (retrospective actual), fall back to GPS
	let bestArrStr = '—';
	let bestDelayStr = '—';
	if (r.feedArrTs !== null) {
		bestArrStr = feedArrStr;
		bestDelayStr = feedDelayStr;
	} else if (r.gpsArrTs !== null) {
		bestArrStr = gpsArrStr;
		bestDelayStr = gpsDelayStr;
	}

	console.log(
		`${String(r.stop.seq).padEnd(4)} ${r.stop.name.padEnd(24)} ${sched.padEnd(9)} ` +
		`${feedDelayStr.padEnd(10)} ${feedArrStr.padEnd(10)} ` +
		`${gpsArrStr.padEnd(10)} ${gpsDelayStr.padEnd(10)} ${distStr.padEnd(6)} ` +
		`${bestArrStr.padEnd(10)} ${bestDelayStr.padEnd(10)}`
	);
}

// --- Summary ---
console.log('');
console.log('─'.repeat(110));
console.log('SUMMARY');
console.log('─'.repeat(110));

const withFeed = results.filter(r => r.feedDelay !== null);
const withGps = results.filter(r => r.gpsDelay !== null);
const withAny = results.filter(r => r.feedDelay !== null || r.gpsDelay !== null);

console.log(`  Stops with feed data: ${withFeed.length}/${schedule.length}`);
console.log(`  Stops with GPS data:  ${withGps.length}/${schedule.length}`);
console.log(`  Stops with any data:  ${withAny.length}/${schedule.length}`);

if (withFeed.length > 0) {
	const delays = withFeed.map(r => r.feedDelay!);
	const avg = delays.reduce((a, b) => a + b, 0) / delays.length;
	const max = Math.max(...delays);
	const min = Math.min(...delays);
	console.log(`  Feed avg delay: ${formatDelay(avg)} | max: ${formatDelay(max)} | min: ${formatDelay(min)}`);
}

if (withGps.length > 0 && withFeed.length > 0) {
	// Cross-reference: for stops with both, show correlation
	const both = results.filter(r => r.feedDelay !== null && r.gpsDelay !== null);
	if (both.length > 0) {
		console.log(`\n  Cross-reference (${both.length} stops with both GPS + feed):`);
		for (const r of both) {
			const diff = r.gpsDelay! - r.feedDelay!;
			console.log(`    ${r.stop.name.padEnd(24)} feed=${formatDelay(r.feedDelay!).padEnd(10)} gps=${formatDelay(r.gpsDelay!).padEnd(10)} diff=${formatDelay(diff)}`);
		}
	}
}

// Delay profile using best available data
console.log('');
console.log('─'.repeat(110));
console.log('DELAY PROFILE (best available)');
console.log('─'.repeat(110));

const barMax = 40;
const center = Math.floor(barMax / 2);

for (const r of results) {
	const label = `${String(r.stop.seq).padEnd(3)} ${r.stop.name.substring(0, 22).padEnd(22)}`;
	const delaySec = r.feedDelay ?? r.gpsDelay;
	const source = r.feedDelay !== null ? 'F' : (r.gpsDelay !== null ? 'G' : ' ');

	if (delaySec === null) {
		console.log(`  ${label}  ${'░'.repeat(center)}│${'░'.repeat(center)}  —`);
		continue;
	}

	const offset = Math.round(delaySec / 15); // each char = 15 seconds
	const clamped = Math.max(-center, Math.min(center, offset));

	let bar = '';
	for (let i = -center; i <= center; i++) {
		if (i === 0) bar += '│';
		else if (clamped >= 0 && i > 0 && i <= clamped) bar += '▓';
		else if (clamped < 0 && i < 0 && i >= clamped) bar += '▒';
		else bar += ' ';
	}
	console.log(`  ${label} ${bar}  ${formatDelay(delaySec)} [${source}]`);
}
console.log(`  ${''.padEnd(26)} ${'◄ early'.padStart(center + 3)}│${'late ►'}`);
console.log(`  [F]=feed  [G]=GPS`);

// --- ETA Distribution Analysis ---
// For each stop, collect every distinct ETA prediction from all feed snapshots.
// Each time the predicted delay changes, that's a new data point.
// The final observation is the actual arrival. The spread shows prediction volatility.

console.log('');
console.log('='.repeat(110));
console.log('ETA DISTRIBUTION PER STOP');
console.log('  Each row shows how the predicted ETA for a stop evolved across feed snapshots.');
console.log('  A new data point is recorded each time the predicted delay changes.');
console.log('  The last prediction is treated as the actual arrival.');
console.log('='.repeat(110));
console.log('');

type EtaPrediction = {
	feedTs: number;     // when the prediction was made
	delay: number;      // predicted delay in seconds
	eta: number;        // predicted arrival (scheduledTs + delay)
};

type StopEtaDist = {
	stop: ScheduledStop;
	scheduledTs: number;
	predictions: EtaPrediction[];
	actual: EtaPrediction | null;  // last prediction = actual
};

const etaDists: StopEtaDist[] = [];

for (const stop of schedule) {
	const scheduledTs = midnightTs + timeToSeconds(stop.departure);

	// Get all feed observations for this stop, sorted by time
	const obs = feedObs
		.filter(o => o.stopSeq === stop.seq || o.stopId === stop.stopId)
		.map(o => ({
			feedTs: o.feedTs,
			delay: (o.arrivalDelay ?? o.departureDelay)!,
		}))
		.filter(o => o.delay !== undefined && o.delay !== null)
		.sort((a, b) => a.feedTs - b.feedTs);

	// Deduplicate: only keep when delay changes
	const predictions: EtaPrediction[] = [];
	let lastDelay: number | null = null;
	for (const o of obs) {
		if (o.delay !== lastDelay) {
			predictions.push({
				feedTs: o.feedTs,
				delay: o.delay,
				eta: scheduledTs + o.delay,
			});
			lastDelay = o.delay;
		}
	}

	etaDists.push({
		stop,
		scheduledTs,
		predictions,
		actual: predictions.length > 0 ? predictions[predictions.length - 1] : null,
	});
}

// Header
console.log(
	`${'#'.padEnd(4)} ${'Stop'.padEnd(22)} ${'Sched'.padEnd(9)} ` +
	`${'Preds'.padEnd(6)} ${'Actual'.padEnd(9)} ` +
	`${'Min ETA'.padEnd(10)} ${'Max ETA'.padEnd(10)} ${'Spread'.padEnd(8)} ` +
	`${'σ'.padEnd(6)} ETA Timeline`
);
console.log('─'.repeat(110));

for (const dist of etaDists) {
	const sched = dist.stop.departure.substring(0, 8);
	const nPreds = dist.predictions.length;

	if (nPreds === 0) {
		console.log(`${String(dist.stop.seq).padEnd(4)} ${dist.stop.name.padEnd(22)} ${sched.padEnd(9)} 0     —         —          —          —        —`);
		continue;
	}

	const etas = dist.predictions.map(p => p.eta);
	const delays = dist.predictions.map(p => p.delay);
	const minEta = Math.min(...etas);
	const maxEta = Math.max(...etas);
	const spread = maxEta - minEta;
	const actualEta = dist.actual!.eta;

	// Standard deviation of delay predictions
	const mean = delays.reduce((a, b) => a + b, 0) / delays.length;
	const variance = delays.reduce((a, d) => a + (d - mean) ** 2, 0) / delays.length;
	const stddev = Math.sqrt(variance);

	// Timeline: show each distinct prediction as delay value
	const timeline = dist.predictions
		.map(p => {
			const t = unixToTimeUTC(p.feedTs).substring(0, 5);
			const d = p.delay;
			const sign = d >= 0 ? '+' : '';
			return `${t}:${sign}${d}s`;
		})
		.join(' → ');

	console.log(
		`${String(dist.stop.seq).padEnd(4)} ${dist.stop.name.substring(0, 21).padEnd(22)} ${sched.padEnd(9)} ` +
		`${String(nPreds).padEnd(6)} ${secondsToTime(actualEta - midnightTs).padEnd(9)} ` +
		`${secondsToTime(minEta - midnightTs).padEnd(10)} ${secondsToTime(maxEta - midnightTs).padEnd(10)} ${(spread + 's').padEnd(8)} ` +
		`${(Math.round(stddev) + 's').padEnd(6)} ${timeline}`
	);
}

// Spread visualization
console.log('');
console.log('─'.repeat(110));
console.log('ETA SPREAD PER STOP (range of predicted ETAs)');
console.log('─'.repeat(110));

const spreadBarMax = 50;

for (const dist of etaDists) {
	const label = `${String(dist.stop.seq).padEnd(3)} ${dist.stop.name.substring(0, 20).padEnd(20)}`;
	const nPreds = dist.predictions.length;

	if (nPreds === 0) {
		console.log(`  ${label}  ${'░'.repeat(spreadBarMax)}  no data`);
		continue;
	}

	const etas = dist.predictions.map(p => p.eta);
	const minEta = Math.min(...etas);
	const maxEta = Math.max(...etas);
	const actualEta = dist.actual!.eta;
	const spread = maxEta - minEta;

	if (spread === 0) {
		// Single prediction or all same — show just actual
		const delayStr = formatDelay(dist.actual!.delay);
		console.log(`  ${label}  ${'·'.repeat(Math.floor(spreadBarMax / 2))}◆${'·'.repeat(Math.ceil(spreadBarMax / 2) - 1)}  ${delayStr} (stable, ${nPreds} pred${nPreds > 1 ? 's' : ''})`);
		continue;
	}

	// Scale: map [min_eta - 30s, max_eta + 30s] to bar width
	// Show scheduled time as │, min-max range as ▒, actual as ◆
	const schedTs = dist.scheduledTs;
	const rangeStart = Math.min(minEta, schedTs) - 30;
	const rangeEnd = Math.max(maxEta, schedTs) + 30;
	const rangeWidth = rangeEnd - rangeStart;

	function toBarPos(ts: number): number {
		return Math.round(((ts - rangeStart) / rangeWidth) * spreadBarMax);
	}

	const schedPos = toBarPos(schedTs);
	const minPos = toBarPos(minEta);
	const maxPos = toBarPos(maxEta);
	const actualPos = toBarPos(actualEta);

	let bar = '';
	for (let i = 0; i < spreadBarMax; i++) {
		if (i === actualPos) bar += '◆';
		else if (i === schedPos) bar += '│';
		else if (i >= minPos && i <= maxPos) bar += '▒';
		else bar += ' ';
	}

	const spreadStr = spread >= 60 ? `${Math.round(spread / 60)}m${spread % 60}s` : `${spread}s`;
	console.log(`  ${label}  ${bar}  spread=${spreadStr} (${nPreds} preds)`);
}
console.log(`  ${''.padEnd(24)} │=scheduled  ▒=prediction range  ◆=actual`);
