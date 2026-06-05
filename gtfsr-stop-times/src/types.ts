export interface Stop {
	stop_id: string;
	stop_code: string;
	stop_name: string;
	stop_lat?: number;
	stop_lon?: number;
}

export interface StopTime {
	trip_id: string;
	stop_id: string;
	stop_sequence: number;
	arrival_time: string;
	departure_time: string;
}

export interface Departure {
	trip_id: string;
	stop_id: string;
	stop_sequence: number;
	route_short_name: string;
	direction_id: number;
	trip_headsign: string;
	scheduled_departure: string;   // HH:MM
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface RouteDirection {
	route_short_name: string;
	direction_id: number;
	from_stop: string;
	to_stop: string;
}

export interface RouteStop {
	stop_sequence: number;
	stop_code: string;
	stop_name: string;
}

export interface TripStop {
	stop_sequence: number;
	stop_id: string;
	stop_code: string;
	stop_name: string;
	scheduled_arrival: string;     // HH:MM
	scheduled_departure: string;   // HH:MM
	estimated_arrival: string | null;
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface TripDetail {
	trip_id: string;
	route_short_name: string;
	trip_headsign: string;
	direction_id: number;
	stops: TripStop[];
}

/** Read-only GTFS storage interface — only what's needed for stop-time lookups */
export interface GtfsStorage {
	isSeeded(): Promise<boolean>;
	searchStops(query: string): Promise<Stop[]>;
	getStopByCode(code: string): Promise<Stop | null>;
	getActiveServiceIds(dayName: string, date: string): Promise<string[]>;
	getScheduledDepartures(
		stopId: string,
		activeServiceIds: string[],
		windowMinutes?: number,
		hourOffset?: number
	): Promise<Departure[]>;
	getTripStopTimes(tripId: string): Promise<StopTime[]>;
	getRoutesForStop(stopCode: string): Promise<string[]>;
	getRouteTrips(routeShortName: string, directionId?: number): Promise<string[]>;
	searchRoutes(query: string): Promise<RouteDirection[]>;
	getRouteStops(routeShortName: string, directionId: number): Promise<RouteStop[]>;
	getTripDetail(tripId: string): Promise<TripDetail | null>;
}
