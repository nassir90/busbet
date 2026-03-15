import { getDepartures } from '$lib/server/service/departures.js';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = ({ params }) => getDepartures(params.code);
