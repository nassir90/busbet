import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { gunzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

const CACHE_TTL_MS      = 15_000;
const MIN_DIST_M        = 20;   // minimum displacement to compute a heading
const MAX_HEADING_AGE_S = 600;  // discard prev position if older than 10 min
const COS_DUBLIN        = Math.cos(53.34 * Math.PI / 180);

export interface VehiclePosition {
	trip_id:          string;
	route_short_name: string;
	lat:              number;
	lon:              number;
	bearing:          number | null;  // 0–360 clockwise from north, null if unknown
	delay_seconds:    number | null;
}

let feedCache: { feed: transit_realtime.FeedMessage; fetchedAt: number } | null = null;

// In-memory history for bearing derivation. Populated on every poll; persists
// across HTTP requests for the lifetime of the process.
const prevPos = new Map<string, { lat: number; lon: number; ts: number }>();

function distM(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const dlat = (lat1 - lat2) * 111320;
	const dlon = (lon1 - lon2) * 111320 * COS_DUBLIN;
	return Math.sqrt(dlat * dlat + dlon * dlon);
}

async function fetchVehiclesFeed(vehiclesDir: string): Promise<transit_realtime.FeedMessage | null> {
	const now = Date.now();
	if (feedCache && now - feedCache.fetchedAt < CACHE_TTL_MS) return feedCache.feed;
	try {
		const files = readdirSync(vehiclesDir).filter((f) => f.endsWith('.pb.gz')).sort();
		if (!files.length) { console.warn('[vehicles] no archive files in', vehiclesDir); return feedCache?.feed ?? null; }
		const raw = gunzipSync(readFileSync(join(vehiclesDir, files[files.length - 1])));
		const feed = FeedMessage.decode(new Uint8Array(raw));
		feedCache = { feed, fetchedAt: now };
		return feed;
	} catch (err) {
		console.warn('[vehicles] feed read error:', err);
		return feedCache?.feed ?? null;
	}
}

/** Given a feeds directory like `.../data/feeds`, derive `.../data/vehicles`. */
export function vehiclesDirFromFeedsDir(feedsDir: string): string {
	return join(dirname(feedsDir), 'vehicles');
}

/**
 * Returns all active vehicle positions for the given set of route short names,
 * with server-derived bearings. NTA's GTFS-R `bearing` field is always 0, so
 * bearing is computed here from consecutive position samples instead.
 *
 * Route matching strips the operator prefix from the feed's routeId
 * (e.g. "7-46A_00674" → short name "46A") using the same suffix approach as
 * extract-positions.ts.
 */
export async function getVehiclesForRoutes(
	routeNames: Set<string>,
	delayByTripId: Map<string, number | null>,
	vehiclesDir: string,
): Promise<VehiclePosition[]> {
	const feed = await fetchVehiclesFeed(vehiclesDir);
	if (!feed) return [];

	const now = Math.floor(Date.now() / 1000);
	const result: VehiclePosition[] = [];

	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.position) continue;
		const { latitude: lat, longitude: lon } = v.position;
		if (!lat || !lon || Math.abs(lat) < 1) continue;
		if (lat === 0 && lon === 0) continue;

		// Match route by stripping any operator prefix from the feed routeId.
		// NTA uses formats like "7-46A_00674"; the short name is the part before
		// the last numeric suffix, which we can recover via the DB name set.
		const feedRouteId = v.trip?.routeId ?? '';
		const routeName = resolveRouteName(feedRouteId, routeNames);
		if (!routeName) continue;

		const tripId = v.trip?.tripId ?? '';
		const ts = v.timestamp ? Number(v.timestamp) : now;

		let bearing: number | null = null;
		const prev = prevPos.get(tripId);
		if (prev && ts > prev.ts && (ts - prev.ts) <= MAX_HEADING_AGE_S) {
			const d = distM(lat, lon, prev.lat, prev.lon);
			if (d >= MIN_DIST_M) {
				const dlat = (lat - prev.lat) * 111320;
				const dlon = (lon - prev.lon) * 111320 * COS_DUBLIN;
				bearing = (Math.atan2(dlon, dlat) * 180 / Math.PI + 360) % 360;
			}
		}
		prevPos.set(tripId, { lat, lon, ts });

		result.push({
			trip_id:          tripId,
			route_short_name: routeName,
			lat,
			lon,
			bearing,
			delay_seconds:    delayByTripId.get(tripId) ?? null,
		});
	}

	return result;
}

/**
 * Resolve a feed routeId (e.g. "7-46A_00674", "GO_46A", "46A") to one of the
 * known route short names. Tries exact match first, then strips prefixes.
 */
function resolveRouteName(feedRouteId: string, knownRoutes: Set<string>): string | null {
	if (knownRoutes.has(feedRouteId)) return feedRouteId;
	// Strip leading operator prefix: "7-46A_00674" → try parts split by "-" and "_"
	for (const sep of ['-', '_']) {
		const parts = feedRouteId.split(sep);
		for (let i = 1; i < parts.length; i++) {
			const candidate = parts.slice(i).join(sep);
			if (knownRoutes.has(candidate)) return candidate;
		}
	}
	return null;
}
