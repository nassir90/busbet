import { useEffect, useMemo, useState } from 'preact/hooks';
import { type DeparturesResponse, paths, peek, useApi, type VehiclePosition } from '../api';
import { DepartureRow, Empty, FilterIcon, Skeleton, StaleBadge, Star, TopBar } from '../components';
import { BusMap, type MapBus } from '../Map';
import { useNavReady } from '../router';
import { addFavourite, recordHistory, removeFavourite, updateFavourite, useFavourites, useSettings } from '../store';
import { driftLabel, driftMinutes, dueLabel, minutesUntil, toMins, useNowMinutes } from '../time';

/** Buses further out than this aren't drawn: the map is about what's coming soon. */
const BUS_WINDOW_MIN = 30;

function runState(v: VehiclePosition, now: number): 'not-departed' | 'running' | 'finished' {
	if (v.first_departure && minutesUntil(toMins(v.first_departure), now) > 0) return 'not-departed';
	if (v.last_arrival && minutesUntil(toMins(v.last_arrival), now) < 0) return 'finished';
	return 'running';
}

export function StopView({ code }: { code: string }) {
	const cachedAtOpen = useMemo(() => peek(paths.departures(code)) !== undefined, [code]);
	const res = useApi<DeparturesResponse>(paths.departures(code));
	const settings = useSettings();
	// Fetched with the map off too: the bus list below the board reads it.
	const vehicles = useApi<VehiclePosition[]>(paths.vehicles(code, BUS_WINDOW_MIN));
	const routes = useApi<string[]>(paths.stopRoutes(code), null);
	const favourites = useFavourites();
	const now = useNowMinutes();
	const [filterOpen, setFilterOpen] = useState(false);
	const [localHidden, setLocalHidden] = useState<string[]>([]);

	useNavReady(res.data !== undefined || res.error != null, cachedAtOpen);

	const fav = favourites.find((f) => f.code === code);
	// Persisted on the favourite so its home card honours it too; local only for other stops.
	const hidden = new Set(fav ? fav.hiddenRoutes ?? [] : localHidden);
	const setHidden = (next: Set<string>) => {
		if (fav) updateFavourite(code, { hiddenRoutes: [...next] });
		else setLocalHidden([...next]);
	};

	const stop = res.data?.stop;
	useEffect(() => {
		if (stop) recordHistory({ kind: 'stop', code: stop.stop_code, name: stop.stop_name, routes: stop.routes ?? routes.data });
	}, [stop?.stop_code, routes.data]);
	useEffect(() => {
		document.title = stop ? `${stop.stop_name} (${code}) — Iompar` : `Stop ${code} — Iompar`;
	}, [stop?.stop_name, code]);

	const deps = res.data?.departures.filter((d) => !hidden.has(d.route_short_name));

	// Each vehicle on the map, labelled with when its trip reaches this stop.
	const buses: MapBus[] = useMemo(() => (vehicles.data ?? [])
		.filter((v) => !hidden.has(v.route_short_name))
		.map((v) => {
			const d = res.data?.departures.find((x) => x.trip_id === v.trip_id);
			const due = d ? minutesUntil(toMins(d.estimated_departure ?? d.scheduled_departure), now) : null;
			const state = runState(v, now);
			return {
				id: v.trip_id, lat: v.lat, lon: v.lon, bearing: v.bearing,
				label: v.route_short_name,
				title: `${v.route_short_name}${d ? ` to ${d.trip_headsign}` : ''}${due != null ? ` · ${dueLabel(due)}` : ''}${state === 'not-departed' ? ' · not departed' : ''}`,
				dim: state !== 'running',
			};
		}), [vehicles.data, res.data, now, hidden.size]);

	const allRoutes = (routes.data?.length ? routes.data : [...new Set(res.data?.departures.map((d) => d.route_short_name))]).sort(
		(a, b) => a.localeCompare(b, undefined, { numeric: true }),
	);

	return (
		<>
			<TopBar
				title={stop?.stop_name ?? `Stop ${code}`}
				sub={`Stop ${code}`}
				actions={
					<>
						<StaleBadge show={!!res.data?.stale || res.failedRefresh} />
						<button class={`icon-btn${hidden.size ? ' on' : ''}`} aria-label="Filter routes" aria-expanded={filterOpen} onClick={() => setFilterOpen(!filterOpen)}>
							<FilterIcon active={hidden.size > 0} />
						</button>
						<button
							class={`icon-btn${fav ? ' on' : ''}`}
							aria-label={fav ? 'Remove favourite' : 'Add favourite'}
							aria-pressed={!!fav}
							onClick={() => fav
								? removeFavourite(code)
								: addFavourite({ code, name: stop?.stop_name ?? code, lat: stop?.stop_lat, lon: stop?.stop_lon, hiddenRoutes: localHidden })}
						>
							<Star on={!!fav} />
						</button>
					</>
				}
			/>
			{filterOpen && (
				<div class="filter">
					<div class="section-head">
						<span class="label">Show routes</span>
						{/* Always laid out, only hidden: popping in made the header taller and shifted
						    the chips and the board down under the pointer on the first toggle. */}
						<button
							class="text-btn"
							style={{ visibility: hidden.size > 0 ? 'visible' : 'hidden' }}
							onClick={() => setHidden(new Set())}
						>
							Show all
						</button>
					</div>
					<div class="filter-chips">
						{allRoutes.map((r) => (
							<label key={r} class={`chip toggle-chip${hidden.has(r) ? ' off' : ''}`}>
								<input
									type="checkbox"
									checked={!hidden.has(r)}
									onChange={() => {
										const next = new Set(hidden);
										if (next.has(r)) next.delete(r); else next.add(r);
										setHidden(next);
									}}
								/>
								{r}
							</label>
						))}
					</div>
					{!fav && <p class="note">Add this stop to favourites to keep the filter.</p>}
				</div>
			)}
			{/* On a wide screen the map sits beside the list rather than above it. */}
			<div class={`split${settings.maps && stop?.stop_lat != null ? ' with-map' : ''}`}>
			{settings.maps && stop?.stop_lat != null && stop.stop_lon != null && (
				<BusMap stops={[{ lat: stop.stop_lat, lon: stop.stop_lon, label: `${stop.stop_name} (${code})`, primary: true }]} buses={buses} />
			)}
			<main class="page flush">
				{res.error && !res.data ? (
					<Empty>{(res.error as { status?: number }).status === 404 ? `There's no stop ${code} in the current timetable.` : "Couldn't load departures. Retrying…"}</Empty>
				) : !deps ? (
					<Skeleton />
				) : res.data!.departures.length === 0 ? (
					<Empty>No departures in the next 105 minutes.</Empty>
				) : deps.length === 0 ? (
					<Empty>No departures for the routes you've kept visible.</Empty>
				) : (
					<ul class="list deps">{deps.map((d) => <DepartureRow key={d.trip_id} d={d} stopCode={code} now={now} />)}</ul>
				)}
				{vehicles.data && vehicles.data.length > 0 && <BusList vehicles={vehicles.data} res={res.data} hidden={hidden} now={now} />}
			</main>
			</div>
		</>
	);
}

/** The text form of the map: which buses are live and how far off they are. */
function BusList({ vehicles, res, hidden, now }: {
	vehicles: VehiclePosition[];
	res: DeparturesResponse | undefined;
	hidden: Set<string>;
	now: number;
}) {
	const rows = vehicles
		.filter((v) => !hidden.has(v.route_short_name))
		.map((v) => {
			const d = res?.departures.find((x) => x.trip_id === v.trip_id);
			const due = d ? minutesUntil(toMins(d.estimated_departure ?? d.scheduled_departure), now) : null;
			return { v, d, due, state: runState(v, now) };
		})
		.sort((a, b) => (a.due ?? 999) - (b.due ?? 999));
	if (!rows.length) return null;
	return (
		<section class="buses">
			<h2 class="label pad">Buses on the way</h2>
			<ul class="list">
				{rows.map(({ v, d, due, state }) => (
					<li key={v.trip_id} class="bus-row">
						<span class="chip">{v.route_short_name}</span>
						<span class="row-main">
							<span>{d?.trip_headsign ?? 'In service'}</span>
							<span class="code">
								{state === 'not-departed' ? 'Not departed yet' : state === 'finished' ? 'Finished' : 'Running'}
								{v.delay_seconds != null && ` · ${driftLabel(driftMinutes('', null, v.delay_seconds))}`}
							</span>
						</span>
						{due != null && <span class="due">{dueLabel(due)}</span>}
					</li>
				))}
			</ul>
		</section>
	);
}
