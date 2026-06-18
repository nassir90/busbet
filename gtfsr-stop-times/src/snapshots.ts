import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;

// Default Prometheus-style lookback delta: a sample older than this (relative to
// the queried instant) is considered stale and yields no realtime data.
export const DEFAULT_LOOKBACK_S = 300;

const INDEX_TTL_MS     = 15_000;
const DECODE_CACHE_MAX = 48;

interface Entry { ts: number; file: string }
interface Index { entries: Entry[]; maxTs: number; builtAt: number; count: number }

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
 * Returns a timestamp-sorted index of snapshots in `dir`, built purely from
 * filenames (no decoding). Rebuilt on TTL; updated incrementally when only new
 * files have been appended, fully rebuilt if files were removed/rotated.
 */
function getIndex(dir: string): Index {
	const now = Date.now();
	const cached = indexCache.get(dir);
	if (cached && now - cached.builtAt < INDEX_TTL_MS) return cached;

	let files: string[];
	try {
		files = readdirSync(dir).filter((f) => f.endsWith('.pb.gz'));
	} catch (err) {
		console.warn('[snapshots] readdir failed for', dir, err);
		return cached ?? { entries: [], maxTs: 0, builtAt: now, count: 0 };
	}

	if (!cached || files.length < cached.count) {
		const entries: Entry[] = [];
		for (const f of files) {
			const ts = parseUnix(f);
			if (ts != null) entries.push({ ts, file: f });
		}
		entries.sort((a, b) => a.ts - b.ts);
		const idx: Index = {
			entries,
			maxTs: entries.length ? entries[entries.length - 1].ts : 0,
			builtAt: now,
			count: files.length,
		};
		indexCache.set(dir, idx);
		return idx;
	}

	// Incremental: append only snapshots newer than the current max.
	let added = false;
	for (const f of files) {
		const ts = parseUnix(f);
		if (ts != null && ts > cached.maxTs) {
			cached.entries.push({ ts, file: f });
			cached.maxTs = ts;
			added = true;
		}
	}
	if (added) cached.entries.sort((a, b) => a.ts - b.ts);
	cached.builtAt = now;
	cached.count = files.length;
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
