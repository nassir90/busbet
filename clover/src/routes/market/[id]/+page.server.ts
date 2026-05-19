import { error } from '@sveltejs/kit';
import { getMarketById } from '$lib/server/service/markets.js';
import { getDepartures } from '$lib/server/service/departures.js';
import { getPlaybackTime } from '$lib/server/service/gtfs.js';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ params }) => {
	const market = getMarketById(params.id);
	if (!market) error(404, { message: 'Market not found' });

	const [{ stop, departures }, playback] = await Promise.all([
		getDepartures(market.stop_code),
		getPlaybackTime()
	]);

	// Filter departures to this market's route + direction
	const filtered = departures.filter(
		(d) => d.route_short_name === market.route_short_name &&
			d.trip_headsign.toLowerCase().includes(market.direction.toLowerCase().split(' ')[0])
	);

	return { market, stop, departures: filtered, allDepartures: departures, playback };
};
