import { json } from '@sveltejs/kit';
import { setPlaybackTime, getPlaybackTime } from '$lib/server/service/gtfs.js';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async () => {
	const state = await getPlaybackTime();
	return json(state ?? { error: 'offline' });
};

export const POST: RequestHandler = async ({ request }) => {
	const body = await request.json();
	await setPlaybackTime(body);
	const state = await getPlaybackTime();
	return json(state ?? { error: 'offline' });
};
