import type { GtfsStorage, Stop, Departure } from './types.js';
import { applyTripUpdates, selectTripUpdates, tripIdForKey, tripKey, tripKeyIndex, type TripUpdates } from './gtfs.js';
import { DEFAULT_LOOKBACK_S } from './snapshots.js';
import { serviceDate, serviceDayIndex, serviceHour, previousServiceDay } from './servicetime.js';

const DAY_NAMES = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

export interface DeparturesResult {
	stop: Stop;
	departures: Departure[];
	feed_timestamp: number | null;
	stale: boolean;
}

/**
 * For the scheduled trips the feed doesn't know by id, the feed's id for the same trip, matched
 * on route, direction, first departure and service date (see tripKey in gtfs.ts). Each trip is
 * tried on its candidate dates in order; the first that matches wins. Trips without a live update
 * at all (no realtime for that service yet) are looked up too, but each trip's fields are read
 * from the timetable once per process (getTripKeyFields caches them), so a busy stop costs one
 * small indexed query the first time and none after.
 */
export async function realtimeTripIdsFor(
	storage: GtfsStorage,
	updates: TripUpdates | null,
	trips: { trip_id: string; dates: string[] }[],
): Promise<Map<string, string>> {
	const out = new Map<string, string>();
	if (!updates || !tripKeyIndex(updates).size) return out;
	const unmatched = trips.filter((t) => !updates.has(t.trip_id));
	if (!unmatched.length) return out;
	const fields = await storage.getTripKeyFields(unmatched.map((t) => t.trip_id));
	for (const t of unmatched) {
		const f = fields.get(t.trip_id);
		if (!f) continue;
		for (const date of t.dates) {
			const rtId = tripIdForKey(updates, tripKey(f.route_id, f.direction_id, f.start_time, date));
			if (rtId) { out.set(t.trip_id, rtId); break; }
		}
	}
	return out;
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
	const yesterday = serviceHour(now) < 4 ? previousServiceDay(now) : null;
	const overnightServices = yesterday
		? await storage.getActiveServiceIds(DAY_NAMES[yesterday.dayIndex], yesterday.date)
		: null;

	const { updates, feedTimestamp, stale } = selectTripUpdates(feedsDir, atSec, atSec != null ? lookbackSec : Infinity);

	for (const code of codes) {
		const stop = await storage.getStopByCode(code);
		if (!stop) continue;

		let departures = await storage.getScheduledDepartures(stop.stop_id, activeServices, 105, 0, now);
		// The service date each departure runs on, which a renumbered trip is matched by.
		const dates: { trip_id: string; dates: string[] }[] = departures.map((d) => ({ trip_id: d.trip_id, dates: [today] }));

		if (overnightServices && yesterday) {
			const overnight = await storage.getScheduledDepartures(stop.stop_id, overnightServices, 105, 24, now);
			for (const d of overnight) dates.push({ trip_id: d.trip_id, dates: [yesterday.date] });
			departures = [...departures, ...overnight].sort((a, b) =>
				a.scheduled_departure.localeCompare(b.scheduled_departure)
			);
		}

		applyTripUpdates(departures, updates, await realtimeTripIdsFor(storage, updates, dates));
		out.set(code, { stop, departures, feed_timestamp: feedTimestamp, stale });
	}

	return out;
}
