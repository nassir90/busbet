/**
 * Runs the planner against an extracted GTFS directory or the SQLite database and prints what it
 * finds. Used for the benchmarks and the comparison against other planners in
 * docs/ROUTE_PLANNING.md.
 *
 *   npx tsx scripts/plan-cli.ts --gtfs /path/to/extracted --at 2026-10-13T08:30 [--json out.json]
 *   npx tsx scripts/plan-cli.ts --db ../data/busbet.db --at ...
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { gunzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import { Planner, type Itinerary } from '../src/plan/planner.js';
import { csvSource, sqliteSource } from '../src/plan/sources.js';
import { epochZeroOf, typedBytes } from '../src/plan/timetable.js';

export const OD_PAIRS: { name: string; from: [number, number]; to: [number, number] }[] = [
	{ name: 'Finnstown Abbey → Aston Quay', from: [53.3433, -6.4498], to: [53.3468, -6.2585] },
	{ name: 'Lucan Village → St James’s Hospital', from: [53.3576, -6.4489], to: [53.3415, -6.2955] },
	{ name: 'Heuston Station → Dublin Airport', from: [53.3464, -6.2925], to: [53.4264, -6.2499] },
	{ name: 'Tallaght (The Square) → DCU Glasnevin', from: [53.2874, -6.3735], to: [53.3858, -6.2565] },
	{ name: 'Howth → Trinity College', from: [53.3868, -6.0654], to: [53.3438, -6.2546] },
	{ name: 'Blanchardstown SC → UCD Belfield', from: [53.3925, -6.3923], to: [53.3065, -6.2210] },
	{ name: 'Dún Laoghaire → Phibsborough', from: [53.2943, -6.1339], to: [53.3606, -6.2726] },
	{ name: 'Swords Main St → Tallaght Hospital', from: [53.4597, -6.2181], to: [53.2896, -6.3786] },
];

function arg(name: string): string | undefined {
	const i = process.argv.indexOf(`--${name}`);
	return i >= 0 ? process.argv[i + 1] : undefined;
}

/** "2026-10-13T08:30" read as Dublin wall-clock time. */
export function dublinEpoch(local: string): number {
	const [d, t] = local.split('T');
	const date = d.replace(/-/g, '');
	const [h, m] = t.split(':').map(Number);
	return epochZeroOf(date, 'Europe/Dublin') + h * 3600 + m * 60;
}

export function hhmm(epoch: number): string {
	return new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Dublin', hour: '2-digit', minute: '2-digit' }).format(new Date(epoch * 1000));
}

export function summarise(i: Itinerary): string {
	const parts = i.legs.map((l) =>
		l.mode === 'walk'
			? `walk ${Math.round(l.duration_s / 60)}′`
			: `${l.mode} ${l.route} (${l.from.name} → ${l.to.name})${l.realtime ? ` [${l.delay_s! >= 0 ? '+' : ''}${Math.round(l.delay_s! / 60)}′ rt]` : ''}`,
	);
	return `${hhmm(i.departure)}–${hhmm(i.arrival)} ${Math.round(i.duration_s / 60)} min, ${i.transfers} chg: ${parts.join(' › ')}`;
}

async function main() {
	const gtfs = arg('gtfs');
	const db = arg('db');
	const at = arg('at') ?? '2026-10-13T08:30';
	const out = arg('json');
	const rtFile = arg('rt');
	// CommonJS package: default import, then reach in (see src/snapshots.ts).
	const feed = rtFile
		? (GtfsRealtimeBindings as any).transit_realtime.FeedMessage.decode(gunzipSync(readFileSync(rtFile)))
		: null;
	const source = gtfs ? csvSource(gtfs) : await sqliteSource(db!);
	const planner = new Planner(source);
	const departAt = dublinEpoch(at);

	const heapBefore = process.memoryUsage();
	const tt = await planner.timetableFor(new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Dublin' }).format(new Date(departAt * 1000)).replace(/-/g, ''));
	const heapAfter = process.memoryUsage();
	console.log(`timetable ${tt.date}: ${tt.nStops} stops, ${tt.nPatterns} patterns, ${tt.nTrips} trips, ${tt.arr.length} stop events, ${tt.transferTo.length} transfers`);
	console.log(`  build ${tt.buildMs} ms, typed arrays ${(typedBytes(tt) / 2 ** 20).toFixed(1)} MB, rss +${((heapAfter.rss - heapBefore.rss) / 2 ** 20).toFixed(0)} MB (rss now ${(heapAfter.rss / 2 ** 20).toFixed(0)} MB)`);

	const results: unknown[] = [];
	for (const od of OD_PAIRS) {
		const started = performance.now();
		const res = await planner.plan({ from: { lat: od.from[0], lon: od.from[1] }, to: { lat: od.to[0], lon: od.to[1] }, departAt, feed });
		const ms = performance.now() - started;
		console.log(`\n${od.name}  [${ms.toFixed(0)} ms total, ${res.debug.raptor_runs} runs, raptor ${res.debug.raptor_ms} ms, ${res.debug.access_stops}/${res.debug.egress_stops} stops]`);
		if (res.realtime.used) console.log(`  realtime: ${res.realtime.updated_trips} trips updated, ${res.realtime.ignored_trips} ignored`);
		for (const i of res.itineraries) console.log('  ' + summarise(i));
		results.push({ od, ms, res });
	}
	if (out) writeFileSync(out, JSON.stringify(results, null, 1));
}

if (import.meta.url === `file://${process.argv[1]}`) main().catch((e) => { console.error(e); process.exit(1); });
