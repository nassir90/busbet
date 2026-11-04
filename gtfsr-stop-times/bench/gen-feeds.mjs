// Usage: node bench/gen-feeds.mjs <dataDir> <unix seconds of the newest snapshot>
// Synthetic NTA-shaped TripUpdates and VehiclePositions snapshots, 30s apart, covering trips
// that are mid-journey at the chosen instant. Sizes are chosen to resemble the live NTA feed
// (a few thousand trip updates with full stop lists; a couple of thousand vehicles).
import { mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
const { transit_realtime: rt } = GtfsRealtimeBindings;
const [, , dataDir, atArg] = process.argv;
const AT = parseInt(atArg);
const N_ROUTES = 400, N_TRIPS = 30000, STOPS = 40, N_TU = 3500, N_VEH = 2000, SNAPS = 60, STEP = 30;
let seed = 7; const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const feeds = join(dataDir, 'feeds'), vehicles = join(dataDir, 'vehicles');
rmSync(feeds, { recursive: true, force: true }); rmSync(vehicles, { recursive: true, force: true });
mkdirSync(feeds, { recursive: true }); mkdirSync(vehicles, { recursive: true });
const name = (ts) => `${new Date(ts * 1000).toISOString().replace(/[:.]/g, '-')}_${ts}.pb.gz`;
const tripId = (i) => `${i}.y1006.60-${i % N_ROUTES}-d12-1.${Math.floor(i / N_ROUTES) % 2}.O`;
// Deterministic subset of trips, spread over the whole table so stops see a mix of realtime
// and schedule-only departures.
const tuTrips = []; for (let i = 0; i < N_TRIPS && tuTrips.length < N_TU; i++) if (rnd() < 0.15) tuTrips.push(i);
const delays = tuTrips.map(() => Math.floor(rnd() * 600 - 120));
// 1..12 stop time updates per trip (NTA sends the remaining stops, not the whole trip).
const nStu = tuTrips.map(() => 1 + Math.floor(rnd() * 12));
for (let s = 0; s < SNAPS; s++) {
	const ts = AT - (SNAPS - 1 - s) * STEP;
	const tu = rt.FeedMessage.create({
		header: { gtfsRealtimeVersion: '2.0', incrementality: 0, timestamp: ts },
		entity: tuTrips.map((t, k) => ({
			id: String(k),
			tripUpdate: {
				trip: { tripId: tripId(t), startTime: '08:00:00', startDate: '20261104', scheduleRelationship: 0, routeId: `R${t % N_ROUTES}` },
				stopTimeUpdate: Array.from({ length: nStu[k] }, (_, i) => ({
					stopSequence: i + 1,
					stopId: `8220DB${String((t * 13 + i * 7) % 10000).padStart(6, '0')}`,
					arrival: { delay: delays[k] + s },
					departure: { delay: delays[k] + s },
					scheduleRelationship: 0,
				})),
			},
		})),
	});
	writeFileSync(join(feeds, name(ts)), gzipSync(rt.FeedMessage.encode(tu).finish()));
	const vp = rt.FeedMessage.create({
		header: { gtfsRealtimeVersion: '2.0', incrementality: 0, timestamp: ts },
		entity: tuTrips.slice(0, N_VEH).map((t, k) => ({
			id: `V${k}`,
			vehicle: {
				trip: { tripId: tripId(t), startTime: '08:00:00', startDate: '20261104', scheduleRelationship: 0, routeId: `R${t % N_ROUTES}` },
				position: { latitude: 53.3 + (k % 100) * 0.002 + s * 0.0003, longitude: -6.3 + Math.floor(k / 100) * 0.005 + s * 0.0002, bearing: 0 },
				timestamp: ts, vehicle: { id: `bus${k}` },
			},
		})),
	});
	writeFileSync(join(vehicles, name(ts)), gzipSync(rt.FeedMessage.encode(vp).finish()));
}
console.log('wrote', SNAPS, 'snapshots each to', feeds, vehicles);
