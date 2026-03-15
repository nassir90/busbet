import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { getStorage } from '$lib/server/storage/index.js';

export const GET: RequestHandler = async ({ locals }) => {
	const storage = await getStorage();
	const user = await storage.getOrCreateUser(locals.userId);
	const wagers = await storage.getUserWagers(locals.userId);
	return json({ balance: user.balance, wagers });
};
