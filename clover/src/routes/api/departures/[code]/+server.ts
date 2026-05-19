import { json } from '@sveltejs/kit';
import { getDepartures } from '$lib/server/service/departures.js';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ params }) => json(await getDepartures(params.code));
