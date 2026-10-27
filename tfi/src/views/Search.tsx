import { useEffect, useRef, useState } from 'preact/hooks';
import { fetchJson, paths, prefetch, type RouteDirection, type Stop } from '../api';
import { href, Link, navigate } from '../router';
import { forgetHistory, recordHistory, useHistory, type HistoryEntry } from '../store';

const MAX_CHIPS = 6;

interface Results { q: string; stops: Stop[]; routes: RouteDirection[] }

/**
 * One row per route, both directions folded in, which is how the route page shows it too. An
 * exact match comes first, then routes starting with the query: typing "39" wants the 39, not 239.
 */
function groupRoutes(routes: RouteDirection[], q: string) {
	const rank = (r: string) => (r.toLowerCase() === q ? 0 : r.toLowerCase().startsWith(q) ? 1 : 2);
	const by = new Map<string, RouteDirection[]>();
	for (const r of routes) {
		const list = by.get(r.route_short_name) ?? [];
		list.push(r);
		by.set(r.route_short_name, list);
	}
	return [...by.entries()]
		.map(([route, dirs]) => ({ route, dirs: dirs.sort((a, b) => a.direction_id - b.direction_id) }))
		.sort((a, b) => rank(a.route) - rank(b.route) || a.route.localeCompare(b.route, undefined, { numeric: true }));
}

function routeLabel(dirs: RouteDirection[]) {
	const d = dirs[0];
	return dirs.length > 1 ? `${d.from_stop} ⇄ ${d.to_stop}` : `${d.from_stop} → ${d.to_stop}`;
}

export function Chips({ routes }: { routes?: string[] }) {
	if (!routes?.length) return null;
	const shown = routes.slice(0, MAX_CHIPS);
	return (
		<span class="chips" aria-label={`Routes ${routes.join(', ')}`}>
			{shown.map((r) => <span key={r} class="chip chip-sm static">{r}</span>)}
			{routes.length > shown.length && <span class="more">+{routes.length - shown.length}</span>}
		</span>
	);
}

export function Search({ onQuery }: { onQuery: (active: boolean) => void }) {
	const [q, setQ] = useState('');
	const [res, setRes] = useState<Results | null>(null);
	const [failed, setFailed] = useState(false);
	const seq = useRef(0);
	const history = useHistory();
	const input = useRef<HTMLInputElement>(null);

	// "/" focuses search from anywhere on the page, as on most desktop sites.
	useEffect(() => {
		const onKey = (e: KeyboardEvent) => {
			const t = e.target as HTMLElement;
			if (e.key !== '/' || e.metaKey || e.ctrlKey || e.altKey || t.closest('input, textarea, [contenteditable]')) return;
			e.preventDefault();
			input.current?.focus();
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, []);

	const query = q.trim();
	useEffect(() => { onQuery(query.length > 0); }, [query.length > 0]);

	useEffect(() => {
		if (query.length < 1) { setRes(null); return; }
		const n = ++seq.current;
		const t = setTimeout(async () => {
			try {
				// Stops need two characters (the API refuses one); routes answer to one ("7").
				const [stops, routes] = await Promise.all([
					query.length >= 2 ? fetchJson<Stop[]>(paths.stops(query)) : Promise.resolve([]),
					fetchJson<RouteDirection[]>(paths.routes(query)),
				]);
				if (n !== seq.current) return;
				setRes({ q: query, stops, routes });
				setFailed(false);
			} catch {
				if (n === seq.current) setFailed(true);
			}
		}, 120);
		return () => clearTimeout(t);
	}, [query]);

	const lower = query.toLowerCase();
	const matchingHistory = history.filter((h) =>
		(h.kind === 'stop' ? `${h.name} ${h.code}` : `${h.route} ${h.label}`).toLowerCase().includes(lower),
	).slice(0, query ? 3 : 6);

	const groups = res ? groupRoutes(res.routes, res.q.toLowerCase()) : [];

	const onSubmit = (e: Event) => {
		e.preventDefault();
		// A bare stop number goes straight to its board, as on the old page.
		if (/^\d{1,7}$/.test(query)) { navigate(href.stop(query)); return; }
		const first = res?.stops[0];
		if (first) { recordStop(first); navigate(href.stop(first.stop_code)); }
	};

	return (
		<div class="search">
			<form role="search" onSubmit={onSubmit}>
				<input
					ref={input}
					class="search-input"
					type="search"
					enterKeyHint="search"
					autocomplete="off"
					spellcheck={false}
					placeholder="Stop name, stop number or route"
					aria-label="Search stops and routes"
					value={q}
					onInput={(e) => setQ((e.target as HTMLInputElement).value)}
					onKeyDown={(e) => { if (e.key === 'Escape') setQ(''); }}
				/>
			</form>

			{query && (
				<div class="results">
					{matchingHistory.length > 0 && <HistoryList entries={matchingHistory} />}
					{groups.length > 0 && (
						<section>
							<h2 class="label">Routes</h2>
							<ul class="list">
								{groups.slice(0, 8).map(({ route, dirs }) => (
									<li key={route}>
										<Link
											class="row"
											href={href.route(route)}
											prefetch={() => dirs.forEach((d) => prefetch(paths.routeStops(route, d.direction_id), 3_600_000))}
											onClick={() => recordHistory({ kind: 'route', route, label: routeLabel(dirs) })}
										>
											<span class="chip">{route}</span>
											<span class="row-main">{routeLabel(dirs)}</span>
										</Link>
									</li>
								))}
							</ul>
						</section>
					)}
					{res && res.stops.length > 0 && (
						<section>
							<h2 class="label">Stops</h2>
							<ul class="list">
								{res.stops.map((s) => (
									<li key={s.stop_id}>
										<Link
											class="row"
											href={href.stop(s.stop_code)}
											prefetch={() => prefetch(paths.departures(s.stop_code))}
											onClick={() => recordStop(s)}
										>
											<span class="row-main">
												<span>{s.stop_name}</span>
												<span class="code">{s.stop_code}</span>
											</span>
											<Chips routes={s.routes} />
										</Link>
									</li>
								))}
							</ul>
						</section>
					)}
					{res && res.q === query && !res.stops.length && !groups.length && !matchingHistory.length && (
						<p class="empty">No stops or routes match “{query}”.</p>
					)}
					{failed && <p class="empty">Search is unavailable right now.</p>}
				</div>
			)}
		</div>
	);
}

function recordStop(s: Stop) {
	recordHistory({ kind: 'stop', code: s.stop_code, name: s.stop_name, routes: s.routes });
}

export function HistoryList({ entries, title = 'Recent' }: { entries: HistoryEntry[]; title?: string }) {
	return (
		<section>
			<h2 class="label">{title}</h2>
			<ul class="list">
				{entries.map((h) => (
					<li key={h.kind === 'stop' ? `s${h.code}` : `r${h.route}`} class="with-action">
						<Link
							class="row"
							href={h.kind === 'stop' ? href.stop(h.code) : href.route(h.route)}
							prefetch={() => (h.kind === 'stop' ? prefetch(paths.departures(h.code)) : undefined)}
							onClick={() => recordHistory(h)}
						>
							<svg class="hist-icon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M13 3a9 9 0 0 0-9 9H1l4 4 4-4H6a7 7 0 1 1 2 4.9l-1.4 1.4A9 9 0 1 0 13 3m-1 5v5l4.3 2.5.7-1.2-3.5-2.1V8z" /></svg>
							{h.kind === 'route' && <span class="chip">{h.route}</span>}
							<span class="row-main">
								<span>{h.kind === 'stop' ? h.name : h.label}</span>
								{h.kind === 'stop' && <span class="code">{h.code}</span>}
							</span>
							{h.kind === 'stop' && <Chips routes={h.routes} />}
						</Link>
						<button class="icon-btn subtle" aria-label="Remove from history" onClick={() => forgetHistory(h)}>×</button>
					</li>
				))}
			</ul>
		</section>
	);
}
