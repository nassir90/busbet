import { existsSync, readdirSync, readFileSync, watch } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

// Default Prometheus-style lookback delta: a sample older than this (relative to
// the queried instant) is considered stale and yields no realtime data.
export const DEFAULT_LOOKBACK_S = 300;

/** Re-list interval when the directory can't be watched — the original behaviour. */
const INDEX_TTL_MS     = 15_000;
/**
 * Background full re-list while watching: a safety net for missed or coalesced watch events.
 * It runs off the request path, so its cost no longer lands on whichever request is unlucky.
 */
const RESCAN_MS        = 10 * 60_000;
/**
 * How long a newly created snapshot sits before it is indexed. The collector creates the file
 * and then writes it, and the watch event fires on creation — index it at once and a request in
 * that window decodes a truncated gzip and 500s. Snapshots land every 30s, so 2s costs nothing.
 */
export const SETTLE_MS = 2_000;
const DECODE_CACHE_MAX = 48;

interface Entry { ts: number; file: string }
interface Index {
	entries: Entry[];
	maxTs: number;
	builtAt: number;    // when the directory was last fully listed
	count: number;      // files seen by that listing; a smaller count next time means rotation
	watching: boolean;  // new files arrive via fs.watch rather than by re-listing
	dirty: boolean;     // a watched file was removed; the next re-list must rebuild, not append
	rescanning: boolean;
}

const indexCache  = new Map<string, Index>();
const decodeCache = new Map<string, transit_realtime.FeedMessage>();

/** Snapshot filenames look like `2026-03-15T23-17-55-460Z_1773616673.pb.gz`. */
/** The `_<unix>` suffix is the feed's own header.timestamp (verified: zero drift). */
function parseUnix(file: string): number | null {
	const us = file.lastIndexOf('_');
	if (us < 0) return null;
	const dot = file.indexOf('.', us);
	const n = parseInt(file.slice(us + 1, dot < 0 ? undefined : dot), 10);
	return Number.isFinite(n) ? n : null;
}

/**
 * Folds a directory listing into `idx`, in place so a watcher holding `idx` stays attached:
 * appends only snapshots newer than the current max, or fully rebuilds if files were
 * removed/rotated (or a watcher saw a removal).
 */
function applyListing(idx: Index, listing: string[], now: number): void {
	const files = listing.filter((f) => f.endsWith('.pb.gz'));
	if (idx.dirty || files.length < idx.count || !idx.entries.length) {
		const entries: Entry[] = [];
		for (const f of files) {
			const ts = parseUnix(f);
			if (ts != null) entries.push({ ts, file: f });
		}
		entries.sort((a, b) => a.ts - b.ts);
		idx.entries = entries;
		idx.maxTs = entries.length ? entries[entries.length - 1].ts : 0;
		idx.dirty = false;
	} else {
		let added = false;
		for (const f of files) {
			const ts = parseUnix(f);
			if (ts != null && ts > idx.maxTs) {
				idx.entries.push({ ts, file: f });
				idx.maxTs = ts;
				added = true;
			}
		}
		if (added) idx.entries.sort((a, b) => a.ts - b.ts);
	}
	idx.builtAt = now;
	idx.count = files.length;
}

/** Indexes one snapshot the watcher reported, once it has had time to be fully written. */
function addWatched(idx: Index, file: string): void {
	const ts = parseUnix(file);
	if (ts == null) return;
	if (ts > idx.maxTs) {
		idx.entries.push({ ts, file });
		idx.maxTs = ts;
	} else if (!idx.entries.some((e) => e.file === file)) {
		// Out of order (a backfill, or a duplicate event for an already-indexed file).
		idx.entries.push({ ts, file });
		idx.entries.sort((a, b) => a.ts - b.ts);
	}
}

function watchDir(dir: string, idx: Index): void {
	try {
		const watcher = watch(dir, (event, name) => {
			if (event !== 'rename' || !name || !name.endsWith('.pb.gz')) return;
			// 'rename' covers both creation and removal; tell them apart once the file settles.
			setTimeout(() => {
				if (existsSync(join(dir, name))) addWatched(idx, name);
				else idx.dirty = true;
			}, SETTLE_MS).unref();
		});
		watcher.on('error', (err) => {
			console.warn('[snapshots] watch failed for', dir, '- falling back to re-listing', err);
			idx.watching = false;
			watcher.close();
		});
		watcher.unref();
		idx.watching = true;
	} catch (err) {
		console.warn('[snapshots] cannot watch', dir, '- falling back to re-listing', err);
	}
}

function rescanInBackground(dir: string, idx: Index): void {
	idx.rescanning = true;
	readdir(dir)
		.then((listing) => applyListing(idx, listing, Date.now()))
		.catch((err) => console.warn('[snapshots] background readdir failed for', dir, err))
		.finally(() => { idx.rescanning = false; });
}

/**
 * Returns a timestamp-sorted index of snapshots in `dir`, built purely from filenames (no
 * decoding).
 *
 * The directory is listed once, then watched. It used to be re-listed every 15s on the request
 * path, and the collector never rotates it: at 126k files a warm listing cost ~250ms and a cold
 * one several seconds, paid by whichever request crossed the TTL, and growing by ~2,900 files a
 * day. If the directory can't be watched, that TTL re-listing is still the fallback.
 */
function getIndex(dir: string): Index {
	const now = Date.now();
	const cached = indexCache.get(dir);

	if (!cached) {
		let listing: string[];
		try {
			listing = readdirSync(dir);
		} catch (err) {
			console.warn('[snapshots] readdir failed for', dir, err);
			return { entries: [], maxTs: 0, builtAt: now, count: 0, watching: false, dirty: false, rescanning: false };
		}
		const idx: Index = { entries: [], maxTs: 0, builtAt: now, count: 0, watching: false, dirty: false, rescanning: false };
		applyListing(idx, listing, now);
		indexCache.set(dir, idx);
		watchDir(dir, idx);
		return idx;
	}

	if (cached.watching) {
		if ((cached.dirty || now - cached.builtAt >= RESCAN_MS) && !cached.rescanning) {
			rescanInBackground(dir, cached);
		}
		return cached;
	}

	if (now - cached.builtAt < INDEX_TTL_MS) return cached;
	try {
		applyListing(cached, readdirSync(dir), now);
	} catch (err) {
		console.warn('[snapshots] readdir failed for', dir, err);
	}
	return cached;
}

/** Index of the last entry with `ts <= atSec`, or -1 if none. */
function findFloor(entries: Entry[], atSec: number): number {
	let lo = 0, hi = entries.length - 1, ans = -1;
	while (lo <= hi) {
		const mid = (lo + hi) >> 1;
		if (entries[mid].ts <= atSec) { ans = mid; lo = mid + 1; }
		else hi = mid - 1;
	}
	return ans;
}

export interface Selection {
	cur: Entry | null;   // newest snapshot at or before the queried instant
	prev: Entry | null;  // the snapshot immediately before `cur` (for bearing derivation)
	stale: boolean;      // true when there is no snapshot within `lookbackSec` of the instant
}

/** Prometheus-style point selection: last sample <= atSec, stale if gap > lookback. */
export function selectAt(dir: string, atSec: number, lookbackSec: number): Selection {
	const { entries } = getIndex(dir);
	if (!entries.length) return { cur: null, prev: null, stale: true };
	const i = findFloor(entries, atSec);
	if (i < 0) return { cur: null, prev: null, stale: true };
	const cur = entries[i];
	return {
		cur,
		prev: i > 0 ? entries[i - 1] : null,
		stale: atSec - cur.ts > lookbackSec,
	};
}

/** Decode a snapshot file, with a small LRU cache keyed by absolute path. */
export function decodeFeed(dir: string, file: string): transit_realtime.FeedMessage {
	const key = join(dir, file);
	const hit = decodeCache.get(key);
	if (hit) { decodeCache.delete(key); decodeCache.set(key, hit); return hit; }
	const feed = FeedMessage.decode(new Uint8Array(gunzipSync(readFileSync(key))));
	decodeCache.set(key, feed);
	if (decodeCache.size > DECODE_CACHE_MAX) {
		const oldest = decodeCache.keys().next().value;
		if (oldest !== undefined) decodeCache.delete(oldest);
	}
	return feed;
}

// ── Query-param parsing (Prometheus-compatible) ────────────────────────────

/** Accepts RFC3339 or a Unix timestamp (seconds, or milliseconds if > 1e12). Returns epoch seconds. */
export function parseTimeParam(v: string | null): number | null {
	if (!v) return null;
	const num = Number(v);
	if (v.trim() !== '' && Number.isFinite(num)) return num > 1e12 ? Math.floor(num / 1000) : Math.floor(num);
	const ms = Date.parse(v);
	return Number.isNaN(ms) ? null : Math.floor(ms / 1000);
}

/** Accepts a float (seconds) or a duration string like `30s`, `5m`, `1h`. */
export function parseDuration(v: string | null, def: number): number {
	if (!v) return def;
	const m = v.match(/^(\d+(?:\.\d+)?)(s|m|h)?$/);
	if (!m) return def;
	const n = parseFloat(m[1]);
	return m[2] === 'm' ? n * 60 : m[2] === 'h' ? n * 3600 : n;
}
