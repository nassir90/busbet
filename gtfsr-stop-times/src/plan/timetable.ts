/**
 * One service day of the timetable, flattened into typed arrays for RAPTOR.
 *
 * RAPTOR works on "routes" in its own sense: every trip in one shares the exact same stop
 * sequence and no trip overtakes another. GTFS routes don't promise either (a 39A short working
 * skips the outer stops), so trips are regrouped here into *patterns*, and a pattern is split
 * again wherever one trip would overtake another.
 *
 * Times are seconds from "noon minus 12 hours" on the service date, which is what GTFS times are
 * measured from (it is local midnight except on the two daylight-saving change nights). Trips of
 * the previous service day that run past midnight (25:10 and the like) are included shifted back
 * a day, and the next day's earliest trips shifted forward, so a query at 00:30 or 23:30 finds
 * what is actually running.
 */
import { GridIndex, haversineMeters, walkSeconds, type WalkModel, DEFAULT_WALK } from './geo.js';
import { addDays, type TimetableSource, type TripRow } from './sources.js';

export interface Timetable {
	/** `YYYYMMDD` service date. */
	date: string;
	/** Epoch seconds of noon minus 12 h on [date] in the service zone: time 0 of this table. */
	epochZero: number;
	builtAt: number;
	buildMs: number;

	nStops: number;
	stopId: string[];
	stopCode: string[];
	stopName: string[];
	stopLat: Float64Array;
	stopLon: Float64Array;
	stopIndex: Map<string, number>;
	grid: GridIndex;

	nPatterns: number;
	/** patternStops[patternStopsStart[p] .. patternStopsStart[p+1]) are p's stops, in order. */
	patternStopsStart: Int32Array;
	patternStops: Int32Array;
	/** Trips of pattern p, sorted by departure: tripOrder[patternTripsStart[p] .. +1). */
	patternTripsStart: Int32Array;
	patternRoute: Int32Array;

	nTrips: number;
	tripPattern: Int32Array;
	/** arr/dep of trip t at its i-th stop are at [tripTimesStart[t] + i]. */
	tripTimesStart: Int32Array;
	arr: Int32Array;
	dep: Int32Array;
	tripId: string[];
	tripHeadsign: string[];
	tripShapeId: string[];
	/** Whole days the trip instance is shifted by (-1 = yesterday's late trip, +1 = tomorrow's early one). */
	tripDayShift: Int8Array;
	/** GTFS stop_sequence of trip t's i-th stop, for matching realtime updates. */
	stopSequence: Int32Array;

	nRoutes: number;
	routeId: string[];
	routeShortName: string[];
	routeLongName: string[];
	routeType: Int32Array;
	routeAgency: string[];

	/** For stop s: the (pattern, position) pairs that serve it. */
	stopPatternsStart: Int32Array;
	stopPatterns: Int32Array;
	stopPatternPos: Int32Array;

	/** Walking transfers between nearby stops: to and seconds, for each stop. */
	transfersStart: Int32Array;
	transferTo: Int32Array;
	transferSecs: Int32Array;
}

export interface BuildOptions {
	/** Straight-line radius for stop-to-stop walking transfers. */
	transferRadiusM?: number;
	walk?: WalkModel;
	/** Keep the next day's trips that leave before this many seconds after midnight. */
	nextDayHorizonS?: number;
	/** GTFS route_types to keep; undefined keeps everything. */
	routeTypes?: Set<number>;
}

export function parseGtfsTime(t: string): number {
	// "HH:MM:SS", HH may exceed 23. Hand-rolled: this runs once per stop_time.
	const a = t.indexOf(':');
	const b = t.indexOf(':', a + 1);
	return +t.slice(0, a) * 3600 + +t.slice(a + 1, b) * 60 + +t.slice(b + 1);
}

/** Epoch seconds of noon minus 12 h on a `YYYYMMDD` date in [timeZone]. */
export function epochZeroOf(date: string, timeZone: string): number {
	const y = +date.slice(0, 4), m = +date.slice(4, 6), d = +date.slice(6, 8);
	const guess = Date.UTC(y, m - 1, d, 12) / 1000;
	return guess - offsetSeconds(guess, timeZone) - 12 * 3600;
}

/** The zone's UTC offset in seconds at an instant. */
export function offsetSeconds(epochSec: number, timeZone: string): number {
	const parts = new Intl.DateTimeFormat('en-US', {
		timeZone,
		hourCycle: 'h23',
		year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', second: 'numeric',
	}).formatToParts(new Date(epochSec * 1000));
	const g = (t: string) => +(parts.find((p) => p.type === t)?.value ?? 0);
	const asUtc = Date.UTC(g('year'), g('month') - 1, g('day'), g('hour'), g('minute'), g('second')) / 1000;
	return asUtc - epochSec;
}

/** The `YYYYMMDD` service date an instant falls on in [timeZone]. */
export function serviceDateOf(epochSec: number, timeZone: string): string {
	const local = new Date((epochSec + offsetSeconds(epochSec, timeZone)) * 1000);
	return `${local.getUTCFullYear()}${String(local.getUTCMonth() + 1).padStart(2, '0')}${String(local.getUTCDate()).padStart(2, '0')}`;
}

interface Instance {
	trip: TripRow;
	shift: number; // days
	stops: Int32Array;
	seqs: Int32Array;
	arr: Int32Array;
	dep: Int32Array;
}

export async function buildTimetable(
	source: TimetableSource,
	date: string,
	timeZone = 'Europe/Dublin',
	opts: BuildOptions = {},
): Promise<Timetable> {
	const started = performance.now();
	const transferRadiusM = opts.transferRadiusM ?? 400;
	const walk = opts.walk ?? DEFAULT_WALK;
	const nextDayHorizonS = opts.nextDayHorizonS ?? 6 * 3600;

	// ---- stops & routes
	const stopRows = await source.stops();
	const stopIndex = new Map<string, number>();
	const nStops = stopRows.length;
	const stopLat = new Float64Array(nStops);
	const stopLon = new Float64Array(nStops);
	stopRows.forEach((s, i) => {
		stopIndex.set(s.stop_id, i);
		stopLat[i] = s.stop_lat;
		stopLon[i] = s.stop_lon;
	});

	const routeRows = (await source.routes()).filter((r) => !opts.routeTypes || opts.routeTypes.has(r.route_type));
	const routeIndex = new Map<string, number>();
	routeRows.forEach((r, i) => routeIndex.set(r.route_id, i));

	// ---- which trips run: today, yesterday's past-midnight tail, tomorrow's early start
	const days = [
		{ date: addDays(date, -1), shift: -1 },
		{ date, shift: 0 },
		{ date: addDays(date, 1), shift: 1 },
	];
	const tripsByDay: { shift: number; trips: TripRow[] }[] = [];
	const wanted = new Set<string>();
	for (const d of days) {
		const ids = await source.activeServiceIds(d.date);
		const trips = (await source.trips(ids)).filter((t) => routeIndex.has(t.route_id));
		tripsByDay.push({ shift: d.shift, trips });
		for (const t of trips) wanted.add(t.trip_id);
	}

	// ---- stop_times, grouped per trip
	const raw = new Map<string, { stop: number; seq: number; arr: number; dep: number }[]>();
	await source.stopTimes(wanted, (r) => {
		const stop = stopIndex.get(r.stop_id);
		if (stop === undefined) return;
		let list = raw.get(r.trip_id);
		if (!list) raw.set(r.trip_id, (list = []));
		list.push({
			stop,
			seq: r.stop_sequence,
			arr: parseGtfsTime(r.arrival_time || r.departure_time),
			dep: parseGtfsTime(r.departure_time || r.arrival_time),
		});
	});

	const compiled = new Map<string, Omit<Instance, 'trip' | 'shift'>>();
	for (const [id, list] of raw) {
		if (list.length < 2) continue;
		list.sort((a, b) => a.seq - b.seq);
		compiled.set(id, {
			stops: Int32Array.from(list, (x) => x.stop),
			seqs: Int32Array.from(list, (x) => x.seq),
			arr: Int32Array.from(list, (x) => x.arr),
			dep: Int32Array.from(list, (x) => x.dep),
		});
	}
	raw.clear();

	const instances: Instance[] = [];
	for (const { shift, trips } of tripsByDay) {
		for (const trip of trips) {
			const c = compiled.get(trip.trip_id);
			if (!c) continue;
			const offset = shift * 86400;
			if (shift === -1 && c.arr[c.arr.length - 1] + offset <= 0) continue;
			if (shift === 1 && c.dep[0] + offset >= 86400 + nextDayHorizonS) continue;
			if (offset === 0) instances.push({ trip, shift, ...c });
			else instances.push({
				trip, shift, stops: c.stops, seqs: c.seqs,
				arr: c.arr.map((x) => x + offset), dep: c.dep.map((x) => x + offset),
			});
		}
	}

	// ---- patterns: same route + same stop sequence, then split wherever FIFO would break
	const groups = new Map<string, Instance[]>();
	for (const inst of instances) {
		const key = `${inst.trip.route_id}|${inst.stops.join(',')}`;
		let g = groups.get(key);
		if (!g) groups.set(key, (g = []));
		g.push(inst);
	}
	const patterns: Instance[][] = [];
	for (const g of groups.values()) {
		g.sort((a, b) => a.dep[0] - b.dep[0]);
		const subs: Instance[][] = [];
		for (const inst of g) {
			let placed = false;
			for (const sub of subs) {
				const last = sub[sub.length - 1];
				let fifo = true;
				for (let i = 0; i < inst.stops.length; i++) {
					if (inst.arr[i] < last.arr[i] || inst.dep[i] < last.dep[i]) { fifo = false; break; }
				}
				if (fifo) { sub.push(inst); placed = true; break; }
			}
			if (!placed) subs.push([inst]);
		}
		patterns.push(...subs);
	}

	// ---- flatten
	const nPatterns = patterns.length;
	const nTrips = instances.length;
	const patternStopsStart = new Int32Array(nPatterns + 1);
	const patternTripsStart = new Int32Array(nPatterns + 1);
	let totalPatternStops = 0;
	let totalTimes = 0;
	for (let p = 0; p < nPatterns; p++) {
		patternStopsStart[p] = totalPatternStops;
		totalPatternStops += patterns[p][0].stops.length;
		totalTimes += patterns[p].length * patterns[p][0].stops.length;
	}
	patternStopsStart[nPatterns] = totalPatternStops;

	const patternStops = new Int32Array(totalPatternStops);
	const patternRoute = new Int32Array(nPatterns);
	const tripPattern = new Int32Array(nTrips);
	const tripTimesStart = new Int32Array(nTrips);
	const arr = new Int32Array(totalTimes);
	const dep = new Int32Array(totalTimes);
	const stopSequence = new Int32Array(totalTimes);
	const tripId: string[] = new Array(nTrips);
	const tripHeadsign: string[] = new Array(nTrips);
	const tripShapeId: string[] = new Array(nTrips);
	const tripDayShift = new Int8Array(nTrips);

	let t = 0;
	let timeCursor = 0;
	for (let p = 0; p < nPatterns; p++) {
		const pat = patterns[p];
		patternStops.set(pat[0].stops, patternStopsStart[p]);
		patternRoute[p] = routeIndex.get(pat[0].trip.route_id)!;
		patternTripsStart[p] = t;
		for (const inst of pat) {
			tripPattern[t] = p;
			tripTimesStart[t] = timeCursor;
			arr.set(inst.arr, timeCursor);
			dep.set(inst.dep, timeCursor);
			stopSequence.set(inst.seqs, timeCursor);
			timeCursor += inst.stops.length;
			tripId[t] = inst.trip.trip_id;
			tripHeadsign[t] = inst.trip.trip_headsign;
			tripShapeId[t] = inst.trip.shape_id;
			tripDayShift[t] = inst.shift;
			t++;
		}
	}
	patternTripsStart[nPatterns] = t;

	// ---- stop -> (pattern, position)
	const counts = new Int32Array(nStops + 1);
	for (let i = 0; i < totalPatternStops; i++) counts[patternStops[i] + 1]++;
	for (let s = 0; s < nStops; s++) counts[s + 1] += counts[s];
	const stopPatternsStart = counts.slice();
	const fill = counts.slice(0, nStops);
	const stopPatterns = new Int32Array(totalPatternStops);
	const stopPatternPos = new Int32Array(totalPatternStops);
	for (let p = 0; p < nPatterns; p++) {
		for (let i = patternStopsStart[p]; i < patternStopsStart[p + 1]; i++) {
			const s = patternStops[i];
			stopPatterns[fill[s]] = p;
			stopPatternPos[fill[s]] = i - patternStopsStart[p];
			fill[s]++;
		}
	}

	// ---- walking transfers between served stops
	const grid = new GridIndex(stopLat, stopLon, 500);
	const served = new Uint8Array(nStops);
	for (let i = 0; i < totalPatternStops; i++) served[patternStops[i]] = 1;
	const tStart = new Int32Array(nStops + 1);
	const tTo: number[] = [];
	const tSecs: number[] = [];
	for (let s = 0; s < nStops; s++) {
		tStart[s] = tTo.length;
		if (!served[s]) continue;
		for (const { index, meters } of grid.within(stopLat[s], stopLon[s], transferRadiusM)) {
			if (index === s || !served[index]) continue;
			tTo.push(index);
			tSecs.push(walkSeconds(meters, walk));
		}
	}
	tStart[nStops] = tTo.length;

	return {
		date,
		epochZero: epochZeroOf(date, timeZone),
		builtAt: Date.now(),
		buildMs: Math.round(performance.now() - started),
		nStops,
		stopId: stopRows.map((s) => s.stop_id),
		stopCode: stopRows.map((s) => s.stop_code ?? ''),
		stopName: stopRows.map((s) => s.stop_name ?? ''),
		stopLat,
		stopLon,
		stopIndex,
		grid,
		nPatterns,
		patternStopsStart,
		patternStops,
		patternTripsStart,
		patternRoute,
		nTrips,
		tripPattern,
		tripTimesStart,
		arr,
		dep,
		tripId,
		tripHeadsign,
		tripShapeId,
		tripDayShift,
		stopSequence,
		nRoutes: routeRows.length,
		routeId: routeRows.map((r) => r.route_id),
		routeShortName: routeRows.map((r) => r.route_short_name ?? ''),
		routeLongName: routeRows.map((r) => r.route_long_name ?? ''),
		routeType: Int32Array.from(routeRows, (r) => r.route_type),
		routeAgency: routeRows.map((r) => r.agency_id ?? ''),
		stopPatternsStart,
		stopPatterns,
		stopPatternPos,
		transfersStart: tStart,
		transferTo: Int32Array.from(tTo),
		transferSecs: Int32Array.from(tSecs),
	};
}

/** Rough heap footprint of the typed arrays, for the benchmarks. Strings are not counted. */
export function typedBytes(tt: Timetable): number {
	let n = 0;
	for (const v of Object.values(tt)) if (ArrayBuffer.isView(v)) n += (v as ArrayBufferView).byteLength;
	return n;
}

export { haversineMeters };
