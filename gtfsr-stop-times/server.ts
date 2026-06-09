import http from 'node:http';
import { readFileSync } from 'node:fs';
import { createSqliteBackend } from './src/sqlite.js';
import { getDepartures } from './src/departures.js';
import { fetchFeed, applyRealtimeDelaysToTrip } from './src/gtfs.js';
import { getVehiclesForTrips, vehiclesDirFromFeedsDir } from './src/vehicles.js';

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

function respond(res: http.ServerResponse, status: number, body: unknown) {
	const payload = JSON.stringify(body);
	res.writeHead(status, { 'Content-Type': 'application/json' });
	res.end(payload);
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

		const departures = path.match(/^\/departures\/([^/]+)$/);
		if (departures) {
			const result = await getDepartures(departures[1], storage, FEEDS_DIR);
			if (!result) return respond(res, 404, { message: `Stop ${departures[1]} not found` });
			return respond(res, 200, result);
		}

		const routesForStop = path.match(/^\/stop-routes\/([^/]+)$/);
		if (routesForStop) {
			const routes = await storage.getRoutesForStop(routesForStop[1]);
			return respond(res, 200, routes);
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
			return respond(res, 200, stops);
		}

		const tripDetail = path.match(/^\/trips\/([^/]+)$/);
		if (tripDetail) {
			const detail = await storage.getTripDetail(tripDetail[1]);
			if (!detail) return respond(res, 404, { message: 'Trip not found' });
			await applyRealtimeDelaysToTrip(detail, FEEDS_DIR);
			return respond(res, 200, detail);
		}

		if (path === '/trip-updates') {
			const feed = await fetchFeed(FEEDS_DIR);
			return respond(res, 200, buildTripUpdatesJson(feed));
		}

		// /vehicles/{stop_code} — active vehicle positions for trips serving this stop,
		// with server-derived bearings (NTA feed bearing field is always 0).
		const vehicles = path.match(/^\/vehicles\/([^/]+)$/);
		if (vehicles) {
			const stopCode = vehicles[1];
			const windowMins = parseInt(url.searchParams.get('window') ?? '10');
			const deps = await getDepartures(stopCode, storage, FEEDS_DIR);
			if (!deps) return respond(res, 404, { message: `Stop ${stopCode} not found` });
			const nowMins = new Date().getHours() * 60 + new Date().getMinutes();
			const tripMap = new Map(
				deps.departures
					.filter((d) => {
						const [h, m] = (d.estimated_departure ?? d.scheduled_departure).split(':').map(Number);
						return (h * 60 + m) - nowMins <= windowMins;
					})
					.map((d) => [d.trip_id, { route: d.route_short_name, delay: d.delay_seconds }])
			);
			const positions = await getVehiclesForTrips(tripMap, VEHICLES_DIR);
			return respond(res, 200, positions);
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
