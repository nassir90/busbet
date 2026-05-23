import { json, error } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import type { RequestHandler } from './$types';

const serviceUrl = () => env.GTFSR_STOP_TIMES_URL ?? 'http://localhost:8110';

export const GET: RequestHandler = async ({ url }) => {
	const q = url.searchParams.get('q')?.trim();
	if (!q || q.length < 2) {
		error(400, { message: 'Query must be at least 2 characters' });
	}
	const res = await fetch(`${serviceUrl()}/stops?q=${encodeURIComponent(q)}`);
	return json(await res.json());
};
