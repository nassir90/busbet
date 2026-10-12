/**
 * Walk + public transport journey planning on top of RAPTOR: finds the stops near each end, runs
 * the search a few times at successively later departures to get a spread of options, and turns
 * the raw legs into itineraries a client can draw.
 *
 * Coordinates in a request are never logged or kept; the request object goes out of scope with
 * the response.
 */
import type { transit_realtime } from 'gtfs-realtime-bindings';
import { DEFAULT_WALK, haversineMeters, walkSeconds, type WalkModel } from './geo.js';
import { extractJourney, INF, raptor, type RawLeg } from './raptor.js';
import { realtimeOverlay, type RealtimeOverlay } from './realtime.js';
import type { ShapePoint, TimetableSource } from './sources.js';
import { buildTimetable, serviceDateOf, type BuildOptions, type Timetable } from './timetable.js';

export interface LatLon { lat: number; lon: number }

export interface PlanRequest {
	from: LatLon;
	to: LatLon;
	/** Epoch seconds. */
	departAt: number;
	maxItineraries?: number;
	/** Upper bound on straight-line metres walked to the first stop or from the last. */
	maxAccessMeters?: number;
	walk?: WalkModel;
	/** Latest realtime feed, when the departure is close enough to now for it to matter. */
	feed?: transit_realtime.FeedMessage | null;
}

export interface PlacePoint {
	name: string | null;
	lat: number;
	lon: number;
	stop_id?: string;
	stop_code?: string;
}

export type LegMode = 'walk' | 'bus' | 'tram' | 'rail' | 'subway' | 'ferry' | 'other';

export interface Leg {
	mode: LegMode;
	from: PlacePoint;
	to: PlacePoint;
	/** Epoch seconds. For a vehicle, the predicted time when realtime has one. */
	start: number;
	end: number;
	duration_s: number;
	distance_m?: number;
	route?: string;
	route_long_name?: string;
	route_type?: number;
	agency_id?: string;
	headsign?: string;
	trip_id?: string;
	scheduled_start?: number;
	scheduled_end?: number;
	/** Seconds late at boarding; null when the feed says nothing about this trip. */
	delay_s?: number | null;
	realtime?: boolean;
	/** Stops the vehicle calls at between boarding and alighting. */
	intermediate_stops?: number;
	/** [lat, lon] pairs. Road geometry for vehicles when the shape is known; straight for walks. */
	geometry: [number, number][];
}

export interface Itinerary {
	departure: number;
	arrival: number;
	duration_s: number;
	transfers: number;
	walk_s: number;
	walk_m: number;
	legs: Leg[];
}

export interface PlanResponse {
	itineraries: Itinerary[];
	service_date: string;
	realtime: { used: boolean; feed_timestamp: number | null; updated_trips: number; ignored_trips: number };
	debug: { raptor_runs: number; raptor_ms: number; access_stops: number; egress_stops: number; timetable_build_ms: number };
}

const MODE_BY_TYPE: Record<number, LegMode> = { 0: 'tram', 1: 'subway', 2: 'rail', 3: 'bus', 4: 'ferry' };

function modeOf(routeType: number): LegMode {
	if (routeType in MODE_BY_TYPE) return MODE_BY_TYPE[routeType];
	if (routeType >= 100 && routeType < 200) return 'rail';
	if (routeType >= 700 && routeType < 800) return 'bus';
	if (routeType >= 900 && routeType < 1000) return 'tram';
	return 'other';
}

export class Planner {
	private cache = new Map<string, Promise<Timetable>>();
	private shapeCache = new Map<string, ShapePoint[]>();

	constructor(
		private readonly source: TimetableSource,
		private readonly timeZone = 'Europe/Dublin',
		private readonly buildOptions: BuildOptions = {},
		/** How many service days to keep built. Each is ~30–60 MB of typed arrays. */
		private readonly keepDays = 2,
		/**
		 * Builds timetables somewhere other than this thread, e.g. [buildTimetableInChild], so the
		 * build's garbage doesn't stay resident. Defaults to building in-line from [source].
		 */
		private readonly builder?: (date: string) => Promise<Timetable>,
	) {}

	timetableFor(date: string): Promise<Timetable> {
		let p = this.cache.get(date);
		if (!p) {
			p = this.builder ? this.builder(date) : buildTimetable(this.source, date, this.timeZone, this.buildOptions);
			p.catch(() => this.cache.delete(date));
			this.cache.set(date, p);
			while (this.cache.size > this.keepDays) this.cache.delete(this.cache.keys().next().value!);
		}
		return p;
	}

	async plan(req: PlanRequest): Promise<PlanResponse> {
		const walk = req.walk ?? DEFAULT_WALK;
		const maxItins = Math.min(req.maxItineraries ?? 5, 10);
		const accessMeters = req.maxAccessMeters ?? 1000;
		const date = serviceDateOf(req.departAt, this.timeZone);
		const tt = await this.timetableFor(date);
		const t0 = req.departAt - tt.epochZero;

		const overlay: RealtimeOverlay | null = req.feed ? realtimeOverlay(tt, req.feed) : null;

		const nearby = (p: LatLon) => {
			let found = tt.grid.within(p.lat, p.lon, accessMeters).filter((x) => tt.stopPatternsStart[x.index + 1] > tt.stopPatternsStart[x.index]);
			// Out in the country the nearest stop can be further than a city walk; widen once.
			if (found.length === 0) {
				found = tt.grid.within(p.lat, p.lon, accessMeters * 2.5).filter((x) => tt.stopPatternsStart[x.index + 1] > tt.stopPatternsStart[x.index]);
			}
			return found.map((x) => ({ stop: x.index, meters: x.meters, walk: walkSeconds(x.meters, walk) }));
		};
		const accessStops = nearby(req.from);
		const egressStops = nearby(req.to);
		const egressWalkByStop = new Map(egressStops.map((e) => [e.stop, e.walk]));
		const accessWalkByStop = new Map(accessStops.map((a) => [a.stop, a.walk]));

		const itineraries: Itinerary[] = [];
		const seen = new Set<string>();
		let runs = 0;
		let raptorMs = 0;

		const direct = haversineMeters(req.from.lat, req.from.lon, req.to.lat, req.to.lon);
		const directWalkS = walkSeconds(direct, walk);
		if (direct <= 3000) {
			itineraries.push(this.walkOnly(req, directWalkS, direct * walk.detour));
			seen.add('walk');
		}

		let depart = t0;
		const horizon = t0 + 2 * 3600;
		while (runs < 6 && depart < horizon && itineraries.filter((i) => i.legs.length > 1 || i.legs[0].mode !== 'walk').length < maxItins) {
			const started = performance.now();
			const r = raptor(tt, {
				access: accessStops.map((a) => ({ stop: a.stop, time: depart + a.walk })),
				egress: egressStops.map((e) => ({ stop: e.stop, walk: e.walk })),
				maxRounds: 5,
				times: overlay ? { arr: overlay.arr, dep: overlay.dep, nonFifoPatterns: overlay.nonFifoPatterns } : undefined,
				bannedTrips: overlay?.banned,
			});
			raptorMs += performance.now() - started;
			runs++;

			let bestSoFar = INF;
			let earliestBoard = INF;
			for (let k = 1; k <= r.rounds; k++) {
				const arrival = r.targetArrival[k];
				if (arrival >= bestSoFar) continue;
				bestSoFar = arrival;
				const raw = extractJourney(tt, r, k, egressWalkByStop.get(r.targetStop[k]) ?? 0);
				if (!raw) continue;
				const itin = await this.toItinerary(tt, raw, req, accessWalkByStop, overlay, walk);
				if (!itin) continue;
				// Nobody takes a bus to arrive later than walking the whole way would.
				if (direct <= 3000 && itin.arrival >= req.departAt + directWalkS) continue;
				const key = itin.legs.filter((l) => l.trip_id).map((l) => `${l.trip_id}@${l.from.stop_id}`).join('>');
				const firstBoard = raw.find((l) => l.kind === 'transit');
				if (firstBoard) {
					const d = (overlay?.dep ?? tt.dep)[tt.tripTimesStart[firstBoard.trip!] + firstBoard.boardPos!];
					earliestBoard = Math.min(earliestBoard, d);
				}
				if (seen.has(key)) continue;
				seen.add(key);
				itineraries.push(itin);
			}
			if (earliestBoard >= INF) break;
			// Next run: just after the first vehicle we found leaves, to surface the next one.
			depart = Math.max(depart + 60, earliestBoard - this.minAccess(accessStops) + 60);
		}

		return {
			itineraries: transferPenaltyFilter(dominanceFilter(itineraries))
				.sort((a, b) => a.arrival - b.arrival || a.transfers - b.transfers)
				.slice(0, maxItins + 1),
			service_date: tt.date,
			realtime: {
				used: !!overlay,
				feed_timestamp: overlay?.feedTimestamp ?? null,
				updated_trips: overlay?.updatedTrips ?? 0,
				ignored_trips: overlay?.implausibleTrips ?? 0,
			},
			debug: {
				raptor_runs: runs,
				raptor_ms: Math.round(raptorMs * 10) / 10,
				access_stops: accessStops.length,
				egress_stops: egressStops.length,
				timetable_build_ms: tt.buildMs,
			},
		};
	}

	private minAccess(access: { walk: number }[]): number {
		return access.reduce((m, a) => Math.min(m, a.walk), INF);
	}

	private walkOnly(req: PlanRequest, seconds: number, meters: number): Itinerary {
		return {
			departure: req.departAt,
			arrival: req.departAt + seconds,
			duration_s: seconds,
			transfers: 0,
			walk_s: seconds,
			walk_m: Math.round(meters),
			legs: [{
				mode: 'walk',
				from: { name: null, ...req.from },
				to: { name: null, ...req.to },
				start: req.departAt,
				end: req.departAt + seconds,
				duration_s: seconds,
				distance_m: Math.round(meters),
				geometry: [[req.from.lat, req.from.lon], [req.to.lat, req.to.lon]],
			}],
		};
	}

	private stopPoint(tt: Timetable, s: number): PlacePoint {
		return { name: tt.stopName[s], lat: tt.stopLat[s], lon: tt.stopLon[s], stop_id: tt.stopId[s], stop_code: tt.stopCode[s] };
	}

	private async toItinerary(
		tt: Timetable,
		raw: RawLeg[],
		req: PlanRequest,
		accessWalk: Map<number, number>,
		overlay: RealtimeOverlay | null,
		walk: WalkModel,
	): Promise<Itinerary | null> {
		const arr = overlay?.arr ?? tt.arr;
		const dep = overlay?.dep ?? tt.dep;
		const z = tt.epochZero;
		const legs: Leg[] = [];

		// Resolve vehicle legs first: walks are pinned to them (leave as late as possible).
		const transit = raw.filter((l) => l.kind === 'transit');
		if (!transit.length) return null;

		for (let i = 0; i < raw.length; i++) {
			const l = raw[i];
			if (l.kind === 'transit') {
				const t = l.trip!;
				const base = tt.tripTimesStart[t];
				const p = tt.tripPattern[t];
				const ps = tt.patternStopsStart[p];
				const route = tt.patternRoute[p];
				const start = dep[base + l.boardPos!];
				const end = arr[base + l.alightPos!];
				const rt = overlay?.tripHasRealtime[t] === 1;
				legs.push({
					mode: modeOf(tt.routeType[route]),
					from: this.stopPoint(tt, l.fromStop),
					to: this.stopPoint(tt, l.toStop),
					start: z + start,
					end: z + end,
					duration_s: end - start,
					// Irish Rail names most lines just "rail"; the long name ("Dublin - Maynooth") says more.
					route: !tt.routeShortName[route] || tt.routeShortName[route] === 'rail'
						? tt.routeLongName[route] : tt.routeShortName[route],
					route_long_name: tt.routeLongName[route],
					route_type: tt.routeType[route],
					agency_id: tt.routeAgency[route],
					headsign: tt.tripHeadsign[t],
					trip_id: tt.tripId[t],
					scheduled_start: z + tt.dep[base + l.boardPos!],
					scheduled_end: z + tt.arr[base + l.alightPos!],
					delay_s: rt ? start - tt.dep[base + l.boardPos!] : null,
					realtime: rt,
					intermediate_stops: l.alightPos! - l.boardPos! - 1,
					geometry: await this.vehicleGeometry(tt, t, ps, l.boardPos!, l.alightPos!),
				});
			} else {
				legs.push(null as unknown as Leg); // filled below, once neighbours are known
			}
		}

		for (let i = 0; i < raw.length; i++) {
			const l = raw[i];
			if (l.kind === 'transit') continue;
			if (l.kind === 'access') {
				const next = legs[i + 1];
				const secs = accessWalk.get(l.toStop) ?? 0;
				const to = this.stopPoint(tt, l.toStop);
				legs[i] = walkLeg({ name: null, ...req.from }, to, next.start - secs, secs, walk);
			} else if (l.kind === 'egress') {
				const prev = legs[i - 1];
				const from = this.stopPoint(tt, l.fromStop);
				const secs = l.end - l.start;
				legs[i] = walkLeg(from, { name: null, ...req.to }, prev.end, secs, walk);
			} else {
				const prev = legs[i - 1];
				const secs = l.end - l.start;
				legs[i] = walkLeg(this.stopPoint(tt, l.fromStop), this.stopPoint(tt, l.toStop), prev.end, secs, walk);
			}
		}

		// Zero-length walks (boarding at the stop you're standing on) are noise, and a transfer
		// walk followed by the walk to the destination is one walk to the person doing it.
		const kept: Leg[] = [];
		for (const l of legs) {
			if (l.mode === 'walk' && l.duration_s === 0) continue;
			const last = kept[kept.length - 1];
			if (l.mode === 'walk' && last?.mode === 'walk') {
				kept[kept.length - 1] = {
					...last,
					to: l.to,
					end: l.end,
					duration_s: last.duration_s + l.duration_s,
					distance_m: (last.distance_m ?? 0) + (l.distance_m ?? 0),
					geometry: [...last.geometry, ...l.geometry.slice(1)],
				};
				continue;
			}
			kept.push(l);
		}
		const departure = kept[0].start;
		const arrival = kept[kept.length - 1].end;
		const walks = kept.filter((l) => l.mode === 'walk');
		return {
			departure,
			arrival,
			duration_s: arrival - departure,
			transfers: transit.length - 1,
			walk_s: walks.reduce((s, l) => s + l.duration_s, 0),
			walk_m: walks.reduce((s, l) => s + (l.distance_m ?? 0), 0),
			legs: kept,
		};
	}

	private async vehicleGeometry(tt: Timetable, trip: number, ps: number, from: number, to: number): Promise<[number, number][]> {
		const stops: [number, number][] = [];
		for (let i = from; i <= to; i++) {
			const s = tt.patternStops[ps + i];
			stops.push([tt.stopLat[s], tt.stopLon[s]]);
		}
		const shapeId = tt.tripShapeId[trip];
		if (!shapeId) return stops;
		let shape = this.shapeCache.get(shapeId);
		if (!shape) {
			shape = await this.source.shape(shapeId);
			this.shapeCache.set(shapeId, shape);
			if (this.shapeCache.size > 500) this.shapeCache.delete(this.shapeCache.keys().next().value!);
		}
		if (shape.length < 2) return stops;
		return sliceShape(shape, stops[0], stops[stops.length - 1]);
	}
}

function walkLeg(from: PlacePoint, to: PlacePoint, start: number, secs: number, walk: WalkModel): Leg {
	const straight = haversineMeters(from.lat, from.lon, to.lat, to.lon);
	return {
		mode: 'walk',
		from,
		to,
		start,
		end: start + secs,
		duration_s: secs,
		distance_m: Math.round(straight * walk.detour),
		geometry: [[from.lat, from.lon], [to.lat, to.lon]],
	};
}

/** The part of a route's shape between the points nearest its boarding and alighting stops. */
export function sliceShape(shape: ShapePoint[], a: [number, number], b: [number, number]): [number, number][] {
	const nearest = (pt: [number, number], fromIdx: number) => {
		let bi = fromIdx;
		let bd = Infinity;
		for (let i = fromIdx; i < shape.length; i++) {
			const d = haversineMeters(pt[0], pt[1], shape[i].lat, shape[i].lon);
			if (d < bd) { bd = d; bi = i; }
		}
		return bi;
	};
	const i = nearest(a, 0);
	const j = nearest(b, i);
	const out: [number, number][] = [a];
	for (let k = i; k <= j; k++) out.push([shape[k].lat, shape[k].lon]);
	out.push(b);
	return out;
}

/** Drops an itinerary when another leaves no earlier, arrives no later and changes no more often. */
export function dominanceFilter(list: Itinerary[]): Itinerary[] {
	return list.filter((a) => !list.some((b) => b !== a
		&& b.departure >= a.departure && b.arrival <= a.arrival && b.transfers <= a.transfers
		&& (b.departure > a.departure || b.arrival < a.arrival || b.transfers < a.transfers)));
}

/**
 * Minutes a change has to save to be worth showing. Pareto-optimal isn't the same as sensible: a
 * four-change journey that arrives eight minutes before a direct bus is technically better and
 * practically worse — every change is a chance to miss the connection.
 */
export const CHANGE_PENALTY_S = 6 * 60;

/** Drops an itinerary when one with fewer changes, leaving about as early, is barely slower. */
export function transferPenaltyFilter(list: Itinerary[]): Itinerary[] {
	return list.filter((a) => !list.some((b) => b !== a
		&& b.transfers < a.transfers
		&& b.departure >= a.departure - 5 * 60
		&& b.arrival - a.arrival <= CHANGE_PENALTY_S * (a.transfers - b.transfers)));
}
