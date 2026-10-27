import type { ComponentChildren } from 'preact';
import { type Departure, paths, prefetch } from './api';
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

export function RouteChip({ route, dir, from }: { route: string; dir?: number; from?: string }) {
	return (
		<Link
			class="chip"
			href={href.route(route, dir, from)}
			prefetch={dir != null ? () => prefetch(paths.routeStops(route, dir), 3_600_000) : undefined}
			title={`Route ${route}`}
		>
			{route}
		</Link>
	);
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
			<RouteChip route={d.route_short_name} dir={d.direction_id} from={stopCode} />
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
