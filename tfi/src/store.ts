import { useEffect, useState } from 'preact/hooks';

/**
 * Where user data (favourites, history, settings) is kept. The only implementation is the browser's
 * localStorage. The intended successor is a hosted, signed-in store shared with the mobile app, so
 * nothing outside this file touches storage: the views only see the stores below, which keep a
 * synchronous in-memory value and write through. A remote backend would `load` the cached copy,
 * `save` in the background, and call `onChange` when the server sends something newer.
 */
export interface Backend {
	load(key: string): unknown | undefined;
	save(key: string, value: unknown): void;
	/** Changes made elsewhere: another tab now, another device later. */
	onChange(key: string, cb: () => void): void;
}

// Reads and writes are wrapped: storage can be absent or throw (private windows, blocked site
// data), and the site must still work, just without remembering anything.
const localBackend: Backend = {
	load(key) {
		try {
			const raw = localStorage.getItem(key);
			return raw == null ? undefined : JSON.parse(raw);
		} catch {
			return undefined;
		}
	},
	save(key, value) {
		try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* not persisted */ }
	},
	onChange(key, cb) {
		window.addEventListener('storage', (e) => { if (e.key === key) cb(); });
	},
};

const backend: Backend = localBackend;

function createStore<T>(key: string, fallback: T, migrate: (v: T) => T = (v) => v) {
	const read = () => migrate((backend.load(key) as T | undefined) ?? fallback);
	let value = read();
	const subs = new Set<(v: T) => void>();
	const set = (next: T) => {
		value = next;
		backend.save(key, next);
		subs.forEach((s) => s(next));
	};
	backend.onChange(key, () => {
		value = read();
		subs.forEach((s) => s(value));
	});
	const use = (): T => {
		const [v, setV] = useState(value);
		useEffect(() => {
			subs.add(setV);
			setV(value);
			return () => { subs.delete(setV); };
		}, []);
		return v;
	};
	return { get: () => value, set, use };
}

// ---------------------------------------------------------------------------
// Favourites
// ---------------------------------------------------------------------------

export interface Favourite {
	code: string;
	name: string;
	lat?: number | null;
	lon?: number | null;
	/** Routes not shown for this stop, on its card and its board. */
	hiddenRoutes?: string[];
}

// Same key the old server-rendered page used ({code, name}), so existing favourites carry over.
const favStore = createStore<Favourite[]>('tfi-favourites', [], (list) =>
	Array.isArray(list) ? list.filter((f) => f && typeof f.code === 'string') : [],
);

export const useFavourites = favStore.use;
export const getFavourites = favStore.get;

export function isFavourite(code: string) {
	return favStore.get().some((f) => f.code === code);
}

export function addFavourite(f: Favourite) {
	favStore.set([...favStore.get().filter((x) => x.code !== f.code), f]);
}

export function removeFavourite(code: string) {
	favStore.set(favStore.get().filter((f) => f.code !== code));
}

export function updateFavourite(code: string, patch: Partial<Favourite>) {
	favStore.set(favStore.get().map((f) => (f.code === code ? { ...f, ...patch } : f)));
}

export function moveFavourite(code: string, by: -1 | 1) {
	const list = [...favStore.get()];
	const i = list.findIndex((f) => f.code === code);
	const j = i + by;
	if (i < 0 || j < 0 || j >= list.length) return;
	[list[i], list[j]] = [list[j], list[i]];
	favStore.set(list);
}

// ---------------------------------------------------------------------------
// Search history
// ---------------------------------------------------------------------------

export type HistoryEntry =
	| { kind: 'stop'; code: string; name: string; routes?: string[] }
	| { kind: 'route'; route: string; label: string };

const MAX_HISTORY = 12;
const historyStore = createStore<HistoryEntry[]>('tfi-history', []);
export const useHistory = historyStore.use;

const historyKey = (h: HistoryEntry) => (h.kind === 'stop' ? `s:${h.code}` : `r:${h.route}`);

export function recordHistory(h: HistoryEntry) {
	const k = historyKey(h);
	historyStore.set([h, ...historyStore.get().filter((x) => historyKey(x) !== k)].slice(0, MAX_HISTORY));
}

export function forgetHistory(h: HistoryEntry) {
	const k = historyKey(h);
	historyStore.set(historyStore.get().filter((x) => historyKey(x) !== k));
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

export interface Settings {
	/** Sort favourites by distance from the browser's location. Off until the user asks. */
	sortByDistance: boolean;
	/** Show the map on stop and trip pages. */
	maps: boolean;
	/** One of PALETTES' ids; the app's built-in palettes. */
	palette: string;
}

const settingsStore = createStore<Settings>('tfi-settings', { sortByDistance: false, maps: true, palette: 'purple' }, (s) => ({
	sortByDistance: !!s?.sortByDistance,
	maps: s?.maps !== false,
	palette: typeof s?.palette === 'string' ? s.palette : 'purple',
}));
export const getSettings = settingsStore.get;
export const useSettings = settingsStore.use;

export function updateSettings(patch: Partial<Settings>) {
	settingsStore.set({ ...settingsStore.get(), ...patch });
}

export function clearHistory() {
	historyStore.set([]);
}
