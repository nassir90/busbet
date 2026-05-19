import type { Stop, Departure, StopTime } from './types.js';

/** Abstraction over the static GTFS SQLite database (read-only) */
export interface StorageBackend {
	/** Returns true if GTFS static data has been loaded */
	isSeeded(): Promise<boolean>;

	/** Search stops by name or code */
	searchStops(query: string): Promise<Stop[]>;

	/** Get a stop by its public-facing stop code (e.g. "7634") */
	getStopByCode(code: string): Promise<Stop | null>;

	/** Get service IDs active for the given day name ("monday" etc) and date (YYYYMMDD) */
	getActiveServiceIds(dayName: string, date: string): Promise<string[]>;

	/**
	 * Get scheduled departures for a stop within the next N minutes.
	 * Pass hourOffset=24 when querying yesterday's services for GTFS extended
	 * times (24:xx, 25:xx) that represent buses running past midnight.
	 */
	getScheduledDepartures(
		stopId: string,
		activeServiceIds: string[],
		windowMinutes?: number,
		hourOffset?: number
	): Promise<Departure[]>;

	/** Get all stop_times for a trip, ordered by stop_sequence */
	getTripStopTimes(tripId: string): Promise<StopTime[]>;

	/** Get all trip IDs for a route + direction */
	getRouteTrips(routeShortName: string, directionId?: number): Promise<string[]>;
}
