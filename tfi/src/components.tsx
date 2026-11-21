import type { ComponentChildren } from 'preact';
import { type Departure, lineClass, paths, prefetch, type TransitMode } from './api';
import { href, Link } from './router';
import { dueLabel, minutesUntil, toMins, wallClock } from './time';

export function TopBar({ title, sub, actions, back = true }: {
	title: ComponentChildren;
	sub?: ComponentChildren;
	actions?: ComponentChildren;
	back?: boolean;
}) {
	return (
		<header class="bar">
			{back && (
				<button class="icon-btn" aria-label="Back" onClick={() => (history.length > 1 ? history.back() : (location.search = ''))}>
					<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
				</button>
			)}
			<div class="bar-title">
				<h1>{title}</h1>
				{sub && <div class="bar-sub">{sub}</div>}
			</div>
			{actions && <div class="bar-actions">{actions}</div>}
		</header>
	);
}

export function RouteChip({ route, dir, from, routeType }: { route: string; dir?: number; from?: string; routeType?: number | null }) {
	return (
		<Link
			class={`chip ${lineClass(route, routeType)}`}
			href={href.route(route, dir, from)}
			prefetch={dir != null ? () => prefetch(paths.routeStops(route, dir), 3_600_000) : undefined}
			title={`Route ${route}`}
		>
			{route}
		</Link>
	);
}

/**
 * [text] with each " → " drawn as the Material arrow icon, as the app's search rows do: the
 * font's "→" sits small and low beside the name. The text stays a plain string everywhere else.
 */
export function Arrowed({ text }: { text: string }) {
	const parts = text.split(' → ');
	return (
		<>
			{parts.map((p, i) => (
				<>
					{i > 0 && (
						<svg class="arrow" viewBox="0 0 24 24" aria-label="to" role="img">
							<path fill="currentColor" d="M12 4l-1.4 1.4 5.6 5.6H4v2h12.2l-5.6 5.6L12 20l8-8z" />
						</svg>
					)}
					{p}
				</>
			))}
		</>
	);
}

// Material icons: directions_bus, tram, train.
const MODE_PATHS: Record<TransitMode, string> = {
	bus: 'M4 16c0 .88.39 1.67 1 2.22V20c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h8v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1.78c.61-.55 1-1.34 1-2.22V6c0-3.5-3.58-4-8-4s-8 .5-8 4v10zm3.5 1c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm9 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm1.5-6H6V6h12v5z',
	tram: 'M19 16.94V8.5c0-2.79-2.61-3.4-6.01-3.49l.76-1.51H17V2H7v1.5h4.75l-.76 1.52C7.86 5.11 5 5.73 5 8.5v8.44c0 1.45 1.19 2.66 2.59 2.97L6 21.5v.5h2.23l2-2H14l2 2h2v-.5L16.5 20h-.08c1.69 0 2.58-1.37 2.58-3.06zm-7 1.56c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm5-4.5H7V9h10v5z',
	train: 'M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2h2v-.5L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-3.58-4-8-4zM7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-7H6V6h5v4zm2 0V6h5v4h-5zm3.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z',
};
const MODE_LABEL: Record<TransitMode, string> = { bus: 'Bus', tram: 'Luas', train: 'Train' };

/** The stop's mode logo (bus, Luas, train), as the app's StopModeIcon. Nothing when unknown. */
export function ModeIcon({ mode }: { mode: TransitMode | null }) {
	if (!mode) return null;
	return (
		<svg class="mode-icon" viewBox="0 0 24 24" role="img" aria-label={MODE_LABEL[mode]}>
			<path fill="currentColor" d={MODE_PATHS[mode]} />
		</svg>
	);
}

/** True for a stop code worth showing; stop_ids (letters in them) are addresses, not labels. */
export function isStopCode(code: string): boolean {
	return /^\d+$/.test(code) && code !== '0';
}

/** Same as the app's StaleBadge: shown only when what's on screen may be out of date. */
export function StaleBadge({ show }: { show: boolean }) {
	if (!show) return null;
	return (
		<span class="stale" title="The realtime feed is behind, or the last refresh failed">
			<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="currentColor" d="M1 21h22L12 2zm12-3h-2v-2h2zm0-4h-2v-4h2z" /></svg>
			stale
		</span>
	);
}

/** "Sched 08:21 · +2m", as the app's buildDriftLabel. */
export function driftText(sched: string, est: string | null, delaySeconds: number | null, realtime: boolean) {
	const base = `Sched ${wallClock(sched)}`;
	if (!realtime || est == null) return { text: base, cls: '' };
	const drift = delaySeconds != null ? Math.round(delaySeconds / 60) : minutesUntil(toMins(est), toMins(sched));
	if (drift > 0) return { text: `${base} · +${drift}m`, cls: 'late' };
	if (drift < 0) return { text: `${base} · ${drift}m`, cls: 'early' };
	return { text: `${base} · on time`, cls: '' };
}

export function DepartureRow({ d, stopCode, now }: { d: Departure; stopCode: string; now: number }) {
	const due = minutesUntil(toMins(d.estimated_departure ?? d.scheduled_departure), now);
	const drift = driftText(d.scheduled_departure, d.estimated_departure, d.delay_seconds, d.realtime);
	return (
		<li class="dep">
			<RouteChip route={d.route_short_name} dir={d.direction_id} from={stopCode} routeType={d.route_type} />
			<Link class="dep-main" href={href.trip(d.trip_id, stopCode)} prefetch={() => prefetch(paths.trip(d.trip_id))}>
				<span class="dep-head">{d.trip_headsign}</span>
				<span class={`dep-sub ${drift.cls}`}>{drift.text}</span>
			</Link>
			<span class="dep-due">
				<span class="due">{dueLabel(due)}</span>
				<span class={d.realtime ? 'live' : 'sched'}>{d.realtime ? 'Live' : 'Sched'}</span>
			</span>
		</li>
	);
}

export function Empty({ children }: { children: ComponentChildren }) {
	return <p class="empty">{children}</p>;
}

export function Skeleton({ rows = 6 }: { rows?: number }) {
	return (
		<ul class="list skeleton" aria-busy="true" aria-label="Loading">
			{Array.from({ length: rows }, (_, i) => <li key={i}><span /></li>)}
		</ul>
	);
}

export function Star({ on }: { on: boolean }) {
	return (
		<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
			{on
				? <path fill="currentColor" d="m12 17.3 6.2 3.7-1.6-7 5.4-4.7-7.1-.6L12 2 9.1 8.7 2 9.3l5.4 4.7-1.6 7z" />
				: <path fill="currentColor" d="m22 9.2-7.2-.6L12 2 9.2 8.6 2 9.2 7.5 14l-1.6 7L12 17.3l6.2 3.7-1.7-7zM12 15.4l-3.8 2.3 1-4.3-3.3-2.9 4.4-.4L12 6.1l1.7 4 4.4.4-3.3 2.9 1 4.3z" />}
		</svg>
	);
}

export function FilterIcon({ active }: { active: boolean }) {
	return (
		<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
			<path fill="currentColor" d={active ? 'M4 4h16l-6 8v6l-4 2v-8z' : 'M4 4h16l-6 8v6l-4 2v-8zm3.9 2 4.1 5.4V16l0 0V11.4L16.1 6z'} />
		</svg>
	);
}

export function MenuIcon() {
	return <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="currentColor" d="M3 18h18v-2H3zm0-5h18v-2H3zm0-7v2h18V6z" /></svg>;
}
