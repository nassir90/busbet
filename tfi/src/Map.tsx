import { useEffect, useRef, useState } from 'preact/hooks';
import type * as L from 'leaflet';

export interface MapBus { id: string; lat: number; lon: number; label: string; title: string; dim: boolean; bearing: number | null }
export interface MapStop { lat: number; lon: number; label: string; primary?: boolean }

/**
 * Leaflet is loaded on first use, so it isn't in the home screen's bundle. The promise is shared:
 * every map after the first starts at once.
 */
let leaflet: Promise<typeof L> | null = null;
function loadLeaflet() {
	leaflet ??= Promise.all([import('leaflet'), import('leaflet/dist/leaflet.css')]).then(([m]) => (m as unknown as { default: typeof L }).default ?? m);
	return leaflet;
}

// OpenStreetMap's own tiles. The Android app uses CARTO's basemaps, but those answer browser
// requests (which carry a Referer) with an "API key required" tile. Dark mode darkens these in CSS.
const TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

function esc(s: string) {
	return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

export function BusMap({ stops, buses, shape, fit = 'stop', height = 220 }: {
	stops: MapStop[];
	buses: MapBus[];
	shape?: Array<{ lat: number; lon: number }>;
	/** 'stop' centres on the primary stop; 'all' fits the shape and buses. */
	fit?: 'stop' | 'all';
	height?: number;
}) {
	const el = useRef<HTMLDivElement>(null);
	const map = useRef<L.Map | null>(null);
	const layers = useRef<L.LayerGroup | null>(null);
	const lib = useRef<typeof L | null>(null);
	const fitted = useRef(false);
	const [ready, setReady] = useState(false);

	useEffect(() => {
		let alive = true;
		loadLeaflet().then((Lf) => {
			if (!alive || !el.current) return;
			lib.current = Lf;
			const m = Lf.map(el.current, { zoomControl: false, attributionControl: true });
			Lf.tileLayer(TILE_URL, { maxZoom: 19, attribution: ATTRIBUTION, className: 'tiles' }).addTo(m);
			layers.current = Lf.layerGroup().addTo(m);
			map.current = m;
			setReady(true);
		});
		return () => {
			alive = false;
			map.current?.remove();
			map.current = null;
		};
	}, []);

	useEffect(() => {
		const Lf = lib.current, m = map.current, g = layers.current;
		if (!ready || !Lf || !m || !g) return;
		g.clearLayers();
		const pts: L.LatLngExpression[] = [];
		if (shape && shape.length >= 2) {
			const line = shape.map((p) => [p.lat, p.lon] as [number, number]);
			Lf.polyline(line, { className: 'shape-line', weight: 4, opacity: 0.85 }).addTo(g);
			pts.push(...line);
		}
		// SVG attributes don't resolve CSS variables, so the palette colour is read off the root.
		const primary = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim() || '#6750A4';
		for (const s of stops) {
			Lf.circleMarker([s.lat, s.lon], {
				radius: s.primary ? 8 : 4,
				color: s.primary ? primary : '#fff', weight: s.primary ? 3 : 1.5,
				fillColor: s.primary ? '#fff' : primary, fillOpacity: 1,
			}).bindTooltip(esc(s.label)).addTo(g);
			if (s.primary) pts.push([s.lat, s.lon]);
		}
		for (const b of buses) {
			const rot = b.bearing != null ? `<i style="transform:rotate(${b.bearing}deg)"></i>` : '';
			Lf.marker([b.lat, b.lon], {
				icon: Lf.divIcon({
					className: `bus-pin${b.dim ? ' dim' : ''}`,
					html: `${rot}<b>${esc(b.label)}</b>`,
					iconSize: [36, 22],
					iconAnchor: [18, 11],
				}),
				title: b.title,
				zIndexOffset: b.dim ? 0 : 500,
			}).addTo(g);
			pts.push([b.lat, b.lon]);
		}
		// Frame once; after that the user's pan and zoom win over the 30s refresh.
		if (!fitted.current && pts.length) {
			fitted.current = true;
			const primary = stops.find((s) => s.primary);
			if (fit === 'stop' && primary) {
				const near = buses.map((b) => [b.lat, b.lon] as [number, number]);
				if (near.length) m.fitBounds(Lf.latLngBounds([[primary.lat, primary.lon], ...near]), { padding: [24, 24], maxZoom: 16 });
				else m.setView([primary.lat, primary.lon], 15);
			} else {
				m.fitBounds(Lf.latLngBounds(pts), { padding: [20, 20], maxZoom: 16 });
			}
		}
	}, [ready, stops, buses, shape, fit]);

	// Height through a variable, so the desktop layout can make the map fill its column instead.
	return <div class="map" ref={el} style={{ '--map-h': `${height}px` }} role="region" aria-label="Map" />;
}
