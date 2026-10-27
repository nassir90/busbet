export interface Stop {
	stop_id: string;
	stop_code: string;
	stop_name: string;
	stop_lat?: number;
	stop_lon?: number;
	/** Distinct route short names that serve this stop. Populated by search; may be absent elsewhere. */
	routes?: string[];
	/**
	 * Distinct GTFS route_type values of the routes serving this stop (0 tram/Luas, 2 rail, 3 bus,
	 * …). Lets a client pick a mode logo for the stop without a second lookup. Absent on
	 * deployments that predate this field; a stop is effectively single-mode in practice.
	 */
	route_types?: number[];
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
	/** GTFS route_type of the departing service (0 tram/Luas, 2 rail, 3 bus, …). */
	route_type: number | null;
	direction_id: number;
	trip_headsign: string;
	scheduled_departure: string;   // HH:MM
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface RouteDirection {
	route_short_name: string;
	/** GTFS route_type of this route (0 tram/Luas, 2 rail, 3 bus, …). */
	route_type: number | null;
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
	stop_lat: number | null;
	stop_lon: number | null;
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
	/** GTFS route_type of this trip's route (0 tram/Luas, 2 rail, 3 bus, …). */
	route_type: number | null;
	trip_headsign: string;
	direction_id: number;
	stops: TripStop[];
}

/** Read-only GTFS storage interface — only what's needed for stop-time lookups */
/** A single point on a route's road geometry, ordered by `seq`. */
export interface ShapePoint {
	lat: number;
	lon: number;
}

export interface GtfsStorage {
	isSeeded(): Promise<boolean>;
	searchStops(query: string): Promise<Stop[]>;
	getStopByCode(code: string): Promise<Stop | null>;
	getActiveServiceIds(dayName: string, date: string): Promise<string[]>;
	getScheduledDepartures(
		stopId: string,
		activeServiceIds: string[],
		windowMinutes?: number,
		hourOffset?: number,
		atDate?: Date
	): Promise<Departure[]>;
	getTripStopTimes(tripId: string): Promise<StopTime[]>;
	getRoutesForStop(stopCode: string): Promise<string[]>;
	getRouteTrips(routeShortName: string, directionId?: number): Promise<string[]>;
	searchRoutes(query: string): Promise<RouteDirection[]>;
	getRouteStops(routeShortName: string, directionId: number): Promise<RouteStop[]>;
	getTripDetail(tripId: string): Promise<TripDetail | null>;
	getShape(shapeId: string): Promise<ShapePoint[]>;
	getTripSpans(tripIds: string[]): Promise<Map<string, { first_departure: string; last_arrival: string }>>;
	getShapeForTrip(tripId: string): Promise<ShapePoint[]>;
}
