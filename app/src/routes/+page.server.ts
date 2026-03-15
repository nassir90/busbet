import { getStorage } from '$lib/server/storage/index.js';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async () => {
	const storage = await getStorage();
	const seeded = await storage.isSeeded();
	return { seeded };
};
