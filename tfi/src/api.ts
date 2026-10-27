import { useEffect, useRef, useState } from 'preact/hooks';

// Shapes mirror gtfsr-stop-times' JSON, the same contract tfi-app/Api.kt reads.

export interface Stop {
	stop_id: string;
	stop_code: string;
	stop_name: string;
	stop_lat?: number | null;
	stop_lon?: number | null;
	routes?: string[];
}

export interface Departure {
	trip_id: string;
	stop_sequence: number;
	route_short_name: string;
	direction_id: number;
	trip_headsign: string;
	scheduled_departure: string;
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface DeparturesResponse {
	stop: Stop;
	departures: Departure[];
	/** Header time (epoch s) of the GTFS-R snapshot the overlay came from: when these times were true. */
	feed_timestamp?: number | null;
	/** True when the newest snapshot is older than the server's freshness window. */
	stale?: boolean;
}

export interface BatchDeparturesResponse {
	results: Record<string, DeparturesResponse>;
	missing: string[];
}

export interface RouteDirection {
	route_short_name: string;
	direction_id: number;
	from_stop: string;
	to_stop: string;
}

export interface RouteStop {
	stop_sequence: number;
	stop_code: string;
	stop_name: string;
}

export interface TripStop {
	stop_sequence: number;
	stop_code: string;
	stop_name: string;
	stop_lat?: number | null;
	stop_lon?: number | null;
	scheduled_arrival: string;
	scheduled_departure: string;
	estimated_arrival: string | null;
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface TripDetail {
	trip_id: string;
	route_short_name: string;
	trip_headsign: string;
	direction_id: number;
	stops: TripStop[];
	feed_timestamp?: number | null;
	stale?: boolean;
}

export interface VehiclePosition {
	trip_id: string;
	route_short_name: string;
	lat: number;
	lon: number;
	bearing: number | null;
	delay_seconds: number | null;
	first_departure?: string | null;
	last_arrival?: string | null;
}

export interface ShapePoint { lat: number; lon: number }

export class HttpError extends Error {
	constructor(public status: number) { super(`HTTP ${status}`); }
}

export const POLL_MS = 30_000;

// ---------------------------------------------------------------------------
// Cache
//
// Every response is kept in memory by path. A view renders straight from the cache when it has an
// entry (back/forward and revisits paint in the same frame) and revalidates behind it. Requests in
// flight are shared, so a hover prefetch and the click that follows cost one round trip.
// ---------------------------------------------------------------------------

interface Entry { data: unknown; at: number }
const cache = new Map<string, Entry>();
const inflight = new Map<string, Promise<unknown>>();
const MAX_ENTRIES = 200;

/**
 * Answers the endpoints use for "nothing here" rather than failure: a one-way route's other
 * direction, a trip with no live bus, a trip with no shape. Mapped to a value so the views don't
 * show an error for an ordinary answer.
 */
const NOT_FOUND_AS: Array<[RegExp, unknown]> = [
	[/^route-stops\?/, []],
	[/^vehicles\/trip\//, null],
	[/^shapes\/trip\//, []],
	[/^vehicles\//, []],
];

export function apiPath(path: string): string {
	// Relative, so the site works at / and behind any prefix the proxy strips.
	return `api/${path}`;
}

export function peek<T>(path: string): T | undefined {
	return cache.get(path)?.data as T | undefined;
}

/**
 * Puts an answer obtained some other way into the cache, unless a newer one is already there.
 * The favourites batch carries each stop's full board, so opening a favourite paints at once.
 */
export function seed(path: string, data: unknown, at = Date.now()) {
	const e = cache.get(path);
	if (e && e.at >= at) return;
	cache.set(path, { data, at });
}

export function fetchJson<T>(path: string): Promise<T> {
	const pending = inflight.get(path);
	if (pending) return pending as Promise<T>;
	const p = (async () => {
		try {
			const res = await fetch(apiPath(path), { headers: { Accept: 'application/json' } });
			let data: unknown;
			if (res.status === 404) {
				const fallback = NOT_FOUND_AS.find(([re]) => re.test(path));
				if (!fallback) throw new HttpError(404);
				data = fallback[1];
			} else if (!res.ok) {
				throw new HttpError(res.status);
			} else {
				data = await res.json();
			}
			cache.delete(path);
			cache.set(path, { data, at: Date.now() });
			if (cache.size > MAX_ENTRIES) cache.delete(cache.keys().next().value!);
			return data as T;
		} finally {
			inflight.delete(path);
		}
	})();
	inflight.set(path, p);
	return p;
}

/** Warms the cache unless there's a recent entry. Errors are dropped: the real load will report them. */
export function prefetch(path: string, maxAgeMs = POLL_MS) {
	const e = cache.get(path);
	if (e && Date.now() - e.at < maxAgeMs) return;
	fetchJson(path).catch(() => {});
}

export interface Resource<T> {
	data: T | undefined;
	error: Error | null;
	/** Data is on screen but the last refresh failed, so it may be out of date. */
	failedRefresh: boolean;
	loading: boolean;
}

/**
 * Stale-while-revalidate fetch of one API path, polling every `pollMs` while the tab is visible.
 * Pass `null` to fetch nothing.
 */
export function useApi<T>(path: string | null, pollMs: number | null = POLL_MS): Resource<T> {
	const [, force] = useState(0);
	const state = useRef<{ path: string | null; error: Error | null; failedRefresh: boolean; loading: boolean }>({
		path: null, error: null, failedRefresh: false, loading: false,
	});
	if (state.current.path !== path) {
		state.current = { path, error: null, failedRefresh: false, loading: path != null && peek(path) === undefined };
	}

	useEffect(() => {
		if (path == null) return;
		let cancelled = false;
		let timer: number | undefined;
		const load = async () => {
			clearTimeout(timer);
			try {
				await fetchJson<T>(path);
				if (cancelled) return;
				state.current = { path, error: null, failedRefresh: false, loading: false };
			} catch (e) {
				if (cancelled) return;
				const had = peek(path) !== undefined;
				state.current = { path, error: had ? null : (e as Error), failedRefresh: had, loading: false };
			}
			force((n) => n + 1);
			// Nothing on screen yet: try again soon rather than leaving an error up for a full poll.
			const next = state.current.error ? Math.min(pollMs ?? 5_000, 5_000) : pollMs;
			if (next != null && !document.hidden) timer = window.setTimeout(load, next);
		};
		// Revalidate at once, unless the entry is fresh enough that a refetch would only repeat it.
		const e = cache.get(path);
		if (e && Date.now() - e.at < 5_000) {
			if (pollMs != null) timer = window.setTimeout(load, pollMs - (Date.now() - e.at));
		} else {
			load();
		}
		// Hidden tabs stop polling; coming back refreshes immediately rather than showing old times.
		const onVis = () => { if (!document.hidden) load(); else clearTimeout(timer); };
		if (pollMs != null) document.addEventListener('visibilitychange', onVis);
		return () => {
			cancelled = true;
			clearTimeout(timer);
			document.removeEventListener('visibilitychange', onVis);
		};
	}, [path, pollMs]);

	return {
		data: path == null ? undefined : peek<T>(path),
		error: state.current.error,
		failedRefresh: state.current.failedRefresh,
		loading: state.current.loading,
	};
}

// Path builders, so views and prefetchers agree on cache keys.
const enc = encodeURIComponent;
export const paths = {
	stops: (q: string) => `stops?q=${enc(q)}`,
	routes: (q: string) => `routes?q=${enc(q)}`,
	departures: (code: string) => `departures/${enc(code)}`,
	batch: (codes: string[]) => `departures?codes=${codes.map(enc).join(',')}`,
	stopRoutes: (code: string) => `stop-routes/${enc(code)}`,
	routeStops: (route: string, dir: number) => `route-stops?route=${enc(route)}&direction=${dir}`,
	trip: (id: string) => `trips/${enc(id)}`,
	vehicles: (code: string, windowMins: number) => `vehicles/${enc(code)}?window=${windowMins}`,
	tripVehicle: (id: string) => `vehicles/trip/${enc(id)}`,
	tripShape: (id: string) => `shapes/trip/${enc(id)}`,
};
