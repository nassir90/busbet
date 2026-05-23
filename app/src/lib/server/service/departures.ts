import { error } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import { resolveTrackedTrips } from './resolution.js';
import type { Stop, Departure } from '$lib/server/storage/types.js';

const serviceUrl = () => env.GTFSR_STOP_TIMES_URL ?? 'http://localhost:8110';

function ms(since: number) {
	return `${Date.now() - since}ms`;
}

export async function getDepartures(code: string): Promise<{ stop: Stop; departures: Departure[] }> {
	const t0 = Date.now();
	console.log(`[stop/${code}] request`);

	const res = await fetch(`${serviceUrl()}/departures/${code}`);
	if (res.status === 404) {
		console.log(`[stop/${code}] not found`);
		error(404, { message: `Stop ${code} not found` });
	}
	if (!res.ok) {
		console.warn(`[stop/${code}] service error ${res.status}`);
		error(502, { message: 'Departure service unavailable' });
	}

	const result = await res.json() as { stop: Stop; departures: Departure[] };
	const rtCount = result.departures.filter((d) => d.realtime).length;
	console.log(`[stop/${code}] ${result.departures.length} departures, ${rtCount} realtime +${ms(t0)}`);

	resolveTrackedTrips().catch((err) => console.warn('[resolution] error:', err));

	return result;
}
