/**
 * Mock storage backend — no GTFS import needed.
 * Enable with STORAGE_BACKEND=mock in .env.
 */
import { randomUUID } from 'node:crypto';
import type { StorageBackend } from './interface.js';
import type { Stop, Departure, User, Wager, TrackedTrip, PlaceWagerInput, WagerResult } from './types.js';

// ---------------------------------------------------------------------------
// Static mock data
// ---------------------------------------------------------------------------

const STOPS: Stop[] = [
	{ stop_id: 's_279',  stop_code: '279',  stop_name: "O'Connell St (Stop 279)",       stop_lat: 53.3498, stop_lon: -6.2603 },
	{ stop_id: 's_280',  stop_code: '280',  stop_name: "O'Connell St (Stop 280)",       stop_lat: 53.3500, stop_lon: -6.2600 },
	{ stop_id: 's_765',  stop_code: '765',  stop_name: 'College Green (Stop 765)',       stop_lat: 53.3444, stop_lon: -6.2597 },
	{ stop_id: 's_766',  stop_code: '766',  stop_name: 'College Green (Stop 766)',       stop_lat: 53.3443, stop_lon: -6.2601 },
	{ stop_id: 's_1350', stop_code: '1350', stop_name: 'St Stephens Green (Stop 1350)', stop_lat: 53.3382, stop_lon: -6.2591 },
	{ stop_id: 's_2006', stop_code: '2006', stop_name: 'Westmoreland St (Stop 2006)',   stop_lat: 53.3462, stop_lon: -6.2591 },
	{ stop_id: 's_3258', stop_code: '3258', stop_name: 'Donnybrook (Stop 3258)',         stop_lat: 53.3212, stop_lon: -6.2283 },
	{ stop_id: 's_4386', stop_code: '4386', stop_name: 'Ranelagh (Stop 4386)',           stop_lat: 53.3268, stop_lon: -6.2592 },
	{ stop_id: 's_4921', stop_code: '4921', stop_name: 'Rathmines (Stop 4921)',          stop_lat: 53.3240, stop_lon: -6.2680 },
	{ stop_id: 's_5025', stop_code: '5025', stop_name: 'Terenure (Stop 5025)',           stop_lat: 53.3120, stop_lon: -6.2794 },
	{ stop_id: 's_5765', stop_code: '5765', stop_name: 'Dundrum (Stop 5765)',            stop_lat: 53.2921, stop_lon: -6.2478 },
	{ stop_id: 's_6071', stop_code: '6071', stop_name: 'Stillorgan (Stop 6071)',         stop_lat: 53.2852, stop_lon: -6.2028 },
	{ stop_id: 's_7634', stop_code: '7634', stop_name: 'Dún Laoghaire (Stop 7634)',     stop_lat: 53.2942, stop_lon: -6.1357 },
	{ stop_id: 's_348',  stop_code: '348',  stop_name: 'Parnell Sq (Stop 348)',          stop_lat: 53.3526, stop_lon: -6.2647 },
	{ stop_id: 's_1492', stop_code: '1492', stop_name: 'Drumcondra (Stop 1492)',         stop_lat: 53.3635, stop_lon: -6.2594 },
	{ stop_id: 's_1919', stop_code: '1919', stop_name: 'Glasnevin (Stop 1919)',          stop_lat: 53.3698, stop_lon: -6.2747 },
	{ stop_id: 's_2106', stop_code: '2106', stop_name: 'Finglas (Stop 2106)',            stop_lat: 53.3889, stop_lon: -6.3009 },
	{ stop_id: 's_3804', stop_code: '3804', stop_name: 'Blanchardstown SC (Stop 3804)', stop_lat: 53.3909, stop_lon: -6.3782 },
	{ stop_id: 's_496',  stop_code: '496',  stop_name: 'Aston Quay (Stop 496)',          stop_lat: 53.3460, stop_lon: -6.2635 },
	{ stop_id: 's_553',  stop_code: '553',  stop_name: 'Bachelors Walk (Stop 553)',      stop_lat: 53.3476, stop_lon: -6.2633 },
];

interface RouteSpec {
	route_id: string;
	short_name: string;
	headsigns: string[];
	/** minutes between scheduled departures */
	frequency: number;
}

const ROUTES: RouteSpec[] = [
	{ route_id: 'r_46a', short_name: '46A', headsigns: ['Dún Laoghaire', 'Phoenix Park'], frequency: 12 },
	{ route_id: 'r_145', short_name: '145', headsigns: ['Heuston Station', 'Bray'], frequency: 15 },
	{ route_id: 'r_16',  short_name: '16',  headsigns: ['Ballinteer', 'Finglas'],   frequency: 10 },
	{ route_id: 'r_39',  short_name: '39',  headsigns: ['Ongar', 'UCD'],            frequency: 20 },
	{ route_id: 'r_7',   short_name: '7',   headsigns: ['Liffey Valley SC', 'Bride\'s Glen'], frequency: 15 },
	{ route_id: 'r_155', short_name: '155', headsigns: ['Brides Glen', 'Charlestown SC'], frequency: 20 },
	{ route_id: 'r_44',  short_name: '44',  headsigns: ['Enniskerry', 'Dundrum'],   frequency: 30 },
	{ route_id: 'r_27',  short_name: '27',  headsigns: ['Clongriffin', 'Jobstown'], frequency: 15 },
	{ route_id: 'r_13',  short_name: '13',  headsigns: ['Harristown', 'Glenageary'], frequency: 12 },
	{ route_id: 'r_40',  short_name: '40',  headsigns: ['Liffey Valley', 'Santry'], frequency: 10 },
];

/** Routes that call at each stop */
const STOP_ROUTES: Record<string, string[]> = {
	's_279':  ['r_46a', 'r_16', 'r_13', 'r_40'],
	's_280':  ['r_7', 'r_27', 'r_155'],
	's_765':  ['r_46a', 'r_145', 'r_16', 'r_13'],
	's_766':  ['r_39', 'r_7', 'r_155'],
	's_1350': ['r_46a', 'r_16', 'r_44', 'r_13'],
	's_2006': ['r_145', 'r_27', 'r_155'],
	's_3258': ['r_46a', 'r_16', 'r_44'],
	's_4386': ['r_46a', 'r_145', 'r_16'],
	's_4921': ['r_16', 'r_155'],
	's_5025': ['r_16', 'r_44'],
	's_5765': ['r_44', 'r_145'],
	's_6071': ['r_46a', 'r_145'],
	's_7634': ['r_46a', 'r_145'],
	's_348':  ['r_13', 'r_40', 'r_27'],
	's_1492': ['r_13', 'r_40'],
	's_1919': ['r_13', 'r_40'],
	's_2106': ['r_40'],
	's_3804': ['r_39'],
	's_496':  ['r_7', 'r_27', 'r_46a'],
	's_553':  ['r_7', 'r_27'],
};

// ---------------------------------------------------------------------------
// Departure generation
// ---------------------------------------------------------------------------

const ROUTE_MAP = new Map(ROUTES.map((r) => [r.route_id, r]));

/** Seeded pseudo-random: stable per (stopId, routeId, departureIndex) */
function seededRandom(seed: number): number {
	const x = Math.sin(seed + 1) * 10_000;
	return x - Math.floor(x);
}

function generateDepartures(stopId: string, windowMinutes: number): Departure[] {
	const routeIds = STOP_ROUTES[stopId] ?? [];
	const now = new Date();
	const nowMins = now.getHours() * 60 + now.getMinutes();
	const departures: Departure[] = [];

	for (const routeId of routeIds) {
		const spec = ROUTE_MAP.get(routeId);
		if (!spec) continue;

		// Find the first departure after now, then emit one per frequency interval
		const offset = Math.ceil(nowMins / spec.frequency) * spec.frequency;
		let i = 0;
		for (let t = offset; t <= nowMins + windowMinutes; t += spec.frequency) {
			const seed = routeId.charCodeAt(2) * 1000 + stopId.length * 100 + i;
			const headsign = spec.headsigns[i % spec.headsigns.length];
			const tripId = `mock_${routeId}_${stopId}_${t}`;

			// ~40% chance of a delay, ~10% chance of being early
			const roll = seededRandom(seed + nowMins);
			let delaySecs: number | null = null;
			let estimated: string | null = null;
			let realtime = false;

			if (roll < 0.10) {
				delaySecs = -(1 + Math.floor(seededRandom(seed + 1) * 2)) * 60; // 1-2 min early
				realtime = true;
			} else if (roll < 0.50) {
				delaySecs = (1 + Math.floor(seededRandom(seed + 2) * 8)) * 60; // 1-8 min late
				realtime = true;
			}

			if (realtime && delaySecs !== null) {
				const estMins = t + Math.round(delaySecs / 60);
				const eh = Math.floor(estMins / 60) % 24;
				const em = estMins % 60;
				estimated = `${String(eh).padStart(2, '0')}:${String(Math.max(0, em)).padStart(2, '0')}`;
			}

			const h = Math.floor(t / 60) % 24;
			const m = t % 60;
			departures.push({
				trip_id: tripId,
				route_short_name: spec.short_name,
				trip_headsign: headsign,
				scheduled_departure: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`,
				estimated_departure: estimated,
				delay_seconds: delaySecs,
				realtime
			});
			i++;
		}
	}

	return departures.sort((a, b) =>
		(a.estimated_departure ?? a.scheduled_departure).localeCompare(
			b.estimated_departure ?? b.scheduled_departure
		)
	);
}

// ---------------------------------------------------------------------------
// Backend
// ---------------------------------------------------------------------------

export function createMockBackend(): StorageBackend {
	const stopByCode = new Map(STOPS.map((s) => [s.stop_code, s]));
	const stopById = new Map(STOPS.map((s) => [s.stop_id, s]));

	// In-memory wager state
	const users = new Map<string, User>();
	const wagers = new Map<string, Wager>();
	const trackedTrips = new Map<string, TrackedTrip>(); // key: `${tripId}|${stopId}`

	return {
		async isSeeded() {
			return true;
		},

		async searchStops(query: string) {
			const q = query.toLowerCase();
			return STOPS.filter(
				(s) => s.stop_name.toLowerCase().includes(q) || s.stop_code.includes(q)
			).slice(0, 20);
		},

		async getStopByCode(code: string) {
			return stopByCode.get(code) ?? null;
		},

		async getActiveServiceIds(_dayName: string, _date: string) {
			return ['mock_service'];
		},

		async getScheduledDepartures(stopId: string, _activeServiceIds: string[], windowMinutes = 90) {
			if (!stopById.has(stopId)) return [];
			return generateDepartures(stopId, windowMinutes);
		},

		async getOrCreateUser(userId: string): Promise<User> {
			if (!users.has(userId)) {
				users.set(userId, { user_id: userId, balance: 100.0, created_at: new Date().toISOString() });
			}
			return users.get(userId)!;
		},

		async placeWager(input: PlaceWagerInput): Promise<Wager> {
			const user = await (this as StorageBackend).getOrCreateUser(input.userId);
			if (user.balance < input.stake) throw new Error('Insufficient balance');
			user.balance -= input.stake;
			const wager: Wager = {
				wager_id: randomUUID(), user_id: input.userId,
				trip_id: input.tripId, stop_id: input.stopId,
				scheduled_departure: input.scheduledDeparture, scheduled_date: input.scheduledDate,
				wager_type: input.wagerType, drift_minutes: input.driftMinutes,
				stake: input.stake, status: 'open',
				placed_at: new Date().toISOString(), resolved_at: null, payout: null
			};
			wagers.set(wager.wager_id, wager);
			const key = `${input.tripId}|${input.stopId}`;
			if (!trackedTrips.has(key)) {
				trackedTrips.set(key, {
					trip_id: input.tripId, stop_id: input.stopId,
					scheduled_departure: input.scheduledDeparture, scheduled_date: input.scheduledDate,
					last_estimated_departure: null, last_seen_at: null, status: 'tracking'
				});
			}
			return wager;
		},

		async getOpenWagers(tripId: string, stopId: string): Promise<Wager[]> {
			return [...wagers.values()].filter(
				w => w.trip_id === tripId && w.stop_id === stopId && w.status === 'open'
			);
		},

		async settleWagers(tripId: string, stopId: string, results: WagerResult[], outcome: 'arrived' | 'canceled'): Promise<void> {
			const now = new Date().toISOString();
			for (const { wagerId, status, payout } of results) {
				const w = wagers.get(wagerId);
				if (!w) continue;
				w.status = status; w.payout = payout; w.resolved_at = now;
				if (payout > 0) {
					const u = users.get(w.user_id);
					if (u) u.balance += payout;
				}
			}
			const key = `${tripId}|${stopId}`;
			const t = trackedTrips.get(key);
			if (t) t.status = outcome === 'canceled' ? 'canceled' : 'arrived';
		},

		async getUserWagers(userId: string): Promise<Wager[]> {
			return [...wagers.values()]
				.filter(w => w.user_id === userId)
				.sort((a, b) => b.placed_at.localeCompare(a.placed_at))
				.slice(0, 30);
		},

		async getTrackedTrips(): Promise<TrackedTrip[]> {
			return [...trackedTrips.values()].filter(t => t.status === 'tracking');
		},

		async updateTrackedTrip(tripId: string, stopId: string, patch: {
			lastEstimatedDeparture?: string | null;
			lastSeenAt?: string | null;
			status?: 'tracking' | 'arrived' | 'canceled';
		}): Promise<void> {
			const key = `${tripId}|${stopId}`;
			const t = trackedTrips.get(key);
			if (!t) return;
			if (patch.lastEstimatedDeparture !== undefined) t.last_estimated_departure = patch.lastEstimatedDeparture;
			if (patch.lastSeenAt !== undefined) t.last_seen_at = patch.lastSeenAt;
			if (patch.status !== undefined) t.status = patch.status;
		}
	};
}
