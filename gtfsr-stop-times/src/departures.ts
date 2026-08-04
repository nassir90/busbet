import type { GtfsStorage, Stop, Departure } from './types.js';
import { applyRealtimeDelays, selectFeed } from './gtfs.js';
import { DEFAULT_LOOKBACK_S } from './snapshots.js';
import { serviceDate, serviceDayIndex, serviceHour, previousServiceDay } from './servicetime.js';

const DAY_NAMES = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

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
	const batch = await getDeparturesBatch([code], storage, feedsDir, atSec, lookbackSec);
	return batch.get(code) ?? null;
}

/**
 * Departures for several stops at one instant.
 *
 * The point isn't only saving round trips for the client — the per-instant work is genuinely
 * shared. The active service-id lookup and the realtime feed selection depend on the queried
 * time, not the stop, so N single-stop calls repeated both N times. Here they run once and every
 * stop reuses them.
 *
 * Stops that don't exist are simply absent from the returned map; a bad code in the list must not
 * fail the whole batch, since the caller's other stops are perfectly valid.
 */
export async function getDeparturesBatch(
	codes: string[],
	storage: GtfsStorage,
	feedsDir?: string,
	atSec?: number,
	lookbackSec = DEFAULT_LOOKBACK_S
): Promise<Map<string, DeparturesResult>> {
	const out = new Map<string, DeparturesResult>();
	if (!codes.length) return out;

	// "now" is the queried instant (defaults to actual now), so the schedule
	// window and active service day stay consistent for historical queries.
	const now = atSec != null ? new Date(atSec * 1000) : new Date();
	const today = serviceDate(now);
	const todayName = DAY_NAMES[serviceDayIndex(now)];
	const activeServices = await storage.getActiveServiceIds(todayName, today);

	// Overnight services roll over from the previous day; resolved once for the whole batch.
	const overnightServices = serviceHour(now) < 4
		? await (async () => {
			const yesterday = previousServiceDay(now);
			return storage.getActiveServiceIds(DAY_NAMES[yesterday.dayIndex], yesterday.date);
		})()
		: null;

	const { feed, feedTimestamp, stale } = selectFeed(feedsDir, atSec, atSec != null ? lookbackSec : Infinity);

	for (const code of codes) {
		const stop = await storage.getStopByCode(code);
		if (!stop) continue;

		let departures = await storage.getScheduledDepartures(stop.stop_id, activeServices, 105, 0, now);

		if (overnightServices) {
			const overnight = await storage.getScheduledDepartures(stop.stop_id, overnightServices, 105, 24, now);
			departures = [...departures, ...overnight].sort((a, b) =>
				a.scheduled_departure.localeCompare(b.scheduled_departure)
			);
		}

		applyRealtimeDelays(departures, feed);
		out.set(code, { stop, departures, feed_timestamp: feedTimestamp, stale });
	}

	return out;
}
