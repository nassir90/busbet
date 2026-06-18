import { join, dirname } from 'node:path';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import { selectAt, decodeFeed, DEFAULT_LOOKBACK_S } from './snapshots.js';

const MIN_DIST_M = 20;   // minimum displacement to compute a heading
const COS_DUBLIN = Math.cos(53.34 * Math.PI / 180);

export interface VehiclePosition {
	trip_id:          string;
	route_short_name: string;
	lat:              number;
	lon:              number;
	bearing:          number | null;  // 0–360 clockwise from north, null if unknown
	delay_seconds:    number | null;
}

export interface VehiclesResult {
	positions:     VehiclePosition[];
	feedTimestamp: number | null;
	stale:         boolean;
}

function distM(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const dlat = (lat1 - lat2) * 111320;
	const dlon = (lon1 - lon2) * 111320 * COS_DUBLIN;
	return Math.sqrt(dlat * dlat + dlon * dlon);
}

/** Given a feeds directory like `.../data/feeds`, derive `.../data/vehicles`. */
export function vehiclesDirFromFeedsDir(feedsDir: string): string {
	return join(dirname(feedsDir), 'vehicles');
}

/** Builds a tripId → position lookup from a decoded vehicles feed. */
function positionsByTrip(feed: transit_realtime.FeedMessage): Map<string, { lat: number; lon: number }> {
	const map = new Map<string, { lat: number; lon: number }>();
	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.position) continue;
		const { latitude: lat, longitude: lon } = v.position;
		if (!lat || !lon || Math.abs(lat) < 1 || (lat === 0 && lon === 0)) continue;
		const tripId = v.trip?.tripId ?? '';
		if (tripId) map.set(tripId, { lat, lon });
	}
	return map;
}

/**
 * Vehicle positions for the given trip IDs at the queried instant (defaults to now),
 * with server-derived bearings. NTA's GTFS-R `bearing` field is always 0, so we
 * compute it from the displacement between the selected snapshot and the one before
 * it — which works for historical instants, not just the live tail.
 */
export function getVehiclesForTrips(
	tripIds: Map<string, { route: string; delay: number | null }>,
	vehiclesDir: string,
	atSec?: number,
	lookbackSec = DEFAULT_LOOKBACK_S,
): VehiclesResult {
	const at = atSec ?? Math.floor(Date.now() / 1000);
	const sel = selectAt(vehiclesDir, at, atSec != null ? lookbackSec : Infinity);
	if (!sel.cur || sel.stale) {
		return { positions: [], feedTimestamp: sel.cur?.ts ?? null, stale: true };
	}

	const feed = decodeFeed(vehiclesDir, sel.cur.file);
	const prevMap = sel.prev ? positionsByTrip(decodeFeed(vehiclesDir, sel.prev.file)) : null;
	const result: VehiclePosition[] = [];

	for (const entity of feed.entity) {
		const v = entity.vehicle;
		if (!v?.position) continue;
		const { latitude: lat, longitude: lon } = v.position;
		if (!lat || !lon || Math.abs(lat) < 1 || (lat === 0 && lon === 0)) continue;

		const tripId = v.trip?.tripId ?? '';
		const meta = tripIds.get(tripId);
		if (!meta) continue;

		let bearing: number | null = null;
		const prev = prevMap?.get(tripId);
		if (prev && distM(lat, lon, prev.lat, prev.lon) >= MIN_DIST_M) {
			const dlat = (lat - prev.lat) * 111320;
			const dlon = (lon - prev.lon) * 111320 * COS_DUBLIN;
			bearing = (Math.atan2(dlon, dlat) * 180 / Math.PI + 360) % 360;
		}

		result.push({
			trip_id:          tripId,
			route_short_name: meta.route,
			lat,
			lon,
			bearing,
			delay_seconds:    meta.delay,
		});
	}

	return { positions: result, feedTimestamp: sel.cur.ts, stale: false };
}
