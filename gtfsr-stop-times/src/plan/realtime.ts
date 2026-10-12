/**
 * Folds a GTFS-Realtime TripUpdates feed into a copy of the timetable's arr/dep arrays, so RAPTOR
 * plans on predicted times. Same propagation rule as the departure boards (src/gtfs.ts): a stop
 * takes the delay of the latest update at or before its stop_sequence, and stops before the first
 * update keep their schedule.
 */
import type { transit_realtime } from 'gtfs-realtime-bindings';
import type { Timetable } from './timetable.js';
import { addDays } from './sources.js';

export interface RealtimeOverlay {
	arr: Int32Array;
	dep: Int32Array;
	/** Patterns whose trips no longer leave in order once delays apply: search them linearly. */
	nonFifoPatterns: Uint8Array;
	/** Trips cancelled in the feed. */
	banned: Uint8Array;
	/** Per trip: the delay at its last updated stop, or null if the feed says nothing about it. */
	tripHasRealtime: Uint8Array;
	updatedTrips: number;
	cancelledTrips: number;
	feedTimestamp: number | null;
}

const CANCELED = 3;

const tripIndexCache = new WeakMap<Timetable, Map<string, number[]>>();

function tripIndexById(tt: Timetable): Map<string, number[]> {
	let m = tripIndexCache.get(tt);
	if (m) return m;
	m = new Map();
	for (let t = 0; t < tt.nTrips; t++) {
		const list = m.get(tt.tripId[t]);
		if (list) list.push(t);
		else m.set(tt.tripId[t], [t]);
	}
	tripIndexCache.set(tt, m);
	return m;
}

const overlayCache = new WeakMap<transit_realtime.FeedMessage, WeakMap<Timetable, RealtimeOverlay>>();

export function realtimeOverlay(tt: Timetable, feed: transit_realtime.FeedMessage): RealtimeOverlay {
	let perTt = overlayCache.get(feed);
	const hit = perTt?.get(tt);
	if (hit) return hit;

	const arr = tt.arr.slice();
	const dep = tt.dep.slice();
	const banned = new Uint8Array(tt.nTrips);
	const tripHasRealtime = new Uint8Array(tt.nTrips);
	const touchedPatterns = new Set<number>();
	const bannedPatterns = new Set<number>();
	const byId = tripIndexById(tt);
	let updatedTrips = 0;
	let cancelledTrips = 0;

	for (const entity of feed.entity) {
		const tu = entity.tripUpdate;
		const id = tu?.trip?.tripId;
		if (!tu || !id) continue;
		const candidates = byId.get(id);
		if (!candidates) continue;
		// start_date picks the instance when a trip runs on consecutive days; without it, today's.
		const startDate = tu.trip.startDate || null;
		const instances = candidates.filter((t) =>
			startDate ? addDays(tt.date, tt.tripDayShift[t]) === startDate : tt.tripDayShift[t] === 0,
		);
		if ((tu.trip.scheduleRelationship as unknown as number) === CANCELED) {
			for (const t of instances) {
				banned[t] = 1;
				cancelledTrips++;
				bannedPatterns.add(tt.tripPattern[t]);
			}
			continue;
		}
		const updates = (tu.stopTimeUpdate ?? [])
			.map((u) => ({
				seq: u.stopSequence ?? 0,
				arrDelay: u.arrival?.delay ?? u.departure?.delay ?? null,
				depDelay: u.departure?.delay ?? u.arrival?.delay ?? null,
			}))
			.filter((u) => u.arrDelay != null || u.depDelay != null)
			.sort((a, b) => a.seq - b.seq);
		if (!updates.length) continue;
		for (const t of instances) {
			const base = tt.tripTimesStart[t];
			const p = tt.tripPattern[t];
			const len = tt.patternStopsStart[p + 1] - tt.patternStopsStart[p];
			let u = -1;
			for (let i = 0; i < len; i++) {
				const seq = tt.stopSequence[base + i];
				while (u + 1 < updates.length && updates[u + 1].seq <= seq) u++;
				if (u < 0) continue;
				const up = updates[u];
				arr[base + i] = tt.arr[base + i] + (up.arrDelay ?? up.depDelay ?? 0);
				dep[base + i] = tt.dep[base + i] + (up.depDelay ?? up.arrDelay ?? 0);
				// A bus can't leave a stop before it gets there.
				if (dep[base + i] < arr[base + i]) dep[base + i] = arr[base + i];
			}
			tripHasRealtime[t] = 1;
			touchedPatterns.add(p);
			updatedTrips++;
		}
	}

	const nonFifoPatterns = new Uint8Array(tt.nPatterns);
	for (const p of bannedPatterns) nonFifoPatterns[p] = 1;
	for (const p of touchedPatterns) {
		const lo = tt.patternTripsStart[p];
		const hi = tt.patternTripsStart[p + 1];
		const len = tt.patternStopsStart[p + 1] - tt.patternStopsStart[p];
		outer: for (let t = lo + 1; t < hi; t++) {
			const a = tt.tripTimesStart[t - 1];
			const b = tt.tripTimesStart[t];
			for (let i = 0; i < len; i++) {
				if (dep[b + i] < dep[a + i]) { nonFifoPatterns[p] = 1; break outer; }
			}
		}
	}

	const overlay: RealtimeOverlay = {
		arr, dep, nonFifoPatterns, banned, tripHasRealtime, updatedTrips, cancelledTrips,
		feedTimestamp: feed.header?.timestamp != null ? Number(feed.header.timestamp) : null,
	};
	if (!perTt) overlayCache.set(feed, (perTt = new WeakMap()));
	perTt.set(tt, overlay);
	return overlay;
}
