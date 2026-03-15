import { z } from 'zod';

export const StopSchema = z.object({
	stop_id: z.string(),
	stop_code: z.string(),
	stop_name: z.string(),
	stop_lat: z.number().optional(),
	stop_lon: z.number().optional()
});
export type Stop = z.infer<typeof StopSchema>;

export const RouteSchema = z.object({
	route_id: z.string(),
	route_short_name: z.string(),
	route_long_name: z.string().optional(),
	agency_id: z.string().optional()
});
export type Route = z.infer<typeof RouteSchema>;

export const TripSchema = z.object({
	trip_id: z.string(),
	route_id: z.string(),
	service_id: z.string(),
	trip_headsign: z.string().optional(),
	direction_id: z.number().optional()
});
export type Trip = z.infer<typeof TripSchema>;

export const StopTimeSchema = z.object({
	trip_id: z.string(),
	stop_id: z.string(),
	stop_sequence: z.number(),
	arrival_time: z.string(),
	departure_time: z.string()
});
export type StopTime = z.infer<typeof StopTimeSchema>;

export const CalendarSchema = z.object({
	service_id: z.string(),
	monday: z.number(),
	tuesday: z.number(),
	wednesday: z.number(),
	thursday: z.number(),
	friday: z.number(),
	saturday: z.number(),
	sunday: z.number(),
	start_date: z.string(),
	end_date: z.string()
});
export type Calendar = z.infer<typeof CalendarSchema>;

export const CalendarDateSchema = z.object({
	service_id: z.string(),
	date: z.string(),
	exception_type: z.number()
});
export type CalendarDate = z.infer<typeof CalendarDateSchema>;

/** A scheduled departure enriched with realtime delay info */
export const DepartureSchema = z.object({
	trip_id: z.string(),
	route_short_name: z.string(),
	trip_headsign: z.string(),
	scheduled_departure: z.string(), // HH:MM
	estimated_departure: z.string().nullable(), // HH:MM or null
	delay_seconds: z.number().nullable(),
	realtime: z.boolean()
});
export type Departure = z.infer<typeof DepartureSchema>;

// ---------------------------------------------------------------------------
// Wager types
// ---------------------------------------------------------------------------

export const UserSchema = z.object({
	user_id: z.string(),
	balance: z.number(),
	created_at: z.string()
});
export type User = z.infer<typeof UserSchema>;

export const WagerTypeSchema = z.enum(['drift', 'cancellation']);
export type WagerType = z.infer<typeof WagerTypeSchema>;

export const WagerStatusSchema = z.enum(['open', 'won', 'lost', 'void']);
export type WagerStatus = z.infer<typeof WagerStatusSchema>;

export const WagerSchema = z.object({
	wager_id: z.string(),
	user_id: z.string(),
	trip_id: z.string(),
	stop_id: z.string(),
	scheduled_departure: z.string(), // HH:MM
	scheduled_date: z.string(),      // YYYYMMDD
	wager_type: WagerTypeSchema,
	drift_minutes: z.number().nullable(),
	stake: z.number(),
	status: WagerStatusSchema,
	placed_at: z.string(),
	resolved_at: z.string().nullable(),
	payout: z.number().nullable()
});
export type Wager = z.infer<typeof WagerSchema>;

export const TrackedTripSchema = z.object({
	trip_id: z.string(),
	stop_id: z.string(),
	scheduled_departure: z.string(),       // HH:MM
	scheduled_date: z.string(),            // YYYYMMDD
	last_estimated_departure: z.string().nullable(),
	last_seen_at: z.string().nullable(),   // ISO timestamp
	status: z.enum(['tracking', 'arrived', 'canceled'])
});
export type TrackedTrip = z.infer<typeof TrackedTripSchema>;

export interface WagerResult {
	wagerId: string;
	status: 'won' | 'lost' | 'void';
	payout: number;
}

export const PlaceWagerInputSchema = z.object({
	userId: z.string(),
	tripId: z.string(),
	stopId: z.string(),
	scheduledDeparture: z.string().regex(/^\d{2}:\d{2}$/),
	scheduledDate: z.string().regex(/^\d{8}$/),
	wagerType: WagerTypeSchema,
	driftMinutes: z.number().int().nullable(),
	stake: z.number().positive().max(1000)
});
export type PlaceWagerInput = z.infer<typeof PlaceWagerInputSchema>;
