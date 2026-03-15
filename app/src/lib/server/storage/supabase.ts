import { createClient } from '@supabase/supabase-js';
import { randomUUID } from 'node:crypto';
import type { StorageBackend } from './interface.js';
import type { Stop, Departure, User, Wager, TrackedTrip, PlaceWagerInput, WagerResult } from './types.js';

function minsToGtfsTime(totalMins: number): string {
	const h = Math.floor(totalMins / 60);
	const m = totalMins % 60;
	return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`;
}

export function createSupabaseBackend(url: string, anonKey: string): StorageBackend {
	const sb = createClient(url, anonKey);

	return {
		async isSeeded() {
			const { count } = await sb.from('stops').select('*', { count: 'exact', head: true });
			return (count ?? 0) > 0;
		},

		async searchStops(query: string): Promise<Stop[]> {
			const like = `%${query}%`;
			const { data } = await sb
				.from('stops')
				.select('*')
				.or(`stop_name.ilike.${like},stop_code.ilike.${like}`)
				.limit(20);
			return (data ?? []) as Stop[];
		},

		async getStopByCode(code: string): Promise<Stop | null> {
			const { data } = await sb.from('stops').select('*').eq('stop_code', code).maybeSingle();
			return data as Stop | null;
		},

		async getActiveServiceIds(dayName: string, date: string): Promise<string[]> {
			const { data: added } = await sb
				.from('calendar_dates').select('service_id').eq('date', date).eq('exception_type', 1);
			const { data: removed } = await sb
				.from('calendar_dates').select('service_id').eq('date', date).eq('exception_type', 2);
			const removedSet = new Set((removed ?? []).map((r) => r.service_id as string));
			const { data: regular } = await sb
				.from('calendar').select('service_id')
				.eq(dayName, 1).lte('start_date', date).gte('end_date', date);
			const regularIds = (regular ?? [])
				.map((r) => r.service_id as string)
				.filter((id) => !removedSet.has(id));
			const addedIds = (added ?? []).map((r) => r.service_id as string);
			return [...new Set([...regularIds, ...addedIds])];
		},

		async getOrCreateUser(userId: string): Promise<User> {
			const { data } = await sb.from('users').select('*').eq('user_id', userId).maybeSingle();
			if (data) return data as User;
			const now = new Date().toISOString();
			const newUser = { user_id: userId, balance: 100.0, created_at: now };
			await sb.from('users').insert(newUser);
			return newUser;
		},

		async placeWager(input: PlaceWagerInput): Promise<Wager> {
			const user = await (this as StorageBackend).getOrCreateUser(input.userId);
			if (user.balance < input.stake) throw new Error('Insufficient balance');
			const wagerId = randomUUID();
			const now = new Date().toISOString();
			await sb.from('users').update({ balance: user.balance - input.stake }).eq('user_id', input.userId);
			const wager = {
				wager_id: wagerId, user_id: input.userId, trip_id: input.tripId,
				stop_id: input.stopId, scheduled_departure: input.scheduledDeparture,
				scheduled_date: input.scheduledDate, wager_type: input.wagerType,
				drift_minutes: input.driftMinutes, stake: input.stake, status: 'open',
				placed_at: now, resolved_at: null, payout: null
			};
			await sb.from('wagers').insert(wager);
			await sb.from('tracked_trips').upsert({
				trip_id: input.tripId, stop_id: input.stopId,
				scheduled_departure: input.scheduledDeparture, scheduled_date: input.scheduledDate,
				last_estimated_departure: null, last_seen_at: null, status: 'tracking'
			}, { onConflict: 'trip_id,stop_id', ignoreDuplicates: true });
			return wager as Wager;
		},

		async getOpenWagers(tripId: string, stopId: string): Promise<Wager[]> {
			const { data } = await sb.from('wagers').select('*')
				.eq('trip_id', tripId).eq('stop_id', stopId).eq('status', 'open');
			return (data ?? []) as Wager[];
		},

		async settleWagers(tripId: string, stopId: string, results: WagerResult[], outcome: 'arrived' | 'canceled'): Promise<void> {
			const now = new Date().toISOString();
			for (const { wagerId, status, payout } of results) {
				await sb.from('wagers').update({ status, payout, resolved_at: now }).eq('wager_id', wagerId);
				if (payout > 0) {
					const { data: w } = await sb.from('wagers').select('user_id, balance:users(balance)').eq('wager_id', wagerId).maybeSingle();
					if (w) await sb.rpc('increment_balance', { p_user_id: (w as { user_id: string }).user_id, p_delta: payout });
				}
			}
			await sb.from('tracked_trips').update({ status: outcome === 'canceled' ? 'canceled' : 'arrived' })
				.eq('trip_id', tripId).eq('stop_id', stopId);
		},

		async getUserWagers(userId: string): Promise<Wager[]> {
			const { data } = await sb.from('wagers').select('*').eq('user_id', userId)
				.order('placed_at', { ascending: false }).limit(30);
			return (data ?? []) as Wager[];
		},

		async getTrackedTrips(): Promise<TrackedTrip[]> {
			const { data } = await sb.from('tracked_trips').select('*').eq('status', 'tracking');
			return (data ?? []) as TrackedTrip[];
		},

		async updateTrackedTrip(tripId: string, stopId: string, patch: {
			lastEstimatedDeparture?: string | null;
			lastSeenAt?: string | null;
			status?: 'tracking' | 'arrived' | 'canceled';
		}): Promise<void> {
			const update: Record<string, unknown> = {};
			if (patch.lastEstimatedDeparture !== undefined) update.last_estimated_departure = patch.lastEstimatedDeparture;
			if (patch.lastSeenAt !== undefined) update.last_seen_at = patch.lastSeenAt;
			if (patch.status !== undefined) update.status = patch.status;
			if (!Object.keys(update).length) return;
			await sb.from('tracked_trips').update(update).eq('trip_id', tripId).eq('stop_id', stopId);
		},

		async getScheduledDepartures(
			stopId: string,
			activeServiceIds: string[],
			windowMinutes = 90,
			hourOffset = 0
		): Promise<Departure[]> {
			if (activeServiceIds.length === 0) return [];
			const now = new Date();
			const currentMins = now.getHours() * 60 + now.getMinutes() + hourOffset * 60;
			const { data } = await sb
				.rpc('get_scheduled_departures', {
					p_stop_id: stopId,
					p_service_ids: activeServiceIds,
					p_from_time: minsToGtfsTime(currentMins),
					p_to_time: minsToGtfsTime(currentMins + windowMinutes)
				})
				.limit(30);
			return ((data ?? []) as Array<{
				trip_id: string;
				departure_time: string;
				route_short_name: string;
				trip_headsign: string;
			}>).map((row) => {
				const rawHour = parseInt(row.departure_time.substring(0, 2));
				const displayHour = rawHour >= 24 ? rawHour - 24 : rawHour;
				return {
					trip_id: row.trip_id,
					route_short_name: row.route_short_name,
					trip_headsign: row.trip_headsign ?? 'Unknown',
					scheduled_departure: `${String(displayHour).padStart(2, '0')}:${row.departure_time.substring(3, 5)}`,
					estimated_departure: null,
					delay_seconds: null,
					realtime: false
				};
			});
		}
	};
}
