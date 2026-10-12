/**
 * Distances and a flat spatial index for "which stops are near this point".
 *
 * Everything walking-related in the planner is straight-line distance times a detour factor. That
 * is the main simplification against a street-routed planner (MOTIS, OTP): it is fine for
 * choosing between stops a few hundred metres apart, and wrong where a river, railway or motorway
 * makes the real walk much longer. See docs/ROUTE_PLANNING.md for how far off it measured.
 */

const EARTH_RADIUS_M = 6_371_008.8;

export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
	const toRad = Math.PI / 180;
	const dLat = (lat2 - lat1) * toRad;
	const dLon = (lon2 - lon1) * toRad;
	const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
	return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

export interface WalkModel {
	/** Metres per second on foot. 1.3 m/s is 4.7 km/h, a typical urban pace with crossings. */
	speedMps: number;
	/** Real walking distance over straight-line distance. 1.3 is the usual figure for city grids. */
	detour: number;
}

export const DEFAULT_WALK: WalkModel = { speedMps: 1.3, detour: 1.3 };

export function walkSeconds(straightMeters: number, walk: WalkModel = DEFAULT_WALK): number {
	return Math.round((straightMeters * walk.detour) / walk.speedMps);
}

/**
 * Buckets points into a lat/lon grid of roughly [cellMeters] square cells, so a radius query
 * looks at a handful of cells instead of every stop in the country.
 */
export class GridIndex {
	private readonly cellLat: number;
	private readonly cellLon: number;
	private readonly cells = new Map<string, number[]>();

	constructor(private readonly lat: Float64Array, private readonly lon: Float64Array, cellMeters = 500) {
		this.cellLat = cellMeters / 111_320;
		// Ireland sits around 53°N; cells stay near square across the island.
		this.cellLon = cellMeters / (111_320 * Math.cos((53.3 * Math.PI) / 180));
		for (let i = 0; i < lat.length; i++) {
			const key = this.key(lat[i], lon[i]);
			let list = this.cells.get(key);
			if (!list) this.cells.set(key, (list = []));
			list.push(i);
		}
	}

	private key(lat: number, lon: number): string {
		return `${Math.floor(lat / this.cellLat)}:${Math.floor(lon / this.cellLon)}`;
	}

	/** Indices within [radiusMeters] of the point, with their straight-line distances. */
	within(lat: number, lon: number, radiusMeters: number): { index: number; meters: number }[] {
		const out: { index: number; meters: number }[] = [];
		const rLat = Math.ceil(radiusMeters / 111_320 / this.cellLat);
		const rLon = Math.ceil(radiusMeters / (111_320 * Math.cos((lat * Math.PI) / 180)) / this.cellLon);
		const cy = Math.floor(lat / this.cellLat);
		const cx = Math.floor(lon / this.cellLon);
		for (let y = cy - rLat; y <= cy + rLat; y++) {
			for (let x = cx - rLon; x <= cx + rLon; x++) {
				const list = this.cells.get(`${y}:${x}`);
				if (!list) continue;
				for (const i of list) {
					const m = haversineMeters(lat, lon, this.lat[i], this.lon[i]);
					if (m <= radiusMeters) out.push({ index: i, meters: m });
				}
			}
		}
		return out;
	}
}
