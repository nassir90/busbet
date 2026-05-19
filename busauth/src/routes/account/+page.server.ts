import { redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async ({ locals: { supabase, safeGetSession } }) => {
	const { session } = await safeGetSession();
	if (!session) redirect(303, '/login');

	const { data: profile } = await supabase
		.from('profiles')
		.select('username, full_name, website, avatar_url')
		.eq('id', session.user.id)
		.single();

	return { session, profile };
};
