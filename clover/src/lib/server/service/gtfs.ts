/**
 * GTFS-Realtime service — fetches from the playback microservice.
 */
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import type { Departure } from '../storage/types.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const PLAYBACK_URL = process.env.PLAYBACK_URL ?? 'http://localhost:3456';
const CACHE_TTL_MS = 5_000; // shorter TTL since playback can advance fast

let cache: { feed: transit_realtime.FeedMessage; fetchedAt: number } | null = null;

export async function fetchFeed(): Promise<transit_realtime.FeedMessage | null> {
	const now = Date.now();
	if (cache && now - cache.fetchedAt < CACHE_TTL_MS) return cache.feed;
	try {
		const res = await fetch(`${PLAYBACK_URL}/api/feed/trip-updates`);
		if (!res.ok) {
			console.warn(`[gtfs] playback feed fetch failed: ${res.status}`);
			return cache?.feed ?? null;
		}
		const feed = FeedMessage.decode(new Uint8Array(await res.arrayBuffer()));
		cache = { feed, fetchedAt: now };
		return feed;
	} catch (err) {
		console.warn('[gtfs] playback feed fetch error:', err);
		return cache?.feed ?? null;
	}
}

/** Get the current virtual time from the playback server */
export async function getPlaybackTime(): Promise<{ virtualTime: number; speed: number; paused: boolean } | null> {
	try {
		const res = await fetch(`${PLAYBACK_URL}/api/control/time`);
		if (!res.ok) return null;
		return await res.json();
	} catch {
		return null;
	}
}

/** Get the available time range from the playback server */
export async function getPlaybackRange(): Promise<{ earliest: number; latest: number } | null> {
	try {
		const res = await fetch(`${PLAYBACK_URL}/api/control/range`);
		if (!res.ok) return null;
		return await res.json();
	} catch {
		return null;
	}
}

/** Set playback time/speed */
export async function setPlaybackTime(opts: { time?: number; speed?: number }): Promise<void> {
	try {
		await fetch(`${PLAYBACK_URL}/api/control/time`, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(opts)
		});
	} catch (err) {
		console.warn('[gtfs] playback control error:', err);
	}
}

function buildDelayMap(feed: transit_realtime.FeedMessage): Map<string, number> {
	const map = new Map<string, number>();
	for (const entity of feed.entity) {
		const tu = entity.tripUpdate;
		if (!tu?.trip?.tripId) continue;
		for (const stu of tu.stopTimeUpdate ?? []) {
			const delay = stu.departure?.delay ?? stu.arrival?.delay;
			if (delay != null) {
				map.set(tu.trip.tripId, delay);
				break;
			}
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

export async function applyRealtimeDelays(departures: Departure[]): Promise<Departure[]> {
	if (departures.length === 0) return departures;
	const feed = await fetchFeed();
	if (!feed) return departures;
	const delayMap = buildDelayMap(feed);
	for (const dep of departures) {
		const delaySecs = delayMap.get(dep.trip_id);
		if (delaySecs != null) {
			dep.delay_seconds = delaySecs;
			dep.estimated_departure = addSeconds(dep.scheduled_departure, delaySecs);
			dep.realtime = true;
		}
	}
	return departures;
}
