/**
 * RAPTOR (Delling, Pajor, Werneck 2012): round k finds the earliest arrival at every stop using at
 * most k vehicles. Each round scans every pattern touched by a stop improved in the round before,
 * then relaxes walking transfers from the stops it just improved. The result for the destination
 * is a Pareto set over (arrival time, number of vehicles) — one candidate per round that beats
 * every round before it.
 *
 * Kept deliberately plain: no McRAPTOR (no walking-distance criterion), no range queries. The
 * planner gets alternatives by re-running with a later departure instead.
 */
import type { Timetable } from './timetable.js';

export const INF = 0x3fffffff;

/** A pointer explaining how a stop got its label in a round. */
export const enum Via {
	None = 0,
	Access = 1,
	Transit = 2,
	Transfer = 3,
}

export interface RaptorQuery {
	/** Stops the traveller can reach on foot from the origin, with the time they get there. */
	access: { stop: number; time: number }[];
	/** Stops from which the destination is walkable, with the walk in seconds. */
	egress: { stop: number; walk: number }[];
	maxRounds?: number;
	/** Extra seconds needed to board after getting off another vehicle at the same stop. */
	minChangeSecs?: number;
	/** Extra seconds needed to board after a walking transfer: finding the stop, crossing. */
	walkTransferSlackSecs?: number;
	/** Trips that may not be used (cancelled in realtime). */
	bannedTrips?: Uint8Array;
	/**
	 * Per-trip-stop overrides of arr/dep, e.g. realtime. Same layout as the timetable's arrays.
	 * nonFifoPatterns must flag every pattern that is out of order or holds a banned trip.
	 */
	times?: { arr: Int32Array; dep: Int32Array; nonFifoPatterns: Uint8Array };
}

export interface RaptorResult {
	rounds: number;
	/** labels[k][s]: earliest arrival at s with at most k vehicles. */
	labels: Int32Array[];
	via: Uint8Array[];
	/** Transit: the trip. Transfer: the stop walked from. */
	viaA: Int32Array[];
	/** Transit: the position in the pattern the trip was boarded at. */
	viaB: Int32Array[];
	/** Best destination arrival per round, and the egress stop that gave it. */
	targetArrival: Int32Array;
	targetStop: Int32Array;
	stats: { patternsScanned: number; roundsRun: number };
}

export function raptor(tt: Timetable, q: RaptorQuery): RaptorResult {
	const K = q.maxRounds ?? 5;
	// Defaults chosen against Google Maps' Dublin results (docs/ROUTE_PLANNING.md): with no slack
	// we consistently found connections a few minutes tighter than anyone would plan to make.
	const minChange = q.minChangeSecs ?? 120;
	const walkSlack = q.walkTransferSlackSecs ?? 60;
	const n = tt.nStops;
	const arr = q.times?.arr ?? tt.arr;
	const dep = q.times?.dep ?? tt.dep;
	const nonFifo = q.times?.nonFifoPatterns;
	const banned = q.bannedTrips;

	const labels: Int32Array[] = [];
	const via: Uint8Array[] = [];
	const viaA: Int32Array[] = [];
	const viaB: Int32Array[] = [];
	const best = new Int32Array(n).fill(INF);
	const egressWalk = new Int32Array(n).fill(-1);
	for (const e of q.egress) {
		if (egressWalk[e.stop] < 0 || e.walk < egressWalk[e.stop]) egressWalk[e.stop] = e.walk;
	}
	const targetArrival = new Int32Array(K + 1).fill(INF);
	const targetStop = new Int32Array(K + 1).fill(-1);
	let targetBound = INF;

	// Round 0: walking from the origin.
	labels.push(new Int32Array(n).fill(INF));
	via.push(new Uint8Array(n));
	viaA.push(new Int32Array(n).fill(-1));
	viaB.push(new Int32Array(n).fill(-1));
	let marked = new Uint8Array(n);
	let anyMarked = false;
	for (const a of q.access) {
		if (a.time < labels[0][a.stop]) {
			labels[0][a.stop] = a.time;
			best[a.stop] = a.time;
			via[0][a.stop] = Via.Access;
			marked[a.stop] = 1;
			anyMarked = true;
		}
	}

	const queuePos = new Int32Array(tt.nPatterns).fill(-1);
	const queued: number[] = [];
	let patternsScanned = 0;
	let roundsRun = 0;

	for (let k = 1; k <= K && anyMarked; k++) {
		roundsRun = k;
		const prev = labels[k - 1];
		const prevVia = via[k - 1];
		const cur = prev.slice();
		const curVia = prevVia.slice();
		const curA = viaA[k - 1].slice();
		const curB = viaB[k - 1].slice();
		labels.push(cur);
		via.push(curVia);
		viaA.push(curA);
		viaB.push(curB);

		// Collect patterns through marked stops, each from its earliest marked position.
		queued.length = 0;
		for (let s = 0; s < n; s++) {
			if (!marked[s]) continue;
			for (let j = tt.stopPatternsStart[s]; j < tt.stopPatternsStart[s + 1]; j++) {
				const p = tt.stopPatterns[j];
				const pos = tt.stopPatternPos[j];
				if (queuePos[p] < 0) { queued.push(p); queuePos[p] = pos; }
				else if (pos < queuePos[p]) queuePos[p] = pos;
			}
		}
		const improved = new Uint8Array(n);
		let anyImproved = false;

		for (const p of queued) {
			patternsScanned++;
			const startPos = queuePos[p];
			queuePos[p] = -1;
			const ps = tt.patternStopsStart[p];
			const len = tt.patternStopsStart[p + 1] - ps;
			let trip = -1;
			let tripBase = 0;
			let boardPos = -1;
			for (let i = startPos; i < len; i++) {
				const s = tt.patternStops[ps + i];
				if (trip >= 0) {
					const a = arr[tripBase + i];
					if (a < best[s] && a < targetBound) {
						cur[s] = a;
						best[s] = a;
						curVia[s] = Via.Transit;
						curA[s] = trip;
						curB[s] = boardPos;
						improved[s] = 1;
						anyImproved = true;
						const w = egressWalk[s];
						if (w >= 0 && a + w < targetArrival[k]) {
							targetArrival[k] = a + w;
							targetStop[k] = s;
							if (a + w < targetBound) targetBound = a + w;
						}
					}
				}
				// Can an earlier trip be caught here, given how we could reach s last round?
				const reach = prev[s];
				if (reach >= INF) continue;
				const pv = prevVia[s];
				const ready = reach + (pv === Via.Transit ? minChange : pv === Via.Transfer ? walkSlack : 0);
				if (trip >= 0 && ready > dep[tripBase + i]) continue;
				const t = earliestTrip(tt, p, i, ready, dep, nonFifo?.[p] === 1, banned);
				if (t >= 0 && (trip < 0 || dep[tt.tripTimesStart[t] + i] < dep[tripBase + i])) {
					trip = t;
					tripBase = tt.tripTimesStart[t];
					boardPos = i;
				}
			}
		}

		// Walking transfers from stops improved by a vehicle this round. One hop only.
		const nextMarked = new Uint8Array(n);
		anyMarked = false;
		for (let s = 0; s < n; s++) {
			if (!improved[s]) continue;
			nextMarked[s] = 1;
			anyMarked = true;
			const base = cur[s];
			for (let j = tt.transfersStart[s]; j < tt.transfersStart[s + 1]; j++) {
				const s2 = tt.transferTo[j];
				const a = base + tt.transferSecs[j];
				if (a < best[s2] && a < targetBound) {
					cur[s2] = a;
					best[s2] = a;
					curVia[s2] = Via.Transfer;
					curA[s2] = s;
					curB[s2] = -1;
					nextMarked[s2] = 1;
					const w = egressWalk[s2];
					if (w >= 0 && a + w < targetArrival[k]) {
						targetArrival[k] = a + w;
						targetStop[k] = s2;
						if (a + w < targetBound) targetBound = a + w;
					}
				}
			}
		}
		marked = nextMarked;
		if (!anyImproved) break;
	}

	return { rounds: labels.length - 1, labels, via, viaA, viaB, targetArrival, targetStop, stats: { patternsScanned, roundsRun } };
}

/** Earliest trip of pattern [p] leaving its [pos]-th stop at or after [time]; -1 if none. */
function earliestTrip(
	tt: Timetable, p: number, pos: number, time: number, dep: Int32Array, linear: boolean, banned?: Uint8Array,
): number {
	const lo0 = tt.patternTripsStart[p];
	const hi0 = tt.patternTripsStart[p + 1];
	if (linear) {
		// Realtime can reorder trips, and a banned trip must be stepped over, so search the lot.
		// Only patterns flagged by the overlay come here; the rest keep the binary search.
		let bestT = -1;
		let bestDep = INF;
		for (let t = lo0; t < hi0; t++) {
			if (banned?.[t]) continue;
			const d = dep[tt.tripTimesStart[t] + pos];
			if (d >= time && d < bestDep) { bestDep = d; bestT = t; }
		}
		return bestT;
	}
	let lo = lo0, hi = hi0;
	while (lo < hi) {
		const mid = (lo + hi) >>> 1;
		if (dep[tt.tripTimesStart[mid] + pos] < time) lo = mid + 1;
		else hi = mid;
	}
	return lo < hi0 ? lo : -1;
}

export interface RawLeg {
	kind: 'access' | 'transit' | 'transfer' | 'egress';
	fromStop: number;
	toStop: number;
	trip?: number;
	boardPos?: number;
	alightPos?: number;
	/** Seconds, for walks. Transit legs leave these at -1: their times come from the trip. */
	start: number;
	end: number;
}

/** Walks the pointers back from the destination's best label in round [k]. */
export function extractJourney(tt: Timetable, r: RaptorResult, k: number, egressWalk: number): RawLeg[] | null {
	let s = r.targetStop[k];
	if (s < 0) return null;
	const legs: RawLeg[] = [];
	const arrivalAtStop = r.labels[k][s];
	legs.push({ kind: 'egress', fromStop: s, toStop: -1, start: arrivalAtStop, end: arrivalAtStop + egressWalk });
	let round = k;
	let guard = 0;
	while (guard++ < 64) {
		const v = r.via[round][s];
		if (v === Via.Access) {
			legs.push({ kind: 'access', fromStop: -1, toStop: s, start: -1, end: r.labels[round][s] });
			break;
		}
		if (v === Via.Transfer) {
			const from = r.viaA[round][s];
			legs.push({ kind: 'transfer', fromStop: from, toStop: s, start: r.labels[round][from], end: r.labels[round][s] });
			s = from;
			continue;
		}
		if (v === Via.Transit) {
			const trip = r.viaA[round][s];
			const boardPos = r.viaB[round][s];
			const p = tt.tripPattern[trip];
			const ps = tt.patternStopsStart[p];
			const len = tt.patternStopsStart[p + 1] - ps;
			// The alighting position: the first occurrence of s after boarding.
			let alightPos = -1;
			for (let i = boardPos + 1; i < len; i++) if (tt.patternStops[ps + i] === s) { alightPos = i; break; }
			if (alightPos < 0) return null;
			const boardStop = tt.patternStops[ps + boardPos];
			legs.push({ kind: 'transit', fromStop: boardStop, toStop: s, trip, boardPos, alightPos, start: -1, end: -1 });
			s = boardStop;
			round--;
			if (round < 0) return null;
			continue;
		}
		return null;
	}
	return legs.reverse();
}
