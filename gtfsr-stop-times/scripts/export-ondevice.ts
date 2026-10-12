/**
 * The on-device experiment's data side: writes one service day of the planner's timetable as a
 * flat little-endian binary a phone could download and memory-map, plus the answers the Node
 * planner gives for the benchmark journeys so the Kotlin port can be checked against them.
 *
 *   npx tsx scripts/export-ondevice.ts --gtfs /path/to/extracted --date 20261013 --out /tmp/tt.bin
 *
 * Layout: "IOMP", version, then int32 arrays each prefixed by its length, then string tables
 * (int32 byte length + UTF-8 joined with \0). See OnDeviceTimetable.kt for the reader.
 */
import { writeFileSync } from 'node:fs';
import { gzipSync, brotliCompressSync, constants } from 'node:zlib';
import { csvSource } from '../src/plan/sources.js';
import { buildTimetable, type Timetable } from '../src/plan/timetable.js';
import { raptor } from '../src/plan/raptor.js';
import { DEFAULT_WALK, walkSeconds } from '../src/plan/geo.js';
import { OD_PAIRS, dublinEpoch } from './plan-cli.js';

function arg(name: string): string | undefined {
	const i = process.argv.indexOf(`--${name}`);
	return i >= 0 ? process.argv[i + 1] : undefined;
}

function encode(tt: Timetable): Buffer {
	const parts: Buffer[] = [];
	const header = Buffer.alloc(16);
	header.write('IOMP', 0, 'latin1');
	header.writeInt32LE(1, 4);
	header.writeBigInt64LE(BigInt(tt.epochZero), 8);
	parts.push(header);
	const ints = (a: ArrayLike<number>) => {
		const b = Buffer.alloc(4 + a.length * 4);
		b.writeInt32LE(a.length, 0);
		for (let i = 0; i < a.length; i++) b.writeInt32LE(a[i], 4 + i * 4);
		parts.push(b);
	};
	const strings = (a: string[]) => {
		const s = Buffer.from(a.join('\0'), 'utf8');
		const b = Buffer.alloc(4);
		b.writeInt32LE(s.length, 0);
		parts.push(b, s);
	};
	ints(Int32Array.from(tt.stopLat, (x) => Math.round(x * 1e6)));
	ints(Int32Array.from(tt.stopLon, (x) => Math.round(x * 1e6)));
	ints(tt.patternStopsStart);
	ints(tt.patternStops);
	ints(tt.patternTripsStart);
	ints(tt.patternRoute);
	ints(tt.tripTimesStart);
	ints(tt.arr);
	ints(tt.dep);
	ints(tt.stopPatternsStart);
	ints(tt.stopPatterns);
	ints(tt.stopPatternPos);
	ints(tt.transfersStart);
	ints(tt.transferTo);
	ints(tt.transferSecs);
	ints(tt.routeType);
	strings(tt.stopName);
	strings(tt.stopCode);
	strings(tt.routeShortName);
	strings(tt.tripHeadsign);
	return Buffer.concat(parts);
}

/**
 * What a purpose-built format would cost instead of raw int32s: every NTA time is a whole minute,
 * so a trip is its first departure in minutes plus one small per-stop delta, as varints.
 */
function compactTimesBytes(tt: Timetable): number {
	let bytes = 0;
	const varint = (n: number) => (n < 128 ? 1 : n < 16384 ? 2 : 3);
	for (let t = 0; t < tt.nTrips; t++) {
		const p = tt.tripPattern[t];
		const len = tt.patternStopsStart[p + 1] - tt.patternStopsStart[p];
		const base = tt.tripTimesStart[t];
		bytes += varint(Math.max(0, Math.round(tt.dep[base] / 60) + 1440));
		for (let i = 1; i < len; i++) {
			bytes += varint(Math.max(0, Math.round((tt.arr[base + i] - tt.dep[base + i - 1]) / 60)));
			bytes += varint(Math.max(0, Math.round((tt.dep[base + i] - tt.arr[base + i]) / 60)));
		}
	}
	return bytes;
}

async function main() {
	const gtfs = arg('gtfs')!;
	const date = arg('date') ?? '20261013';
	const out = arg('out')!;
	const tt = await buildTimetable(csvSource(gtfs), date);
	const bin = encode(tt);
	writeFileSync(out, bin);
	const gz = gzipSync(bin, { level: 9 });
	const br = brotliCompressSync(bin, { params: { [constants.BROTLI_PARAM_QUALITY]: 11, [constants.BROTLI_PARAM_SIZE_HINT]: bin.length } });
	const mb = (n: number) => (n / 2 ** 20).toFixed(2) + ' MB';
	const timesRaw = (tt.arr.length + tt.dep.length) * 4;
	console.log(`${date}: ${tt.nTrips} trips, ${tt.arr.length} stop events`);
	console.log(`  raw ${mb(bin.length)}, gzip -9 ${mb(gz.length)}, brotli 11 ${mb(br.length)}`);
	console.log(`  of which times: raw int32 ${mb(timesRaw)}, minute-delta varints would be ${mb(compactTimesBytes(tt))}`);

	// Reference answers: one plain RAPTOR run per journey at 08:30, best arrival per round.
	const departAt = dublinEpoch(`${date.slice(0, 4)}-${date.slice(4, 6)}-${date.slice(6)}T08:30`);
	const t0 = departAt - tt.epochZero;
	const expected = OD_PAIRS.map((od) => {
		const near = (lat: number, lon: number) => tt.grid.within(lat, lon, 1000)
			.filter((x) => tt.stopPatternsStart[x.index + 1] > tt.stopPatternsStart[x.index])
			.map((x) => ({ stop: x.index, walk: walkSeconds(x.meters, DEFAULT_WALK) }));
		const access = near(od.from[0], od.from[1]);
		const egress = near(od.to[0], od.to[1]);
		const r = raptor(tt, { access: access.map((a) => ({ stop: a.stop, time: t0 + a.walk })), egress, maxRounds: 5 });
		return { name: od.name, from: od.from, to: od.to, t0, targetArrival: Array.from(r.targetArrival) };
	});
	writeFileSync(out.replace(/\.bin$/, '') + '.expected.json', JSON.stringify({ date, epochZero: tt.epochZero, expected }, null, 1));
}

main().catch((e) => { console.error(e); process.exit(1); });
