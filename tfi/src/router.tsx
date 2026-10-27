import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';

/**
 * Query-string routing: the path never changes, so the site works at / and behind any prefix
 * without knowing which. `?code=` and `?route=&direction=` are the old server-rendered page's
 * URLs, still accepted so bookmarks keep working.
 */
export type View =
	| { name: 'home' }
	| { name: 'stop'; code: string }
	| { name: 'route'; route: string; dir: number | null; from: string | null }
	| { name: 'trip'; id: string; from: string | null }
	| { name: 'settings' };

export function parse(search: string): View {
	const q = new URLSearchParams(search);
	const stop = q.get('stop') ?? q.get('code');
	const trip = q.get('trip');
	const route = q.get('route');
	if (q.has('settings')) return { name: 'settings' };
	if (trip) return { name: 'trip', id: trip, from: q.get('from') };
	if (route) {
		const d = q.get('dir') ?? q.get('direction');
		return { name: 'route', route, dir: d === '0' || d === '1' ? Number(d) : null, from: q.get('from') };
	}
	if (stop) return { name: 'stop', code: stop.trim() };
	return { name: 'home' };
}

export const href = {
	home: () => './',
	settings: () => '?settings',
	stop: (code: string) => `?stop=${encodeURIComponent(code)}`,
	route: (route: string, dir?: number, from?: string) =>
		`?route=${encodeURIComponent(route)}${dir != null ? `&dir=${dir}` : ''}${from ? `&from=${encodeURIComponent(from)}` : ''}`,
	trip: (id: string, from?: string) => `?trip=${encodeURIComponent(id)}${from ? `&from=${encodeURIComponent(from)}` : ''}`,
};

// ---------------------------------------------------------------------------
// Navigation timing
//
// Each navigation is measured from the click to the first frame in which the new view shows real
// data, not a "Loading…" placeholder. Kept on window.__tfiNav for inspection, and logged when
// localStorage 'tfi-perf' is set.
// ---------------------------------------------------------------------------

interface NavTiming { to: string; ms: number; cached: boolean }
declare global { interface Window { __tfiNav?: NavTiming[] } }

let navStart: { at: number; to: string } | null = null;

export function useNavReady(ready: boolean, cached: boolean) {
	useEffect(() => {
		if (!ready || !navStart) return;
		const start = navStart;
		navStart = null;
		requestAnimationFrame(() => {
			const t: NavTiming = { to: start.to, ms: Math.round(performance.now() - start.at), cached };
			(window.__tfiNav ??= []).push(t);
			let log = false;
			try { log = localStorage.getItem('tfi-perf') != null; } catch { /* no storage */ }
			if (log) console.info(`[nav] ${t.to} ${t.ms}ms${cached ? ' (cache)' : ''}`);
		});
	}, [ready]);
}

// ---------------------------------------------------------------------------

const listeners = new Set<() => void>();

export function navigate(to: string, replace = false) {
	const url = new URL(to, location.href);
	navStart = { at: performance.now(), to: url.search || '/' };
	const state = { scroll: 0 };
	if (replace) history.replaceState(state, '', url);
	else {
		// Remember where the page we're leaving was scrolled, for Back.
		history.replaceState({ scroll: window.scrollY }, '');
		history.pushState(state, '', url);
	}
	listeners.forEach((l) => l());
	window.scrollTo(0, 0);
}

window.addEventListener('popstate', (e) => {
	navStart = { at: performance.now(), to: location.search || '/' };
	listeners.forEach((l) => l());
	const y = (e.state as { scroll?: number } | null)?.scroll ?? 0;
	// After the view has rendered from cache.
	requestAnimationFrame(() => window.scrollTo(0, y));
});

export function useView(): View {
	const [view, setView] = useState(() => parse(location.search));
	useEffect(() => {
		const l = () => setView(parse(location.search));
		listeners.add(l);
		return () => { listeners.delete(l); };
	}, []);
	return view;
}

type LinkProps = Omit<JSX.HTMLAttributes<HTMLAnchorElement>, 'href'> & {
	href: string;
	/** Called on hover, focus or touch: the moment to warm the cache for where this goes. */
	prefetch?: () => void;
	children?: ComponentChildren;
};

export function Link({ href: to, prefetch, children, ...rest }: LinkProps) {
	const warmed = useRef(false);
	const warm = () => {
		if (warmed.current || !prefetch) return;
		warmed.current = true;
		prefetch();
	};
	return (
		<a
			{...rest}
			href={to}
			onPointerEnter={warm}
			onFocus={warm}
			onTouchStart={warm}
			onClick={(e) => {
				if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
				e.preventDefault();
				warm();
				navigate(to);
			}}
		>
			{children}
		</a>
	);
}
