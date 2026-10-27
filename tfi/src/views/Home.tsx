import { useEffect, useMemo, useState } from 'preact/hooks';
import { type BatchDeparturesResponse, paths, prefetch, seed, useApi } from '../api';
import { DepartureRow, Empty, MenuIcon, StaleBadge, TopBar } from '../components';
import { href, Link, useNavReady } from '../router';
import { type Favourite, updateFavourite, useFavourites, useSettings } from '../store';
import { useNowMinutes } from '../time';
import { Search } from './Search';

const PER_CARD = 3;

function distanceM(aLat: number, aLon: number, bLat: number, bLon: number) {
	const r = Math.PI / 180;
	const x = (bLon - aLon) * r * Math.cos(((aLat + bLat) / 2) * r);
	const y = (bLat - aLat) * r;
	return Math.sqrt(x * x + y * y) * 6_371_000;
}

function distanceLabel(m: number) {
	return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

function usePosition(enabled: boolean) {
	const [pos, setPos] = useState<{ lat: number; lon: number } | null>(null);
	useEffect(() => {
		if (!enabled || !('geolocation' in navigator)) { setPos(null); return; }
		const id = navigator.geolocation.watchPosition(
			(p) => setPos({ lat: p.coords.latitude, lon: p.coords.longitude }),
			() => {},
			{ enableHighAccuracy: false, maximumAge: 60_000, timeout: 20_000 },
		);
		return () => navigator.geolocation.clearWatch(id);
	}, [enabled]);
	return pos;
}

export function Home() {
	const [searching, setSearching] = useState(false);
	const favourites = useFavourites();
	const settings = useSettings();
	const now = useNowMinutes();
	const pos = usePosition(settings.sortByDistance);

	// One round trip for every card, rather than one per favourite.
	const codes = favourites.map((f) => f.code);
	const batch = useApi<BatchDeparturesResponse>(codes.length ? paths.batch(codes) : null);
	useNavReady(!codes.length || batch.data !== undefined || batch.error != null, batch.data !== undefined && !batch.loading);

	useEffect(() => {
		if (!batch.data) return;
		// Each result is a full stop board: opening a favourite then paints from cache at once.
		for (const [code, r] of Object.entries(batch.data.results)) seed(paths.departures(code), r);
		// Favourites saved by the old page have no coordinates; the batch answer carries them.
		for (const f of favourites) {
			const s = batch.data.results[f.code]?.stop;
			if (s && f.lat == null && s.stop_lat != null) updateFavourite(f.code, { lat: s.stop_lat, lon: s.stop_lon });
		}
	}, [batch.data]);

	const byDistance = settings.sortByDistance && pos != null;
	const ordered = useMemo(() => {
		if (!byDistance) return favourites.map((f) => ({ f, dist: null as number | null }));
		return favourites
			.map((f) => ({ f, dist: f.lat != null && f.lon != null ? distanceM(pos!.lat, pos!.lon, f.lat, f.lon) : null }))
			.sort((a, b) => (a.dist ?? Infinity) - (b.dist ?? Infinity));
	}, [favourites, pos, byDistance]);

	const stale = batch.failedRefresh || (batch.data ? Object.values(batch.data.results).some((r) => r.stale) : false);

	return (
		<>
			<TopBar
				back={false}
				title={<>Favourites{byDistance && favourites.length > 0 && <span class="bar-qual"> · by distance</span>}</>}
				actions={
					<>
						<StaleBadge show={stale} />
						<Link class="icon-btn" href={href.settings()} aria-label="Settings"><MenuIcon /></Link>
					</>
				}
			/>
			<main class="page">
				<Search onQuery={setSearching} />
				{!searching && (
					<>
						{favourites.length > 0 ? (
							<div class="cards">
								{ordered.map(({ f, dist }) => (
									<FavCard
										key={f.code}
										fav={f}
										data={batch.data?.results[f.code]}
										missing={batch.data?.missing.includes(f.code) ?? false}
										loading={batch.data === undefined && batch.error == null}
										failed={batch.error != null}
										now={now}
										distance={dist}
									/>
								))}
							</div>
						) : (
							<Empty>Search for a stop or route. Star a stop to keep it here with its next departures.</Empty>
						)}
					</>
				)}
			</main>
		</>
	);
}

function FavCard({ fav, data, missing, loading, failed, now, distance }: {
	fav: Favourite;
	data: BatchDeparturesResponse['results'][string] | undefined;
	missing: boolean;
	loading: boolean;
	failed: boolean;
	now: number;
	distance: number | null;
}) {
	const hidden = new Set(fav.hiddenRoutes ?? []);
	const deps = data?.departures.filter((d) => !hidden.has(d.route_short_name)).slice(0, PER_CARD);
	return (
		<article class="card">
			<div class="card-head">
				<Link class="card-title" href={href.stop(fav.code)} prefetch={() => prefetch(paths.departures(fav.code))}>
					{fav.name}
				</Link>
				<span class="code">{fav.code}</span>
				{distance != null && <span class="dist">{distanceLabel(distance)}</span>}
			</div>
			{loading ? (
				<ul class="list skeleton"><li><span /></li><li><span /></li></ul>
			) : missing ? (
				<Empty>This stop isn't in the current timetable.</Empty>
			) : failed && !data ? (
				<Empty>Couldn't load departures.</Empty>
			) : deps && deps.length ? (
				<ul class="list">{deps.map((d) => <DepartureRow key={d.trip_id} d={d} stopCode={fav.code} now={now} />)}</ul>
			) : (
				<Empty>No departures in the next 105 minutes.</Empty>
			)}
		</article>
	);
}
