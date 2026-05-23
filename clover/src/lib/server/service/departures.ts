import { error } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import type { Stop, Departure } from '$lib/server/storage/types.js';

const serviceUrl = () => env.GTFSR_STOP_TIMES_URL ?? 'http://localhost:8110';

export async function getDepartures(code: string): Promise<{ stop: Stop; departures: Departure[] }> {
	const res = await fetch(`${serviceUrl()}/departures/${code}`);
	if (res.status === 404) error(404, { message: `Stop ${code} not found` });
	if (!res.ok) error(502, { message: 'Departure service unavailable' });
	return res.json();
}
