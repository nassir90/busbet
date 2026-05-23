import type { GtfsStorage, Stop, Departure } from './types.js';
import { applyRealtimeDelays } from './gtfs.js';

const DAY_NAMES = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

function gtfsDate(d: Date): string {
	return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

export async function getDepartures(
	code: string,
	storage: GtfsStorage,
	feedsDir?: string
): Promise<{ stop: Stop; departures: Departure[] } | null> {
	const stop = await storage.getStopByCode(code);
	if (!stop) return null;

	const now = new Date();
	const today = gtfsDate(now);
	const todayName = DAY_NAMES[now.getDay()];
	const activeServices = await storage.getActiveServiceIds(todayName, today);

	let departures = await storage.getScheduledDepartures(stop.stop_id, activeServices);

	if (now.getHours() < 4) {
		const yesterday = new Date(now);
		yesterday.setDate(yesterday.getDate() - 1);
		const yesterdayServices = await storage.getActiveServiceIds(
			DAY_NAMES[yesterday.getDay()],
			gtfsDate(yesterday)
		);
		const overnight = await storage.getScheduledDepartures(stop.stop_id, yesterdayServices, 105, 24);
		departures = [...departures, ...overnight].sort((a, b) =>
			a.scheduled_departure.localeCompare(b.scheduled_departure)
		);
	}

	await applyRealtimeDelays(departures, feedsDir);
	return { stop, departures };
}
