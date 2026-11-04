import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import type { transit_realtime } from 'gtfs-realtime-bindings';
import type { Departure, TripDetail } from './types.js';
import { selectAt, decodeFeed, DEFAULT_LOOKBACK_S, DECODE_CACHE_MAX } from './snapshots.js';

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
/** Per trip, its stop time updates sorted by stop sequence: everything the overlay needs. */
export type TripUpdates = Map<string, StopUpdate[]>;

/**
 * Memoised per decoded feed. Building this map is a full walk over every entity in the NTA feed,
 * and it used to run once per stop lookup — so a batch of ten favourites walked the feed ten
 * times, and every single-stop poll from every client walked it again. `decodeFeed` LRU-caches
 * the FeedMessage, so object identity is stable across requests and a WeakMap keyed on it hits
 * for as long as that snapshot stays current, then collects itself when the feed rolls over.
 */
const tripUpdatesCache = new WeakMap<transit_realtime.FeedMessage, TripUpdates>();

function buildTripUpdates(feed: transit_realtime.FeedMessage): TripUpdates {
	const cached = tripUpdatesCache.get(feed);
	if (cached) return cached;
	const map: TripUpdates = new Map();
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

// ── Reading the trip updates straight off the wire ─────────────────────────

/**
 * Walks an encoded feed and reads only what buildTripUpdates keeps: trip ids, stop sequences
 * and delays. Decoding the whole FeedMessage built ~7x that in objects per snapshot — stop ids,
 * start dates, vehicle descriptors, one object per arrival and departure event — all dropped
 * again the moment the map was built. The departures path paid that once per new snapshot,
 * and /range paid it at every step; the garbage from a 26-step range took the process from
 * 113 MB to 368 MB.
 *
 * The result is exactly buildTripUpdates over the decoded feed (test/tripupdates.test.ts
 * checks the two against each other), including how gtfs-realtime-bindings reads the wire:
 * a repeated scalar or sub-message keeps its last value; an event that is present but carries
 * no delay reads as 0; a varint wider than 32 bits keeps its low 32; and a feed, entity or
 * trip update missing a required field throws, as the full decode did. Vehicle and alert
 * entities, which a TripUpdates feed doesn't carry, are skipped without being checked.
 */
function parseTripUpdates(raw: Buffer): TripUpdates {
	const map: TripUpdates = new Map();
	let pos = 0;
	const end = raw.length;

	const outOfRange = (): never => { throw new RangeError(`index out of range: ${pos} > ${end}`); };
	/** Low 32 bits of a varint, as protobufjs's Reader#uint32: up to 10 bytes, read unsigned. */
	const uint32 = (): number => {
		let value = 0;
		for (let shift = 0; shift < 32; shift += 7) {
			if (pos >= end) outOfRange();
			const b = raw[pos++];
			value = (value | ((b & 0x7f) << shift)) >>> 0;
			if (b < 0x80) return value;
		}
		// A 5th byte with its continuation bit set: the value is in the low 32 bits already;
		// the rest of the varint (up to 5 more bytes) is skipped unread.
		if ((pos += 5) > end) { pos = end; outOfRange(); }
		return value;
	};
	const int32 = (): number => uint32() | 0;
	const skip = (wireType: number): void => {
		switch (wireType) {
			case 0: uint32(); break;
			case 1: pos += 8; break;
			case 2: { const len = uint32(); pos += len; break; }
			case 3: while ((wireType = uint32() & 7) !== 4) skip(wireType); break;
			case 5: pos += 4; break;
			default: throw new Error(`invalid wire type ${wireType} at offset ${pos}`);
		}
		if (pos > end) outOfRange();
	};
	/** Start of a length-delimited field's payload; the caller walks it to the returned end. */
	const sub = (): number => {
		const len = uint32();
		if (pos + len > end) outOfRange();
		return pos + len;
	};

	const readEvent = (stop: number): number => {
		let delay = 0;
		while (pos < stop) {
			const tag = uint32();
			if (tag >>> 3 === 1) delay = int32();
			else skip(tag & 7);
		}
		return delay;
	};

	const readStopTimeUpdate = (stop: number, updates: StopUpdate[]): void => {
		let stopSequence = 0;
		let arrival: number | undefined;
		let departure: number | undefined;
		while (pos < stop) {
			const tag = uint32();
			switch (tag >>> 3) {
				case 1: stopSequence = uint32(); break;
				case 2: arrival = readEvent(sub()); break;
				case 3: departure = readEvent(sub()); break;
				default: skip(tag & 7);
			}
		}
		const delay = departure ?? arrival;
		if (delay != null) updates.push({ stopSequence, delay });
	};

	const readTripId = (stop: number): string => {
		let tripId = '';
		while (pos < stop) {
			const tag = uint32();
			if (tag >>> 3 === 1) {
				const len = uint32();
				tripId = raw.toString('utf8', pos, pos = Math.min(pos + len, end));
			} else skip(tag & 7);
		}
		return tripId;
	};

	const readTripUpdate = (stop: number): [string, StopUpdate[]] => {
		let tripId: string | undefined;
		const updates: StopUpdate[] = [];
		while (pos < stop) {
			const tag = uint32();
			switch (tag >>> 3) {
				case 1: tripId = readTripId(sub()); break;
				case 2: readStopTimeUpdate(sub(), updates); break;
				default: skip(tag & 7);
			}
		}
		if (tripId === undefined) throw new Error("missing required 'trip'");
		return [tripId, updates];
	};

	const readEntity = (stop: number): void => {
		let id = false;
		let tripUpdate: [string, StopUpdate[]] | undefined;
		while (pos < stop) {
			const tag = uint32();
			switch (tag >>> 3) {
				case 1: id = true; skip(tag & 7); break;
				case 3: tripUpdate = readTripUpdate(sub()); break;
				default: skip(tag & 7);
			}
		}
		if (!id) throw new Error("missing required 'id'");
		if (!tripUpdate) return;
		const [tripId, updates] = tripUpdate;
		if (tripId && updates.length) {
			map.set(tripId, updates.sort((a, b) => a.stopSequence - b.stopSequence));
		}
	};

	let header = false;
	while (pos < end) {
		const tag = uint32();
		switch (tag >>> 3) {
			case 1: {
				const stop = sub();
				let version = false;
				while (pos < stop) {
					const t = uint32();
					if (t >>> 3 === 1) version = true;
					skip(t & 7);
				}
				if (!version) throw new Error("missing required 'gtfsRealtimeVersion'");
				header = true;
				break;
			}
			case 2: readEntity(sub()); break;
			default: skip(tag & 7);
		}
	}
	if (!header) throw new Error("missing required 'header'");
	return map;
}

/** For tests: the map read off an encoded feed, to compare with the one built from a decode. */
export function tripUpdatesFromEncoded(raw: Uint8Array): TripUpdates {
	return parseTripUpdates(Buffer.from(raw.buffer, raw.byteOffset, raw.byteLength));
}

export function tripUpdatesFromDecoded(feed: transit_realtime.FeedMessage): TripUpdates {
	return buildTripUpdates(feed);
}

/** The trip-updates maps of the last few snapshots, by path, like decodeFeed's cache. */
const readCache = new Map<string, TripUpdates>();

function readTripUpdates(dir: string, file: string): TripUpdates {
	const key = join(dir, file);
	const hit = readCache.get(key);
	if (hit) { readCache.delete(key); readCache.set(key, hit); return hit; }
	const updates = parseTripUpdates(gunzipSync(readFileSync(key)));
	readCache.set(key, updates);
	if (readCache.size > DECODE_CACHE_MAX) {
		const oldest = readCache.keys().next().value;
		if (oldest !== undefined) readCache.delete(oldest);
	}
	return updates;
}

export interface TripUpdatesSelection {
	updates: TripUpdates | null;   // null when stale / no snapshot at-or-before the instant
	feedTimestamp: number | null;
	stale: boolean;
}

/** selectFeed, with the snapshot read down to its trip-updates map rather than decoded. */
export function selectTripUpdates(feedsDir: string | undefined, atSec?: number, lookbackSec = DEFAULT_LOOKBACK_S): TripUpdatesSelection {
	const dir = feedsDir ?? DEFAULT_FEEDS_DIR;
	const at = atSec ?? Math.floor(Date.now() / 1000);
	const sel = selectAt(dir, at, lookbackSec);
	if (!sel.cur) return { updates: null, feedTimestamp: null, stale: true };
	if (sel.stale) return { updates: null, feedTimestamp: sel.cur.ts, stale: true };
	return { updates: readTripUpdates(dir, sel.cur.file), feedTimestamp: sel.cur.ts, stale: false };
}

function addSeconds(timeHHMM: string, seconds: number): string {
	const [h, m] = timeHHMM.split(':').map(Number);
	const totalMins = h * 60 + m + Math.round(seconds / 60);
	const rh = Math.max(0, Math.floor(totalMins / 60)) % 24;
	const rm = ((totalMins % 60) + 60) % 60;
	return `${String(rh).padStart(2, '0')}:${String(rm).padStart(2, '0')}`;
}

export function applyRealtimeDelaysToTrip(detail: TripDetail, tripUpdates: TripUpdates | null): TripDetail {
	if (!tripUpdates) return detail;
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
	return applyTripUpdates(departures, buildTripUpdates(feed));
}

export function applyTripUpdates(departures: Departure[], tripUpdates: TripUpdates | null): Departure[] {
	if (departures.length === 0 || !tripUpdates) return departures;
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
