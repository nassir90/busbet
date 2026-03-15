import { json, error } from '@sveltejs/kit';
import { z } from 'zod';
import type { RequestHandler } from './$types';
import { getStorage } from '$lib/server/storage/index.js';

const PlaceWagerRequestSchema = z.object({
	tripId: z.string(),
	stopId: z.string(),
	scheduledDeparture: z.string().regex(/^\d{2}:\d{2}$/),
	wagerType: z.enum(['drift', 'cancellation']),
	driftMinutes: z.number().int().min(-5).max(60).nullable(),
	stake: z.number().positive().max(1000)
});

function todayDate(): string {
	const d = new Date();
	return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

export const POST: RequestHandler = async ({ request, locals }) => {
	let body: unknown;
	try {
		body = await request.json();
	} catch {
		error(400, 'Invalid JSON');
	}

	const parsed = PlaceWagerRequestSchema.safeParse(body);
	if (!parsed.success) {
		error(422, { message: 'Validation error', issues: parsed.error.issues } as { message: string; issues: unknown[] });
	}

	const { tripId, stopId, scheduledDeparture, wagerType, driftMinutes, stake } = parsed.data;

	if (wagerType === 'drift' && driftMinutes == null) {
		error(422, { message: 'driftMinutes required for drift wager' });
	}

	const storage = await getStorage();

	let wager;
	try {
		wager = await storage.placeWager({
			userId: locals.userId,
			tripId,
			stopId,
			scheduledDeparture,
			scheduledDate: todayDate(),
			wagerType,
			driftMinutes: wagerType === 'drift' ? driftMinutes : null,
			stake
		});
	} catch (err: unknown) {
		if (err instanceof Error && err.message === 'Insufficient balance') {
			error(422, { message: 'Insufficient balance' });
		}
		throw err;
	}

	const user = await storage.getOrCreateUser(locals.userId);
	return json({ wager, balance: user.balance }, { status: 201 });
};
