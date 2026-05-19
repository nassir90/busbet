import { error } from '@sveltejs/kit';
import { getStorage } from '$lib/server/storage/index.js';
import { applyRealtimeDelays } from './gtfs.js';
import type { Stop, Departure } from '$lib/server/storage/types.js';

const DAY_NAMES = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];

function gtfsDate(d: Date): string {
	return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

function ms(since: number) {
	return `${Date.now() - since}ms`;
}

export async function getDepartures(code: string): Promise<{ stop: Stop; departures: Departure[] }> {
	const t0 = Date.now();
	console.log(`[stop/${code}] request`);

	const storage = await getStorage();
	console.log(`[stop/${code}] storage ready +${ms(t0)}`);

	const stop = await storage.getStopByCode(code);
	if (!stop) {
		console.log(`[stop/${code}] not found`);
		error(404, { message: `Stop ${code} not found` });
	}
	console.log(`[stop/${code}] found "${stop.stop_name}" +${ms(t0)}`);

	const now = new Date();
	const today = gtfsDate(now);
	const todayName = DAY_NAMES[now.getDay()];
	const activeServices = await storage.getActiveServiceIds(todayName, today);
	console.log(`[stop/${code}] ${activeServices.length} active services (${todayName}) +${ms(t0)}`);

	let departures = await storage.getScheduledDepartures(stop.stop_id, activeServices);
	console.log(`[stop/${code}] ${departures.length} scheduled departures +${ms(t0)}`);

	if (now.getHours() < 4) {
		const yesterday = new Date(now);
		yesterday.setDate(yesterday.getDate() - 1);
		const yesterdayServices = await storage.getActiveServiceIds(
			DAY_NAMES[yesterday.getDay()],
			gtfsDate(yesterday)
		);

		const overnight = await storage.getScheduledDepartures(
			stop.stop_id,
			yesterdayServices,
			90,
			24
		);

		departures = [...departures, ...overnight].sort((a, b) =>
			a.scheduled_departure.localeCompare(b.scheduled_departure)
		);
	}

	await applyRealtimeDelays(departures);
	const rtCount = departures.filter((d) => d.realtime).length;
	console.log(`[stop/${code}] ${rtCount}/${departures.length} with realtime data, done +${ms(t0)}`);

	return { stop, departures };
}
