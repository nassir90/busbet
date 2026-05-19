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

/** A curated market entry */
export interface Market {
	id: string;
	route_short_name: string;
	stop_code: string;
	stop_name: string;
	direction: string; // headsign
	description: string;
}
