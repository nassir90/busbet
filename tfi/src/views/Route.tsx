import { useEffect, useMemo, useState } from 'preact/hooks';
import { paths, peek, prefetch, type RouteStop, type ShapePoint, useApi } from '../api';
import { Empty, Skeleton, TopBar } from '../components';
import { BusMap } from '../Map';
import { href, Link, navigate, useNavReady } from '../router';
import { useSettings } from '../store';

// Static timetable data; the API marks it cacheable for an hour.
const STATIC = null;

/** Where the stop and trip pages put their map beside the list (see .split.with-map). */
const WIDE = '(min-width: 1000px)';

/**
 * Whether the screen is wide enough for the map pane. The route map is only shown there: on a
 * phone the list is the page, and Leaflet and the shape aren't fetched at all.
 */
function useWide() {
	const [wide, setWide] = useState(() => matchMedia(WIDE).matches);
	useEffect(() => {
		const mq = matchMedia(WIDE);
		const on = () => setWide(mq.matches);
		mq.addEventListener('change', on);
		return () => mq.removeEventListener('change', on);
	}, []);
	return wide;
}

export function RouteView({ route, dir, from }: { route: string; dir: number | null; from: string | null }) {
	// Both directions load together, so the tabs switch instantly and a one-way route is known
	// before anyone presses the other tab.
	const d0 = useApi<RouteStop[]>(paths.routeStops(route, 0), STATIC);
	const d1 = useApi<RouteStop[]>(paths.routeStops(route, 1), STATIC);
	const cachedAtOpen = useMemo(() => peek(paths.routeStops(route, dir ?? 0)) !== undefined, [route]);

	const loaded = d0.data !== undefined && d1.data !== undefined;
	// No direction asked for: the first one that has stops (some routes only run as direction 1).
	const active = dir ?? (loaded && !d0.data!.length && d1.data!.length ? 1 : 0);
	const cur = active === 0 ? d0 : d1;
	const other = active === 0 ? d1 : d0;

	useNavReady(cur.data !== undefined || cur.error != null, cachedAtOpen);
	useEffect(() => { document.title = `Route ${route} — Iompar`; }, [route]);

	const stops = cur.data;
	const label = (s?: RouteStop[]) => (s && s.length ? `To ${s[s.length - 1].stop_name}` : '');
	const fromIdx = from && stops ? stops.findIndex((s) => s.stop_code === from) : -1;

	const settings = useSettings();
	const showMap = useWide() && settings.maps;
	const shape = useApi<ShapePoint[]>(showMap && stops?.length ? paths.routeShape(route, active) : null, STATIC);
	const mapStops = useMemo(() => (stops ?? [])
		.filter((s) => s.stop_lat != null && s.stop_lon != null)
		.map((s) => ({ lat: s.stop_lat!, lon: s.stop_lon!, label: `${s.stop_name} (${s.stop_code})`, primary: s.stop_code === from })), [stops, from]);
	// Wait for the shape (or its 404): the map frames itself once, from the line. Without a shape,
	// a line through the stops still shows where the route goes.
	const withMap = showMap && mapStops.length >= 2 && (shape.data !== undefined || shape.error != null);
	const line = shape.data && shape.data.length >= 2 ? shape.data : mapStops;

	useEffect(() => {
		if (fromIdx < 0) return;
		document.getElementById(`rs-${from}`)?.scrollIntoView({ block: 'center' });
	}, [fromIdx]);

	return (
		<>
			<TopBar
				title={`Route ${route}`}
				sub={stops && stops.length ? `${stops[0].stop_name} → ${stops[stops.length - 1].stop_name} · ${stops.length} stops` : undefined}
			/>
			{/* On a wide screen the whole route is drawn beside the list, as on the trip page. */}
			<div class={`split${withMap ? ' with-map' : ''}`}>
			{withMap && <BusMap key={active} stops={mapStops} buses={[]} shape={line} fit="all" />}
			<main class="page flush">
			{loaded && (d0.data!.length > 0 || d1.data!.length > 0) && (
				<nav class="tabs" role="tablist">
					{[0, 1].map((d) => {
						const s = (d === 0 ? d0 : d1).data!;
						return (
							<button
								key={d}
								role="tab"
								aria-selected={active === d}
								class={`tab${active === d ? ' on' : ''}`}
								disabled={!s.length}
								onClick={() => navigate(href.route(route, d, from ?? undefined), true)}
							>
								{s.length ? label(s) : 'One-way route'}
							</button>
						);
					})}
				</nav>
			)}
				{cur.error && !stops ? (
					<Empty>Couldn't load route {route}.</Empty>
				) : !stops ? (
					<Skeleton rows={12} />
				) : stops.length === 0 ? (
					<Empty>
						{other.data?.length ? 'This route has no stops in this direction.' : `There's no route ${route} in the current timetable.`}
					</Empty>
				) : (
					<ol class="list line">
						{stops.map((s, i) => (
							<li key={`${s.stop_sequence}-${s.stop_code}`} id={`rs-${s.stop_code}`} class={i === fromIdx ? 'here' : ''}>
								<Link class="row" href={href.stop(s.stop_code)} prefetch={() => prefetch(paths.departures(s.stop_code))}>
									<span class="seq">{s.stop_sequence}</span>
									<span class="row-main">
										<span>{s.stop_name}</span>
										<span class="code">{s.stop_code}</span>
									</span>
								</Link>
							</li>
						))}
					</ol>
				)}
			</main>
			</div>
		</>
	);
}
