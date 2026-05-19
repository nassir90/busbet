import { json, error } from '@sveltejs/kit';
import { getStorage } from '$lib/server/storage/index.js';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ url }) => {
	const q = url.searchParams.get('q')?.trim();
	if (!q || q.length < 2) {
		error(400, { message: 'Query must be at least 2 characters' });
	}

	const storage = await getStorage();
	const stops = await storage.searchStops(q);
	return json(stops);
};
