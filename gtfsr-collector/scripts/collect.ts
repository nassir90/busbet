/**
 * GTFS-R Collector
 *
 * Polls the NTA GTFS-R TripUpdates and VehiclePositions feeds at a
 * configurable interval and archives the raw protobuf responses as
 * gzipped files for offline analysis and backtesting.
 *
 * Usage:
 *   npx tsx scripts/collect.ts
 *
 * Env vars (or .env file):
 *   NTA_API_KEY       — required
 *   POLL_INTERVAL_MS  — cadence in ms (default: 30000)
 *   DATA_DIR          — root output directory (default: ./data)
 */

import { mkdirSync, writeFileSync, readFileSync } from 'fs';
import { join } from 'path';
import { gzipSync } from 'zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

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
			const val = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
			if (!(key in process.env)) process.env[key] = val;
		}
	} catch {
		// no .env — rely on actual env
	}
}
loadEnv('.env');

const NTA_API_KEY    = process.env.NTA_API_KEY ?? '';
const POLL_INTERVAL  = parseInt(process.env.POLL_INTERVAL_MS ?? '30000');
const DATA_DIR       = process.env.DATA_DIR ?? './data';

const FEEDS_DIR    = join(DATA_DIR, 'feeds');
const VEHICLES_DIR = join(DATA_DIR, 'vehicles');

const TRIPS_URL    = 'https://api.nationaltransport.ie/gtfsr/v2/TripUpdates';
const VEHICLES_URL = 'https://api.nationaltransport.ie/gtfsr/v2/Vehicles';

const BACKOFF_MAX  = 5 * 60 * 1000;

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
	if (!NTA_API_KEY) {
		console.error('NTA_API_KEY is required. Set it in .env or the environment.');
		process.exit(1);
	}

	mkdirSync(FEEDS_DIR, { recursive: true });
	mkdirSync(VEHICLES_DIR, { recursive: true });

	console.log(`GTFS-R Collector`);
	console.log(`  Poll interval: ${POLL_INTERVAL / 1000}s`);
	console.log(`  Data dir:      ${DATA_DIR}`);
	console.log(`  Ctrl+C to stop\n`);

	let running       = true;
	let backoffMs      = 0;
	let lastTripsTs:   number | null = null;
	let lastVehiclesTs: number | null = null;
	let totalTrips     = 0;
	let totalVehicles  = 0;

	process.once('SIGINT', () => {
		running = false;
		cancelSleep();
		console.log('\nStopping…');
		process.once('SIGINT', () => process.exit(130));
	});

	while (running) {
		const loopStart  = Date.now();
		const snapshotAt = new Date().toISOString();
		const safeName   = snapshotAt.replace(/[:.]/g, '-');

		try {
			const fetchOpts = {
				headers: { 'x-api-key': NTA_API_KEY },
				signal: AbortSignal.timeout(15_000)
			};

			const [tripsRes, vehiclesRes] = await Promise.all([
				fetch(TRIPS_URL, fetchOpts),
				fetch(VEHICLES_URL, fetchOpts)
			]);

			// Rate limiting
			if (tripsRes.status === 429 || vehiclesRes.status === 429) {
				const retryHeader = tripsRes.headers.get('retry-after') ?? vehiclesRes.headers.get('retry-after');
				const retryAfterSec = retryHeader ? parseFloat(retryHeader) : NaN;

				if (!isNaN(retryAfterSec)) {
					backoffMs = retryAfterSec * 1000;
					console.warn(`[${ts()}] 429 — Retry-After: ${retryAfterSec}s`);
				} else {
					backoffMs = backoffMs > 0 ? Math.min(backoffMs * 2, BACKOFF_MAX) : POLL_INTERVAL;
					console.warn(`[${ts()}] 429 — backing off ${(backoffMs / 1000).toFixed(0)}s`);
				}
			} else {
				backoffMs = 0;
				let tripsArchived = false;
				let vehiclesArchived = false;

				// Archive TripUpdates
				if (tripsRes.ok) {
					const raw = new Uint8Array(await tripsRes.arrayBuffer());
					const feed = FeedMessage.decode(raw);
					const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;
					if (feedTs !== null && feedTs !== lastTripsTs) {
						lastTripsTs = feedTs;
						writeFileSync(join(FEEDS_DIR, `${safeName}_${feedTs}.pb.gz`), gzipSync(raw));
						tripsArchived = true;
						totalTrips++;
					}
				} else {
					console.warn(`[${ts()}] trips ${tripsRes.status}`);
				}

				// Archive VehiclePositions
				if (vehiclesRes.ok) {
					const raw = new Uint8Array(await vehiclesRes.arrayBuffer());
					const feed = FeedMessage.decode(raw);
					const feedTs = feed.header?.timestamp?.toNumber?.() ?? null;
					if (feedTs !== null && feedTs !== lastVehiclesTs) {
						lastVehiclesTs = feedTs;
						writeFileSync(join(VEHICLES_DIR, `${safeName}_${feedTs}.pb.gz`), gzipSync(raw));
						vehiclesArchived = true;
						totalVehicles++;
					}
				} else {
					console.warn(`[${ts()}] vehicles ${vehiclesRes.status}`);
				}

				const elapsed = Date.now() - loopStart;
				const t = tripsArchived ? 'T' : '-';
				const v = vehiclesArchived ? 'V' : '-';
				console.log(`[${ts()}] [${t}${v}] (+${elapsed}ms) total: ${totalTrips}T ${totalVehicles}V`);
			}
		} catch (err) {
			console.error(`[${ts()}] error:`, err);
		}

		const elapsed = Date.now() - loopStart;
		const wait = Math.max(0, POLL_INTERVAL + backoffMs - elapsed);
		if (running && wait > 0) await sleep(wait);
	}
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
