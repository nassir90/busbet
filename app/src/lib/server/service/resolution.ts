/**
 * Wager resolution service.
 * Checks tracked trips against the live GTFS-R feed and settles open wagers.
 */
import { env } from '$env/dynamic/private';
import { getStorage } from '../storage/index.js';
import type { TrackedTrip, WagerResult } from '../storage/types.js';

const serviceUrl = () => env.GTFSR_STOP_TIMES_URL ?? 'http://localhost:8110';

type TripUpdate = {
	scheduleRelationship: number;
	stopTimeUpdates: { stopId: string; stopSequence: number; arrivalDelay: number; departureDelay: number }[];
};

async function getTripUpdates(): Promise<Record<string, TripUpdate> | null> {
	try {
		const res = await fetch(`${serviceUrl()}/trip-updates`);
		if (!res.ok) return null;
		return await res.json();
	} catch {
		return null;
	}
}

function timeToMins(hhmm: string): number {
	const [h, m] = hhmm.split(':').map(Number);
	return h * 60 + m;
}

function scheduledMs(date: string, time: string): number {
	const year = parseInt(date.substring(0, 4));
	const month = parseInt(date.substring(4, 6)) - 1;
	const day = parseInt(date.substring(6, 8));
	const [h, m] = time.split(':').map(Number);
	return new Date(year, month, day, h, m, 0).getTime();
}

async function settleTrip(trip: TrackedTrip, outcome: 'arrived' | 'canceled'): Promise<void> {
	const storage = await getStorage();
	const wagers = await storage.getOpenWagers(trip.trip_id, trip.stop_id);
	if (wagers.length === 0) {
		await storage.updateTrackedTrip(trip.trip_id, trip.stop_id, {
			status: outcome === 'canceled' ? 'canceled' : 'arrived'
		});
		return;
	}

	const totalPool = wagers.reduce((sum, w) => sum + w.stake, 0);
	let winners: typeof wagers;

	if (outcome === 'canceled') {
		winners = wagers.filter((w) => w.wager_type === 'cancellation');
	} else {
		const scheduledMins = timeToMins(trip.scheduled_departure);
		const estimatedMins = trip.last_estimated_departure
			? timeToMins(trip.last_estimated_departure)
			: scheduledMins;
		const actualDrift = Math.floor(estimatedMins - scheduledMins);
		winners = wagers.filter(
			(w) => w.wager_type === 'drift' && Math.abs((w.drift_minutes ?? 0) - actualDrift) <= 1
		);
	}

	const results: WagerResult[] = wagers.map((w) => {
		if (winners.length === 0) return { wagerId: w.wager_id, status: 'void', payout: w.stake };
		if (winners.some((win) => win.wager_id === w.wager_id)) {
			const winnersStake = winners.reduce((sum, win) => sum + win.stake, 0);
			return { wagerId: w.wager_id, status: 'won', payout: (w.stake / winnersStake) * totalPool };
		}
		return { wagerId: w.wager_id, status: 'lost', payout: 0 };
	});

	await storage.settleWagers(trip.trip_id, trip.stop_id, results, outcome);
}

/** Called after each departures fetch to check and resolve tracked trips. */
export async function resolveTrackedTrips(): Promise<void> {
	const tripUpdates = await getTripUpdates();
	if (!tripUpdates) return;

	const storage = await getStorage();
	const tracked = await storage.getTrackedTrips();
	if (tracked.length === 0) return;

	const now = Date.now();
	const GRACE_MS = 15 * 60 * 1000;

	for (const trip of tracked) {
		const tu = tripUpdates[trip.trip_id];

		if (tu) {
			if (tu.scheduleRelationship === 3) {
				await settleTrip(trip, 'canceled');
				continue;
			}

			const stu = tu.stopTimeUpdates.find((s) => s.stopId === trip.stop_id);
			const delaySecs = stu?.departureDelay ?? stu?.arrivalDelay ?? 0;
			const scheduledMins = timeToMins(trip.scheduled_departure);
			const estimatedMins = scheduledMins + Math.round(delaySecs / 60);
			const eh = Math.floor(estimatedMins / 60) % 24;
			const em = Math.max(0, estimatedMins % 60);
			const estimated = `${String(eh).padStart(2, '0')}:${String(em).padStart(2, '0')}`;

			await storage.updateTrackedTrip(trip.trip_id, trip.stop_id, {
				lastEstimatedDeparture: estimated,
				lastSeenAt: new Date().toISOString()
			});
		} else {
			const scheduledTime = scheduledMs(trip.scheduled_date, trip.scheduled_departure);
			if (now > scheduledTime + GRACE_MS && trip.last_seen_at) {
				await settleTrip(trip, 'arrived');
			}
		}
	}
}
