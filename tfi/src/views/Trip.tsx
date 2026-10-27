import { useEffect, useMemo, useRef } from 'preact/hooks';
import { paths, peek, prefetch, type ShapePoint, type TripDetail, type TripStop, useApi, type VehiclePosition } from '../api';
import { driftText, Empty, Skeleton, StaleBadge, TopBar } from '../components';
import { BusMap } from '../Map';
import { href, Link, useNavReady } from '../router';
import { useSettings } from '../store';
import { minutesUntil, toMins, useNowMinutes } from '../time';

function timeLabel(s: TripStop, now: number) {
	const diff = minutesUntil(toMins(s.estimated_departure ?? s.scheduled_departure), now);
	if (diff < -1) return `${-diff} min ago`;
	if (diff <= 0) return 'Due';
	return `${diff} min`;
}

export function TripView({ id, from }: { id: string; from: string | null }) {
	const cachedAtOpen = useMemo(() => peek(paths.trip(id)) !== undefined, [id]);
	const trip = useApi<TripDetail>(paths.trip(id));
	const vehicle = useApi<VehiclePosition | null>(paths.tripVehicle(id));
	const settings = useSettings();
	// Geometry is static: fetched once, not polled.
	const shape = useApi<ShapePoint[]>(settings.maps ? paths.tripShape(id) : null, null);
	const now = useNowMinutes();
	const scrolled = useRef(false);

	useNavReady(trip.data !== undefined || trip.error != null, cachedAtOpen);

	const d = trip.data;
	useEffect(() => {
		document.title = d ? `${d.route_short_name} to ${d.trip_headsign} — Iompar` : 'Trip — Iompar';
		// "All stops" is the likely next tap, and static: warm it.
		if (d) prefetch(paths.routeStops(d.route_short_name, d.direction_id), 3_600_000);
	}, [d?.trip_id]);

	// The first stop not yet departed. With no realtime this is the timetable's view of the bus.
	const nextIdx = d ? d.stops.findIndex((s) => minutesUntil(toMins(s.estimated_departure ?? s.scheduled_departure), now) >= 0) : -1;
	const originIdx = d && from ? d.stops.findIndex((s) => s.stop_code === from) : -1;
	const first = d?.stops[0];
	const last = d?.stops[d.stops.length - 1];
	const state = !d || !first || !last ? null
		: minutesUntil(toMins(first.estimated_departure ?? first.scheduled_departure), now) > 0 ? 'Not departed'
		: minutesUntil(toMins(last.estimated_arrival ?? last.scheduled_arrival), now) < 0 ? 'Finished'
		: null;

	useEffect(() => {
		if (!d || scrolled.current) return;
		scrolled.current = true;
		const target = originIdx >= 0 ? originIdx : nextIdx;
		if (target > 2) document.getElementById(`ts-${target}`)?.scrollIntoView({ block: 'center' });
	}, [d]);

	const anchor = originIdx >= 0 ? d!.stops[originIdx] : null;
	const mapStops = useMemo(() => (d ? d.stops : [])
		.filter((s) => s.stop_lat != null && s.stop_lon != null)
		.map((s, i) => ({ lat: s.stop_lat!, lon: s.stop_lon!, label: `${s.stop_name} (${s.stop_code})`, primary: s === anchor, i })), [d, anchor]);
	const buses = useMemo(() => (vehicle.data ? [{
		id: vehicle.data.trip_id, lat: vehicle.data.lat, lon: vehicle.data.lon, bearing: vehicle.data.bearing,
		label: vehicle.data.route_short_name, title: 'Live position', dim: false,
	}] : []), [vehicle.data]);

	return (
		<>
			<TopBar
				title={d ? <>{d.route_short_name} <span class="arrow">→</span> {d.trip_headsign}</> : 'Trip'}
				sub={d && (state ? state : vehicle.data ? 'Live position on map' : undefined)}
				actions={d && <>
					<StaleBadge show={!!d.stale || trip.failedRefresh} />
					<Link class="text-btn on-bar" href={href.route(d.route_short_name, d.direction_id, from ?? undefined)}>All stops</Link>
				</>}
			/>
			<div class={`split${settings.maps && (shape.data?.length ?? 0) >= 2 ? ' with-map' : ''}`}>
			{settings.maps && (shape.data?.length ?? 0) >= 2 && (
				<BusMap stops={mapStops} buses={buses} shape={shape.data} fit="all" />
			)}
			<main class="page flush">
				{trip.error && !d ? (
					<Empty>{(trip.error as { status?: number }).status === 404 ? 'This trip is not in the current timetable.' : "Couldn't load this trip."}</Empty>
				) : !d ? (
					<Skeleton rows={12} />
				) : (
					<ol class="list line trip">
						{d.stops.map((s, i) => {
							const passed = nextIdx >= 0 ? i < nextIdx : true;
							const drift = driftText(s.scheduled_departure, s.estimated_departure, s.delay_seconds, s.realtime);
							return (
								<li key={i} id={`ts-${i}`} class={`${passed && i !== originIdx ? 'passed' : ''}${i === originIdx ? ' here' : ''}${i === nextIdx ? ' next' : ''}`}>
									<Link class="row" href={href.stop(s.stop_code)}>
										<span class="seq">{s.stop_sequence}</span>
										<span class="row-main">
											<span>{s.stop_name}</span>
											<span class="code">{s.stop_code}</span>
										</span>
										<span class="dep-due">
											<span class="due">{timeLabel(s, now)}</span>
											<span class={`dep-sub ${drift.cls}`}>{drift.text.replace('Sched ', '')}</span>
										</span>
									</Link>
								</li>
							);
						})}
					</ol>
				)}
			</main>
			</div>
		</>
	);
}
