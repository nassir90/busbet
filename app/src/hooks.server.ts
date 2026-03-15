import { randomUUID } from 'node:crypto';
import type { Handle } from '@sveltejs/kit';

export const handle: Handle = async ({ event, resolve }) => {
	const sessionCookie = event.cookies.get('busbet_session');
	if (sessionCookie) {
		event.locals.userId = sessionCookie;
	} else {
		const userId = randomUUID();
		event.locals.userId = userId;
		event.cookies.set('busbet_session', userId, {
			path: '/',
			httpOnly: true,
			sameSite: 'lax',
			maxAge: 60 * 60 * 24 * 365 // 1 year
		});
	}
	return resolve(event);
};
