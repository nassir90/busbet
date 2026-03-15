import type { LayoutServerLoad } from './$types';
import { getStorage } from '$lib/server/storage/index.js';

export const load: LayoutServerLoad = async ({ locals, depends }) => {
	depends('app:balance');
	const storage = await getStorage();
	const user = await storage.getOrCreateUser(locals.userId);
	return { balance: user.balance };
};
