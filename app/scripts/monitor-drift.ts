/**
 * ETA Snapshot Monitor
 *
 * Polls NTA GTFS-R TripUpdates every 10s. For each active trip on the
 * monitored routes, records — for every stop ahead of the current vehicle
 * position — the predicted arrival time (scheduled + delay) together with:
 *   • which stop the bus is currently at / approaching  (current_stop_name)
 *   • how many seconds until predicted arrival           (time_delta_seconds)
 *   • the route name                                     (route_name)
 *
 * The first entry in each trip's stopTimeUpdate list is treated as the
 * current service location; all subsequent entries are the future stops
 * that get recorded.
 *
 * Usage:
 *   npx tsx scripts/monitor-drift.ts 46A 145
 *
 * Env vars:
 *   NTA_API_KEY      — required
 *   DATABASE_URL     — static GTFS DB    (default: ./data/busbet.db)
 *   MONITOR_DB       — output DB          (default: ./data/monitor.db)
 *   POLL_INTERVAL_MS — cadence in ms      (default: 10000)
 */

import { createClient } from '@libsql/client';
import type { Client } from '@libsql/client';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import { mkdirSync, writeFileSync } from 'fs';
import { dirname, join } from 'path';
import { readFileSync } from 'fs';
import { gzipSync } from 'zlib';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

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
			const val = trimmed
				.slice(eq + 1)
				.trim()
				.replace(/^["']|["']$/g, '');
			if (!(key in process.env)) process.env[key] = val;
		}
	} catch {
		// no .env — rely on actual env
	}
}
loadEnv('.env');

const routeNames = process.argv.slice(2).filter((a) => !a.startsWith('--'));

const NTA_API_KEY = process.env.NTA_API_KEY ?? '';
const DATABASE_URL = process.env.DATABASE_URL ?? './data/busbet.db';
const MONITOR_DB = process.env.MONITOR_DB ?? './data/monitor2.db';
const POLL_INTERVAL = parseInt(process.env.POLL_INTERVAL_MS ?? '30000');

const FEED_ARCHIVE = process.env.FEED_ARCHIVE ?? './data/feeds';
const VEHICLE_ARCHIVE = process.env.VEHICLE_ARCHIVE ?? './data/vehicles';
const GTFSR_TRIPS_URL = 'https://api.nationaltransport.ie/gtfsr/v2/TripUpdates';
const GTFSR_VEHICLES_URL = 'https://api.nationaltransport.ie/gtfsr/v2/Vehicles';

// ---------------------------------------------------------------------------
// Schema
// ---------------------------------------------------------------------------

const SCHEMA = `
CREATE TABLE IF NOT EXISTS eta_observations (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  observed_at         TEXT    NOT NULL,   -- ISO timestamp when snapshot was taken
  route_name          TEXT    NOT NULL,   -- e.g. "46A"
  trip_id             TEXT    NOT NULL,
  direction_id        INTEGER,            -- 0 or 1 from static GTFS trips
  current_stop_seq    INTEGER,            -- stop_sequence the bus is at/approaching
  current_stop_name   TEXT,               -- human label for current position
  target_stop_seq     INTEGER,
  target_stop_id      TEXT    NOT NULL,
  target_stop_name    TEXT,
  scheduled_arrival   TEXT,               -- HH:MM:SS from static GTFS
  delay_seconds       INTEGER,            -- from GTFS-R feed at time of snapshot
  eta                 TEXT,               -- ISO timestamp: scheduled + delay
  time_delta_seconds  INTEGER             -- seconds from observed_at to eta
);
CREATE INDEX IF NOT EXISTS idx_obs_route     ON eta_observations(route_name);
CREATE INDEX IF NOT EXISTS idx_obs_trip      ON eta_observations(trip_id);
CREATE INDEX IF NOT EXISTS idx_obs_time      ON eta_observations(observed_at);
CREATE INDEX IF NOT EXISTS idx_obs_target    ON eta_observations(target_stop_id, route_name);
CREATE INDEX IF NOT EXISTS idx_obs_direction ON eta_observations(route_name, direction_id);
`;

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
	if (!NTA_API_KEY) {
		console.error('NTA_API_KEY is required. Set it in .env or the environment.');
		process.exit(1);
	}
	if (routeNames.length === 0) {
		console.error('Usage: npx tsx scripts/monitor-drift.ts <route> [route …]');
		console.error('  e.g: npx tsx scripts/monitor-drift.ts 46A 145');
		process.exit(1);
	}

	mkdirSync(dirname(MONITOR_DB), { recursive: true });
	mkdirSync(FEED_ARCHIVE, { recursive: true });
	mkdirSync(VEHICLE_ARCHIVE, { recursive: true });

	const staticDb = createClient({ url: `file:${DATABASE_URL}` });
	const monitorDb = createClient({ url: `file:${MONITOR_DB}` });
	await monitorDb.executeMultiple(SCHEMA);

	// Resolve route short names → trip_id set
	const routeNameByTripId = new Map<string, string>();

	for (const name of routeNames) {
		const r = await staticDb.execute({
			sql: `SELECT t.trip_id
			      FROM trips t
			      JOIN routes r ON r.route_id = t.route_id
			      WHERE r.route_short_name = ?`,
			args: [name]
		});
		if (r.rows.length === 0) {
			console.warn(`[warn] Route not found in static DB: ${name}`);
		} else {
			for (const row of r.rows) routeNameByTripId.set(row.trip_id as string, name);
			console.log(`  ${name.padEnd(8)} — ${r.rows.length} trips loaded`);
		}
	}

	if (routeNameByTripId.size === 0) {
		console.error('No valid routes. Load static GTFS first (npm run load-gtfs).');
		process.exit(1);
	}

	// In-memory caches
	const stopNameCache = new Map<string, string>(); // stop_id → name
	const arrivalCache = new Map<string, string>(); // `${tripId}:${stopId}` → arrival_time
	const directionCache = new Map<string, number | null>(); // tripId → direction_id
	const warmedTrips = new Set<string>();

	async function warmTrip(tripId: string) {
		if (warmedTrips.has(tripId)) return;
		warmedTrips.add(tripId);
		const [stRows, tripRow] = await Promise.all([
			staticDb.execute({
				sql: 'SELECT stop_id, arrival_time FROM stop_times WHERE trip_id = ?',
				args: [tripId]
			}),
			staticDb.execute({
				sql: 'SELECT direction_id FROM trips WHERE trip_id = ? LIMIT 1',
				args: [tripId]
			})
		]);
		for (const row of stRows.rows) {
			arrivalCache.set(`${tripId}:${row.stop_id as string}`, row.arrival_time as string);
		}
		directionCache.set(tripId, (tripRow.rows[0]?.direction_id as number | undefined) ?? null);
	}

	async function warmStops(stopIds: string[]) {
		const missing = stopIds.filter((id) => !stopNameCache.has(id));
		if (missing.length === 0) return;
		const ph = missing.map(() => '?').join(',');
		const r = await staticDb.execute({
			sql: `SELECT stop_id, stop_name FROM stops WHERE stop_id IN (${ph})`,
			args: missing
		});
		for (const row of r.rows) {
			stopNameCache.set(
				row.stop_id as string,
				(row.stop_name as string) ?? (row.stop_id as string)
			);
		}
		for (const id of missing) {
			if (!stopNameCache.has(id)) stopNameCache.set(id, id);
		}
	}

	console.log(
		`\nMonitoring: ${routeNames.join(', ')}  |  poll every ${POLL_INTERVAL / 1000}s  |  Ctrl+C to stop\n`
	);

	let running = true;
	let backoffMs = 0; // extra delay on top of POLL_INTERVAL after a 429
	let lastFeedTs: number | null = null; // feed header.timestamp of last archived TripUpdates
	let lastVehicleTs: number | null = null; // feed header.timestamp of last archived VehiclePositions
	const BACKOFF_MAX = 5 * 60 * 1000; // cap at 5 min

	process.once('SIGINT', () => {
		running = false;
		cancelSleep();
		console.log('\nStopping after current poll…');
		// Second Ctrl+C force-exits immediately
		process.once('SIGINT', () => process.exit(130));
	});

	while (running) {
		const loopStart = Date.now();
		const snapshotAt = new Date().toISOString();
		const snapshotMs = loopStart;

		try {
			// Fetch both feeds in parallel
			const fetchOpts = { headers: { 'x-api-key': NTA_API_KEY }, signal: AbortSignal.timeout(15_000) };
			const [tripsRes, vehiclesRes] = await Promise.all([
				fetch(GTFSR_TRIPS_URL, fetchOpts),
				fetch(GTFSR_VEHICLES_URL, fetchOpts)
			]);

			// Handle rate limiting (check trips response as primary signal)
			if (tripsRes.status === 429 || vehiclesRes.status === 429) {
				const retryHeader = tripsRes.headers.get('retry-after') ?? vehiclesRes.headers.get('retry-after');
				const retryAfterSec = retryHeader ? parseFloat(retryHeader) : NaN;

				if (!isNaN(retryAfterSec)) {
					backoffMs = retryAfterSec * 1000;
					console.warn(`[${ts()}] 429 from NTA GTFS-R — Retry-After: ${retryAfterSec}s`);
				} else {
					backoffMs = backoffMs > 0 ? Math.min(backoffMs * 2, BACKOFF_MAX) : POLL_INTERVAL;
					console.warn(
						`[${ts()}] 429 from NTA GTFS-R — backing off ${(backoffMs / 1000).toFixed(0)}s (no Retry-After header)`
					);
				}
			} else if (!tripsRes.ok) {
				console.warn(`[${ts()}] trips feed ${tripsRes.status} — skipping`);
			} else {
				backoffMs = 0;

				// Archive VehiclePositions
				if (vehiclesRes.ok) {
					const vehicleBytes = new Uint8Array(await vehiclesRes.arrayBuffer());
					const vehicleFeed = FeedMessage.decode(vehicleBytes);
					const vTs = vehicleFeed.header?.timestamp?.toNumber?.() ?? null;
					if (vTs !== null && vTs !== lastVehicleTs) {
						lastVehicleTs = vTs;
						const vFilename = `${snapshotAt.replace(/[:.]/g, '-')}_${vTs}.pb.gz`;
						writeFileSync(join(VEHICLE_ARCHIVE, vFilename), gzipSync(vehicleBytes));
					}
				} else {
					console.warn(`[${ts()}] vehicles feed ${vehiclesRes.status} — skipping archive`);
				}

				// Process TripUpdates
				const rawBytes = new Uint8Array(await tripsRes.arrayBuffer());
				const feed = FeedMessage.decode(rawBytes);

				// Archive TripUpdates
				const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;
				if (feedTs !== null && feedTs !== lastFeedTs) {
					lastFeedTs = feedTs;
					const filename = `${snapshotAt.replace(/[:.]/g, '-')}_${feedTs}.pb.gz`;
					writeFileSync(join(FEED_ARCHIVE, filename), gzipSync(rawBytes));
				}

				type Obs = {
					observed_at: string;
					route_name: string;
					trip_id: string;
					direction_id: number | null;
					current_stop_seq: number | null;
					current_stop_name: string | null;
					target_stop_seq: number | null;
					target_stop_id: string;
					target_stop_name: string | null;
					scheduled_arrival: string | null;
					delay_seconds: number | null;
					eta: string | null;
					time_delta_seconds: number | null;
				};

				const obs: Obs[] = [];
				const allStopIds = new Set<string>();

				for (const entity of feed.entity) {
					const tu = entity.tripUpdate;
					if (!tu?.trip?.tripId) continue;

					const tripId = tu.trip.tripId;
					const routeName = routeNameByTripId.get(tripId);
					if (!routeName) continue;

					const updates = tu.stopTimeUpdate ?? [];
					if (updates.length < 2) continue; // need current + at least one future

					await warmTrip(tripId);

					const [current, ...future] = updates;
					if (current.stopId) allStopIds.add(current.stopId);
					for (const u of future) if (u.stopId) allStopIds.add(u.stopId);

					const currentSeq = (current.stopSequence as number | undefined) ?? null;
					const currentId = current.stopId ?? null;

					for (const stu of future) {
						if (!stu.stopId) continue;

						const delaySec = (stu.arrival?.delay ?? stu.departure?.delay ?? null) as number | null;
						const scheduled = arrivalCache.get(`${tripId}:${stu.stopId}`) ?? null;

						let eta: string | null = null;
						let timeDeltaSec: number | null = null;

						if (scheduled != null && delaySec != null) {
							const etaMs = gtfsTimeToMs(scheduled, snapshotMs) + delaySec * 1000;
							eta = new Date(etaMs).toISOString();
							timeDeltaSec = Math.round((etaMs - snapshotMs) / 1000);
						}

						obs.push({
							observed_at: snapshotAt,
							route_name: routeName,
							trip_id: tripId,
							direction_id: directionCache.get(tripId) ?? null,
							current_stop_seq: currentSeq,
							current_stop_name: currentId ? (stopNameCache.get(currentId) ?? currentId) : null,
							target_stop_seq: (stu.stopSequence as number | undefined) ?? null,
							target_stop_id: stu.stopId,
							target_stop_name: null, // filled after warmStops
							scheduled_arrival: scheduled,
							delay_seconds: delaySec,
							eta,
							time_delta_seconds: timeDeltaSec
						});
					}
				}

				await warmStops([...allStopIds]);

				// Fill target stop names now that cache is warm
				for (const o of obs) {
					o.target_stop_name = stopNameCache.get(o.target_stop_id) ?? o.target_stop_id;
					if (o.current_stop_name === null && o.current_stop_seq !== null) {
						// already null is fine
					}
				}

				// Batch insert
				const CHUNK = 500;
				const stmts = obs.map((o) => ({
					sql: `INSERT INTO eta_observations
					      (observed_at, route_name, trip_id, direction_id,
					       current_stop_seq, current_stop_name,
					       target_stop_seq, target_stop_id, target_stop_name,
					       scheduled_arrival, delay_seconds, eta, time_delta_seconds)
					      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
					args: [
						o.observed_at,
						o.route_name,
						o.trip_id,
						o.direction_id,
						o.current_stop_seq,
						o.current_stop_name,
						o.target_stop_seq,
						o.target_stop_id,
						o.target_stop_name,
						o.scheduled_arrival,
						o.delay_seconds,
						o.eta,
						o.time_delta_seconds
					]
				}));

				for (let i = 0; i < stmts.length; i += CHUNK) {
					await monitorDb.batch(stmts.slice(i, i + CHUNK) as never, 'write');
				}

				const elapsed = Date.now() - loopStart;
				const tripCount = new Set(obs.map((o) => o.trip_id)).size;
				const archived = feedTs !== null && feedTs === lastFeedTs ? ' · archived' : ' · unchanged feed';
				console.log(`[${ts()}] ${tripCount} trips · ${obs.length} observations${archived} (+${elapsed}ms)`);
			}
		} catch (err) {
			console.error(`[${ts()}] error:`, err);
		}

		const elapsed = Date.now() - loopStart;
		const wait = Math.max(0, POLL_INTERVAL + backoffMs - elapsed);
		if (running && wait > 0) await sleep(wait);
	}

	staticDb.close();
	monitorDb.close();
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Convert a GTFS arrival_time string ("HH:MM:SS", hours may exceed 23)
 * to a Unix timestamp in ms, anchored to the same calendar day as `anchorMs`.
 * If the result falls more than 6 hours before anchorMs, add 24 h (handles
 * past-midnight service running into the next calendar day).
 */
function gtfsTimeToMs(gtfsTime: string, anchorMs: number): number {
	const [h, m, s] = gtfsTime.split(':').map(Number);
	const anchor = new Date(anchorMs);
	const base = new Date(anchor);
	base.setHours(0, 0, 0, 0);
	let result = base.getTime() + (h * 3600 + m * 60 + s) * 1000;
	// If the scheduled time is more than 6 h in the past, it belongs to tomorrow's clock
	if (result < anchorMs - 6 * 3600 * 1000) result += 86400 * 1000;
	return result;
}

function ts(): string {
	return new Date().toLocaleTimeString('en-IE', { hour12: false });
}

let sleepResolve: (() => void) | null = null;

function sleep(ms: number): Promise<void> {
	return new Promise((res) => {
		sleepResolve = res;
		setTimeout(() => { sleepResolve = null; res(); }, ms);
	});
}

function cancelSleep() {
	if (sleepResolve) { sleepResolve(); sleepResolve = null; }
}

// ---------------------------------------------------------------------------
main().catch((err) => {
	console.error(err);
	process.exit(1);
});
