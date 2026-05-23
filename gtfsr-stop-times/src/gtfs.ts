import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import type { Departure, TripDetail } from './types.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const DEFAULT_FEEDS_DIR = process.env.GTFS_FEEDS_DIR ?? '/home/lab/Projects/busbet/gtfsr-collector/data/feeds';
const CACHE_TTL_MS = 15_000;

let cache: { feed: transit_realtime.FeedMessage; fetchedAt: number } | null = null;

export async function fetchFeed(feedsDir?: string): Promise<transit_realtime.FeedMessage | null> {
	const dir = feedsDir ?? DEFAULT_FEEDS_DIR;
	const now = Date.now();
	if (cache && now - cache.fetchedAt < CACHE_TTL_MS) return cache.feed;
	try {
		const files = readdirSync(dir).filter((f) => f.endsWith('.pb.gz')).sort();
		if (!files.length) {
			console.warn('[gtfs] no feed files in', dir);
			return cache?.feed ?? null;
		}
		const raw = gunzipSync(readFileSync(join(dir, files[files.length - 1])));
		const feed = FeedMessage.decode(new Uint8Array(raw));
		cache = { feed, fetchedAt: now };
		return feed;
	} catch (err) {
		console.warn('[gtfs] feed read error:', err);
		return cache?.feed ?? null;
	}
}

type StopUpdate = { stopSequence: number; delay: number };

function buildTripUpdates(feed: transit_realtime.FeedMessage): Map<string, StopUpdate[]> {
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
	return map;
}

function addSeconds(timeHHMM: string, seconds: number): string {
	const [h, m] = timeHHMM.split(':').map(Number);
	const totalMins = h * 60 + m + Math.round(seconds / 60);
	const rh = Math.max(0, Math.floor(totalMins / 60)) % 24;
	const rm = ((totalMins % 60) + 60) % 60;
	return `${String(rh).padStart(2, '0')}:${String(rm).padStart(2, '0')}`;
}

export async function applyRealtimeDelaysToTrip(detail: TripDetail, feedsDir?: string): Promise<TripDetail> {
	const feed = await fetchFeed(feedsDir);
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

export async function applyRealtimeDelays(departures: Departure[], feedsDir?: string): Promise<Departure[]> {
	if (departures.length === 0) return departures;
	const feed = await fetchFeed(feedsDir);
	if (!feed) return departures;
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
