import http from 'node:http';
import { readFileSync } from 'node:fs';
import { createSqliteBackend } from './src/sqlite.js';
import { getDepartures, getDeparturesBatch } from './src/departures.js';
import { fetchFeed, selectFeed, applyRealtimeDelaysToTrip } from './src/gtfs.js';
import { getVehiclesForTrips, vehiclesDirFromFeedsDir } from './src/vehicles.js';
import { parseTimeParam, parseDuration, DEFAULT_LOOKBACK_S } from './src/snapshots.js';

const MAX_RANGE_STEPS = 1000;

/**
 * Ceiling on stops per batch departures call. A home screen has a handful of favourites; this is
 * only here so one request can't be turned into an unbounded pile of SQLite work.
 */
const MAX_BATCH_CODES = 50;

/**
 * Static GTFS derived from the scheduled feed — stop-route lists, route stop sequences, shape
 * geometry. It changes when a new timetable is loaded, not minute to minute, and the clients
 * refetch it on every screen open. A short shared max-age lets OkHttp and any proxy in front of
 * this serve it without a round trip.
 */
const STATIC_CACHE_HEADERS = { 'Cache-Control': 'public, max-age=3600' };

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

function loadEnv(path: string) {
	try {
		for (const line of readFileSync(path, 'utf8').split('\n')) {
			const trimmed = line.trim();
			if (!trimmed || trimmed.startsWith('#')) continue;
			const eq = trimmed.indexOf('=');
			if (eq === -1) continue;
			const key = trimmed.slice(0, eq).trim();
			const val = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
			if (!(key in process.env)) process.env[key] = val;
		}
	} catch { /* no .env */ }
}
loadEnv('.env');

const PORT        = parseInt(process.env.GTFSR_STOP_TIMES_PORT ?? '8110');
const DB_PATH     = process.env.DATABASE_URL   ?? '/home/lab/Projects/busbet/app/data/busbet.db';
const FEEDS_DIR   = process.env.GTFS_FEEDS_DIR ?? '/home/lab/Projects/busbet/gtfsr-collector/data/feeds';
const VEHICLES_DIR = process.env.GTFS_VEHICLES_DIR ?? vehiclesDirFromFeedsDir(FEEDS_DIR);

const storage = createSqliteBackend(DB_PATH);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function respond(res: http.ServerResponse, status: number, body: unknown, headers?: Record<string, string>) {
	const payload = JSON.stringify(body);
	res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
	res.end(payload);
}

/** Builds a list of sample instants from start..end inclusive, stepping by `step` seconds. */
function rangeSteps(start: number, end: number, step: number): number[] | null {
	if (!Number.isFinite(start) || !Number.isFinite(end) || !(step > 0) || end < start) return null;
	const count = Math.floor((end - start) / step) + 1;
	if (count > MAX_RANGE_STEPS) return null;
	const out: number[] = [];
	for (let t = start; t <= end + 1e-9; t += step) out.push(Math.floor(t));
	return out;
}

function buildTripUpdatesJson(feed: Awaited<ReturnType<typeof fetchFeed>>) {
	if (!feed) return {};
	const out: Record<string, {
		scheduleRelationship: number;
		stopTimeUpdates: { stopId: string; stopSequence: number; arrivalDelay: number; departureDelay: number }[];
	}> = {};
	for (const entity of feed.entity) {
		const tu = entity.tripUpdate;
		if (!tu?.trip?.tripId) continue;
		out[tu.trip.tripId] = {
			scheduleRelationship: (tu.trip.scheduleRelationship as unknown as number) ?? 0,
			stopTimeUpdates: (tu.stopTimeUpdate ?? []).map((s) => ({
				stopId: s.stopId ?? '',
				stopSequence: s.stopSequence ?? 0,
				arrivalDelay: s.arrival?.delay ?? 0,
				departureDelay: s.departure?.delay ?? 0,
			})),
		};
	}
	return out;
}

/** Vehicle positions for trips departing `stopCode` within `windowMins`, at the given instant. */
async function vehiclesAt(stopCode: string, atSec: number | undefined, windowMins: number, lookbackSec: number) {
	const deps = await getDepartures(stopCode, storage, FEEDS_DIR, atSec, lookbackSec);
	if (!deps) return null;
	const ref = atSec != null ? new Date(atSec * 1000) : new Date();
	const nowMins = ref.getHours() * 60 + ref.getMinutes();
	const tripMap = new Map(
		deps.departures
			.filter((d) => {
				const [h, m] = (d.estimated_departure ?? d.scheduled_departure).split(':').map(Number);
				return (h * 60 + m) - nowMins <= windowMins;
			})
			.map((d) => [d.trip_id, { route: d.route_short_name, delay: d.delay_seconds }])
	);
	const result = getVehiclesForTrips(tripMap, VEHICLES_DIR, atSec, lookbackSec);
	return { ...result, positions: await withTripSpans(result.positions) };
}

/**
 * Attaches each trip's scheduled first departure and last arrival, so a client can tell a bus
 * that hasn't started from one that has finished. The feed's own status fields are constants.
 */
async function withTripSpans<T extends { trip_id: string }>(positions: T[]) {
	if (!positions.length) return positions;
	const spans = await storage.getTripSpans(positions.map((p) => p.trip_id));
	return positions.map((p) => ({
		...p,
		first_departure: spans.get(p.trip_id)?.first_departure ?? null,
		last_arrival: spans.get(p.trip_id)?.last_arrival ?? null,
	}));
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
	const url = new URL(req.url ?? '/', `http://localhost`);
	const path = url.pathname;

	try {
		if (path === '/health') {
			return respond(res, 200, { ok: true });
		}

		if (path === '/stops') {
			const q = url.searchParams.get('q')?.trim() ?? '';
			if (q.length < 2) return respond(res, 400, { message: 'q must be at least 2 characters' });
			const stops = await storage.searchStops(q);
			return respond(res, 200, stops);
		}

		const stopByCode = path.match(/^\/stops\/([^/]+)$/);
		if (stopByCode) {
			const stop = await storage.getStopByCode(stopByCode[1]);
			if (!stop) return respond(res, 404, { message: 'Stop not found' });
			return respond(res, 200, stop);
		}

		// /departures/{code}/range?start=&end=&step= — schedule + realtime overlay sampled over time
		const departuresRange = path.match(/^\/departures\/([^/]+)\/range$/);
		if (departuresRange) {
			const start = parseTimeParam(url.searchParams.get('start'));
			const end = parseTimeParam(url.searchParams.get('end'));
			const step = parseDuration(url.searchParams.get('step'), 60);
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			if (start == null || end == null) return respond(res, 400, { message: 'start and end required' });
			const steps = rangeSteps(start, end, step);
			if (!steps) return respond(res, 400, { message: `invalid range or too many steps (max ${MAX_RANGE_STEPS})` });
			let stop = null;
			const series = [];
			for (const t of steps) {
				const r = await getDepartures(departuresRange[1], storage, FEEDS_DIR, t, lookback);
				if (!r) return respond(res, 404, { message: `Stop ${departuresRange[1]} not found` });
				stop = r.stop;
				series.push({ at: t, feed_timestamp: r.feed_timestamp, stale: r.stale, departures: r.departures });
			}
			return respond(res, 200, { stop, series });
		}

		// /departures?codes=a,b,c — one round trip for a whole favourites list. The home screen
		// used to fire one request per card at once, which OkHttp then throttled to 5 concurrent
		// per host, so the tail of a long favourites list waited on a second wave.
		if (path === '/departures') {
			const raw = url.searchParams.get('codes')?.trim() ?? '';
			const codes = [...new Set(raw.split(',').map((c) => c.trim()).filter(Boolean))];
			if (!codes.length) return respond(res, 400, { message: 'codes required' });
			if (codes.length > MAX_BATCH_CODES) {
				return respond(res, 400, { message: `too many codes (max ${MAX_BATCH_CODES})` });
			}
			const at = parseTimeParam(url.searchParams.get('time'));
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			const batch = await getDeparturesBatch(codes, storage, FEEDS_DIR, at ?? undefined, lookback);
			// Keyed by the code the caller asked for, so the client doesn't have to re-match on
			// stop_code. Unknown codes are reported rather than silently dropped.
			return respond(res, 200, {
				results: Object.fromEntries(batch),
				missing: codes.filter((c) => !batch.has(c)),
			});
		}

		const departures = path.match(/^\/departures\/([^/]+)$/);
		if (departures) {
			const at = parseTimeParam(url.searchParams.get('time'));
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			const result = await getDepartures(departures[1], storage, FEEDS_DIR, at ?? undefined, lookback);
			if (!result) return respond(res, 404, { message: `Stop ${departures[1]} not found` });
			return respond(res, 200, result);
		}

		const routesForStop = path.match(/^\/stop-routes\/([^/]+)$/);
		if (routesForStop) {
			const routes = await storage.getRoutesForStop(routesForStop[1]);
			return respond(res, 200, routes, STATIC_CACHE_HEADERS);
		}

		if (path === '/routes') {
			const q = url.searchParams.get('q')?.trim() ?? '';
			if (q.length < 1) return respond(res, 400, { message: 'q required' });
			const routes = await storage.searchRoutes(q);
			return respond(res, 200, routes);
		}

		if (path === '/route-stops') {
			const route = url.searchParams.get('route')?.trim() ?? '';
			const dir = url.searchParams.get('direction');
			if (!route || dir === null) return respond(res, 400, { message: 'route and direction required' });
			const stops = await storage.getRouteStops(route, parseInt(dir));
			if (!stops.length) return respond(res, 404, { message: 'No stops found' });
			return respond(res, 200, stops, STATIC_CACHE_HEADERS);
		}

		const tripDetail = path.match(/^\/trips\/([^/]+)$/);
		if (tripDetail) {
			const detail = await storage.getTripDetail(tripDetail[1]);
			if (!detail) return respond(res, 404, { message: 'Trip not found' });
			const at = parseTimeParam(url.searchParams.get('time'));
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			const { feed, feedTimestamp, stale } = selectFeed(FEEDS_DIR, at ?? undefined, at != null ? lookback : Infinity);
			applyRealtimeDelaysToTrip(detail, feed);
			return respond(res, 200, { ...detail, feed_timestamp: feedTimestamp, stale });
		}

		if (path === '/trip-updates') {
			const feed = fetchFeed(FEEDS_DIR);
			return respond(res, 200, buildTripUpdatesJson(feed));
		}

		// /vehicles/{code}/range?start=&end=&step= — vehicle positions sampled over time (track replay)
		const vehiclesRange = path.match(/^\/vehicles\/([^/]+)\/range$/);
		if (vehiclesRange) {
			const start = parseTimeParam(url.searchParams.get('start'));
			const end = parseTimeParam(url.searchParams.get('end'));
			const step = parseDuration(url.searchParams.get('step'), 30);
			const windowMins = parseInt(url.searchParams.get('window') ?? '10');
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			if (start == null || end == null) return respond(res, 400, { message: 'start and end required' });
			const steps = rangeSteps(start, end, step);
			if (!steps) return respond(res, 400, { message: `invalid range or too many steps (max ${MAX_RANGE_STEPS})` });
			const series = [];
			for (const t of steps) {
				const v = await vehiclesAt(vehiclesRange[1], t, windowMins, lookback);
				if (!v) return respond(res, 404, { message: `Stop ${vehiclesRange[1]} not found` });
				series.push({ at: t, feed_timestamp: v.feedTimestamp, stale: v.stale, positions: v.positions });
			}
			return respond(res, 200, { series });
		}

		// /vehicles/{stop_code} — active vehicle positions for trips serving this stop,
		// with server-derived bearings (NTA feed bearing field is always 0).
		// Road geometry. Trip-scoped: a route has several shapes across its trips, so a
		// route-level lookup would have to pick one arbitrarily. Points are already decimated at
		// load time (see load-gtfs.ts --shape-tolerance), ~111 per shape rather than ~1,244.
		// Position of one specific trip's vehicle. /vehicles/{code} is stop-scoped, which is no
		// use on the trip screen: the bus may be nowhere near the stop you came from.
		const vehicleForTrip = path.match(/^\/vehicles\/trip\/([^/]+)$/);
		if (vehicleForTrip) {
			const tripId = decodeURIComponent(vehicleForTrip[1]);
			const at = parseTimeParam(url.searchParams.get('time'));
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			const detail = await storage.getTripDetail(tripId);
			const route = detail?.route_short_name ?? '';
			const v = getVehiclesForTrips(
				new Map([[tripId, { route, delay: null }]]),
				VEHICLES_DIR,
				at ?? undefined,
				lookback,
			);
			const pos = (await withTripSpans(v.positions))[0] ?? null;
			if (!pos) return respond(res, 404, { message: `No live position for trip ${tripId}` });
			return respond(res, 200, pos);
		}

		const shapeForTrip = path.match(/^\/shapes\/trip\/([^/]+)$/);
		if (shapeForTrip) {
			const pts = await storage.getShapeForTrip(decodeURIComponent(shapeForTrip[1]));
			if (!pts.length) return respond(res, 404, { message: `No shape for trip ${shapeForTrip[1]}` });
			return respond(res, 200, pts, STATIC_CACHE_HEADERS);
		}

		const shapeById = path.match(/^\/shapes\/([^/]+)$/);
		if (shapeById) {
			const pts = await storage.getShape(decodeURIComponent(shapeById[1]));
			if (!pts.length) return respond(res, 404, { message: `Shape ${shapeById[1]} not found` });
			return respond(res, 200, pts, STATIC_CACHE_HEADERS);
		}

		const vehicles = path.match(/^\/vehicles\/([^/]+)$/);
		if (vehicles) {
			const at = parseTimeParam(url.searchParams.get('time'));
			const windowMins = parseInt(url.searchParams.get('window') ?? '10');
			const lookback = parseDuration(url.searchParams.get('lookback'), DEFAULT_LOOKBACK_S);
			const v = await vehiclesAt(vehicles[1], at ?? undefined, windowMins, lookback);
			if (!v) return respond(res, 404, { message: `Stop ${vehicles[1]} not found` });
			return respond(res, 200, v.positions, {
				'X-Feed-Timestamp': String(v.feedTimestamp ?? ''),
				'X-Stale': String(v.stale),
			});
		}

		respond(res, 404, { message: 'Not found' });
	} catch (err) {
		console.error('[server] error:', err);
		respond(res, 500, { message: 'Internal error' });
	}
});

server.listen(PORT, '127.0.0.1', () => {
	console.log(`gtfsr-stop-times listening on port ${PORT}`);
	console.log(`  DB:    ${DB_PATH}`);
	console.log(`  Feeds: ${FEEDS_DIR}`);
});
