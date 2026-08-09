import type { transit_realtime } from 'gtfs-realtime-bindings';
import type { Departure, TripDetail } from './types.js';
import { selectAt, decodeFeed, DEFAULT_LOOKBACK_S } from './snapshots.js';

const DEFAULT_FEEDS_DIR = process.env.GTFS_FEEDS_DIR ?? '/home/lab/Projects/busbet/gtfsr-collector/data/feeds';

export interface FeedSelection {
	feed: transit_realtime.FeedMessage | null;  // null when stale / no snapshot at-or-before the instant
	feedTimestamp: number | null;               // header.timestamp (epoch s) of the chosen snapshot
	stale: boolean;
}

/**
 * Prometheus-style instant selection of a trip-updates snapshot.
 * @param atSec      target instant (epoch s); defaults to now
 * @param lookbackSec staleness window; pass Infinity for "latest, regardless of age"
 */
export function selectFeed(feedsDir: string | undefined, atSec?: number, lookbackSec = DEFAULT_LOOKBACK_S): FeedSelection {
	const dir = feedsDir ?? DEFAULT_FEEDS_DIR;
	const at = atSec ?? Math.floor(Date.now() / 1000);
	const sel = selectAt(dir, at, lookbackSec);
	if (!sel.cur) return { feed: null, feedTimestamp: null, stale: true };
	if (sel.stale) return { feed: null, feedTimestamp: sel.cur.ts, stale: true };
	return { feed: decodeFeed(dir, sel.cur.file), feedTimestamp: sel.cur.ts, stale: false };
}

/** Latest available trip-updates feed (no staleness limit). */
export function fetchFeed(feedsDir?: string): transit_realtime.FeedMessage | null {
	return selectFeed(feedsDir, undefined, Infinity).feed;
}

type StopUpdate = { stopSequence: number; delay: number };

/**
 * Memoised per decoded feed. Building this map is a full walk over every entity in the NTA feed,
 * and it used to run once per stop lookup — so a batch of ten favourites walked the feed ten
 * times, and every single-stop poll from every client walked it again. `decodeFeed` LRU-caches
 * the FeedMessage, so object identity is stable across requests and a WeakMap keyed on it hits
 * for as long as that snapshot stays current, then collects itself when the feed rolls over.
 */
const tripUpdatesCache = new WeakMap<transit_realtime.FeedMessage, Map<string, StopUpdate[]>>();

function buildTripUpdates(feed: transit_realtime.FeedMessage): Map<string, StopUpdate[]> {
	const cached = tripUpdatesCache.get(feed);
	if (cached) return cached;
	const map = new Map<string, StopUpdate[]>();
	for (const entity of feed.entity) {
		const tu = entity.tripUpdate;
		if (!tu?.trip?.tripId) continue;
		const updates: StopUpdate[] = [];
		for (const stu of tu.stopTimeUpdate ?? []) {
			const delay = stu.departure?.delay ?? stu.arrival?.delay;
			if (delay != null) updates.push({ stopSequence: stu.stopSequence ?? 0, delay });
		}
		if (updates.length) {
			map.set(tu.trip.tripId, updates.sort((a, b) => a.stopSequence - b.stopSequence));
		}
	}
	tripUpdatesCache.set(feed, map);
	return map;
}

function addSeconds(timeHHMM: string, seconds: number): string {
	const [h, m] = timeHHMM.split(':').map(Number);
	const totalMins = h * 60 + m + Math.round(seconds / 60);
	const rh = Math.max(0, Math.floor(totalMins / 60)) % 24;
	const rm = ((totalMins % 60) + 60) % 60;
	return `${String(rh).padStart(2, '0')}:${String(rm).padStart(2, '0')}`;
}

export function applyRealtimeDelaysToTrip(detail: TripDetail, feed: transit_realtime.FeedMessage | null): TripDetail {
	if (!feed) return detail;
	const tripUpdates = buildTripUpdates(feed);
	const updates = tripUpdates.get(detail.trip_id);
	if (!updates) return detail;

	for (const stop of detail.stops) {
		let delay: number | null = null;
		for (const u of updates) {
			if (u.stopSequence <= stop.stop_sequence) delay = u.delay;
			else break;
		}
		if (delay != null) {
			stop.delay_seconds = delay;
			stop.estimated_arrival = addSeconds(stop.scheduled_arrival, delay);
			stop.estimated_departure = addSeconds(stop.scheduled_departure, delay);
			stop.realtime = true;
		}
	}
	return detail;
}

export function applyRealtimeDelays(departures: Departure[], feed: transit_realtime.FeedMessage | null): Departure[] {
	if (departures.length === 0 || !feed) return departures;
	const tripUpdates = buildTripUpdates(feed);
	for (const dep of departures) {
		const updates = tripUpdates.get(dep.trip_id);
		if (!updates) continue;
		let delay: number | null = null;
		for (const u of updates) {
			if (u.stopSequence <= dep.stop_sequence) delay = u.delay;
			else break;
		}
		if (delay != null) {
			dep.delay_seconds = delay;
			dep.estimated_departure = addSeconds(dep.scheduled_departure, delay);
			dep.realtime = true;
		}
	}
	return departures;
}
