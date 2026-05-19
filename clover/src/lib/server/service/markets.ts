import type { Market } from '../storage/types.js';

/**
 * Curated markets — hardcoded for now, seeded from our C2 analysis.
 * Each market represents a (route, stop, direction) tuple worth watching.
 */
const CURATED_MARKETS: Market[] = [
	{
		id: 'c2-7899-sandymount',
		route_short_name: 'C2',
		stop_code: '7899',
		stop_name: 'Somerton',
		direction: 'Sandymount',
		description: 'C2 from Somerton towards Sandymount — morning commute favourite'
	},
	{
		id: 'c1-7899-adamstown',
		route_short_name: 'C1',
		stop_code: '7899',
		stop_name: 'Somerton',
		direction: 'Adamstown',
		description: 'C1 from Somerton towards Adamstown'
	},
	{
		id: 'p29-7899-city',
		route_short_name: 'P29',
		stop_code: '7899',
		stop_name: 'Somerton',
		direction: 'City Centre',
		description: 'P29 from Somerton towards Heuston / City — peak hour drama'
	}
];

export function getCuratedMarkets(): Market[] {
	return CURATED_MARKETS;
}

export function getMarketById(id: string): Market | null {
	return CURATED_MARKETS.find((m) => m.id === id) ?? null;
}
