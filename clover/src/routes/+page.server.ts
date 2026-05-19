import { getCuratedMarkets } from '$lib/server/service/markets.js';
import { getPlaybackTime, getPlaybackRange } from '$lib/server/service/gtfs.js';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async () => {
	const [markets, playback, range] = await Promise.all([
		getCuratedMarkets(),
		getPlaybackTime(),
		getPlaybackRange()
	]);
	return { markets, playback, range };
};
