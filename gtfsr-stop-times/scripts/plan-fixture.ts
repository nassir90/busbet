/**
 * Writes a /plan answer as a screenshot fixture for the app (tfi-app's FixtureGtfsApi). The app's
 * fixtures are pinned to one instant; the timetable that produced this plan may be for a different
 * day, so every time in it can be shifted by whole days to land on the fixture's.
 *
 *   npx tsx scripts/plan-fixture.ts --db busbet.db --at 2026-10-13T08:15 \
 *     --from 53.3433,-6.4498 --to 53.3468,-6.2585 --shift-days -4 --out-dir ../tfi-app/app/src/test/resources/screenshot-fixtures/plan
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Planner } from '../src/plan/planner.js';
import { csvSource, sqliteSource } from '../src/plan/sources.js';
import { dublinEpoch } from './plan-cli.js';

function arg(name: string): string | undefined {
	const i = process.argv.indexOf(`--${name}`);
	return i >= 0 ? process.argv[i + 1] : undefined;
}

const TIME_KEYS = new Set(['departure', 'arrival', 'start', 'end', 'scheduled_start', 'scheduled_end', 'feed_timestamp']);

function shiftTimes(v: unknown, by: number): unknown {
	if (Array.isArray(v)) return v.map((x) => shiftTimes(x, by));
	if (v && typeof v === 'object') {
		return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, TIME_KEYS.has(k) && typeof x === 'number' ? x + by : shiftTimes(x, by)]));
	}
	return v;
}

async function main() {
	const source = arg('db') ? await sqliteSource(arg('db')!) : csvSource(arg('gtfs')!);
	const [fLat, fLon] = arg('from')!.split(',').map(Number);
	const [tLat, tLon] = arg('to')!.split(',').map(Number);
	const shift = parseInt(arg('shift-days') ?? '0', 10) * 86400;
	const res = await new Planner(source).plan({
		from: { lat: fLat, lon: fLon },
		to: { lat: tLat, lon: tLon },
		departAt: dublinEpoch(arg('at')!),
	});
	const { debug: _debug, ...body } = res;
	const dir = arg('out-dir')!;
	mkdirSync(dir, { recursive: true });
	const key = `${fLat.toFixed(4)},${fLon.toFixed(4)}-${tLat.toFixed(4)},${tLon.toFixed(4)}`;
	writeFileSync(join(dir, `${key}.json`), JSON.stringify(shiftTimes(body, shift)));
	console.log(`${key}: ${res.itineraries.length} itineraries`);
}

main().catch((e) => { console.error(e); process.exit(1); });
