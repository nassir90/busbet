import { readFileSync } from 'fs';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

try {
	for (const line of readFileSync('.env', 'utf8').split('\n')) {
		const trimmed = line.trim();
		if (!trimmed || trimmed.startsWith('#')) continue;
		const eq = trimmed.indexOf('=');
		if (eq === -1) continue;
		const key = trimmed.slice(0, eq).trim();
		const val = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
		if (!(key in process.env)) process.env[key] = val;
	}
} catch {}

const key = process.env.NTA_API_KEY ?? '';
if (!key) { console.error('NTA_API_KEY not set'); process.exit(1); }

const url = 'https://api.nationaltransport.ie/gtfsr/v2/Vehicles';

console.log(`Fetching ${url} …`);
const res = await fetch(url, {
	headers: { 'x-api-key': key },
	signal: AbortSignal.timeout(15_000)
});

console.log(`Status: ${res.status} ${res.statusText}`);
console.log('Headers:', Object.fromEntries(res.headers.entries()));

if (!res.ok) {
	console.error('Body:', await res.text());
	process.exit(1);
}

const raw = new Uint8Array(await res.arrayBuffer());
console.log(`Payload: ${raw.byteLength} bytes`);

const feed = FeedMessage.decode(raw);
console.log(`Feed timestamp: ${feed.header?.timestamp}`);
console.log(`Entities: ${feed.entity.length}`);

// Show first 3 entities
for (const e of feed.entity.slice(0, 3)) {
	const v = e.vehicle;
	if (!v) continue;
	console.log('\n---');
	console.log(`  trip_id:    ${v.trip?.tripId}`);
	console.log(`  route_id:   ${v.trip?.routeId}`);
	console.log(`  vehicle_id: ${v.vehicle?.id}`);
	console.log(`  lat/lon:    ${v.position?.latitude}, ${v.position?.longitude}`);
	console.log(`  speed:      ${v.position?.speed} m/s`);
	console.log(`  bearing:    ${v.position?.bearing}`);
	console.log(`  stop_id:    ${v.stopId}`);
	console.log(`  status:     ${v.currentStatus}`);
	console.log(`  timestamp:  ${v.timestamp}`);
}
