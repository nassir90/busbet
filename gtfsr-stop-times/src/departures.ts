import type { GtfsStorage, Stop, Departure } from './types.js';
import { applyRealtimeDelays, selectFeed } from './gtfs.js';
import { DEFAULT_LOOKBACK_S } from './snapshots.js';

const DAY_NAMES = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

function gtfsDate(d: Date): string {
	return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

export interface DeparturesResult {
	stop: Stop;
	departures: Departure[];
	feed_timestamp: number | null;
	stale: boolean;
}

export async function getDepartures(
	code: string,
	storage: GtfsStorage,
	feedsDir?: string,
	atSec?: number,
	lookbackSec = DEFAULT_LOOKBACK_S
): Promise<DeparturesResult | null> {
	const stop = await storage.getStopByCode(code);
	if (!stop) return null;

	// "now" is the queried instant (defaults to actual now), so the schedule
	// window and active service day stay consistent for historical queries.
	const now = atSec != null ? new Date(atSec * 1000) : new Date();
	const today = gtfsDate(now);
	const todayName = DAY_NAMES[now.getDay()];
	const activeServices = await storage.getActiveServiceIds(todayName, today);

	let departures = await storage.getScheduledDepartures(stop.stop_id, activeServices, 105, 0, now);

	if (now.getHours() < 4) {
		const yesterday = new Date(now);
		yesterday.setDate(yesterday.getDate() - 1);
		const yesterdayServices = await storage.getActiveServiceIds(
			DAY_NAMES[yesterday.getDay()],
			gtfsDate(yesterday)
		);
		const overnight = await storage.getScheduledDepartures(stop.stop_id, yesterdayServices, 105, 24, now);
		departures = [...departures, ...overnight].sort((a, b) =>
			a.scheduled_departure.localeCompare(b.scheduled_departure)
		);
	}

	const { feed, feedTimestamp, stale } = selectFeed(feedsDir, atSec, atSec != null ? lookbackSec : Infinity);
	applyRealtimeDelays(departures, feed);
	return { stop, departures, feed_timestamp: feedTimestamp, stale };
}
