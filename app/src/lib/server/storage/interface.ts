import type { Stop, Departure, User, Wager, TrackedTrip, PlaceWagerInput, WagerResult } from './types.js';

/** Abstraction over storage backends (SQLite or Supabase) */
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

	// --- Wager methods ---

	/** Get user by ID, creating with €100 starting balance if new */
	getOrCreateUser(userId: string): Promise<User>;

	/** Place a wager, deducting stake from balance atomically */
	placeWager(input: PlaceWagerInput): Promise<Wager>;

	/** Get all open wagers for a specific trip at a stop */
	getOpenWagers(tripId: string, stopId: string): Promise<Wager[]>;

	/** Settle all wagers on a trip/stop, crediting winners and voided stakes */
	settleWagers(
		tripId: string,
		stopId: string,
		results: WagerResult[],
		outcome: 'arrived' | 'canceled'
	): Promise<void>;

	/** Get recent wagers placed by a user */
	getUserWagers(userId: string): Promise<Wager[]>;

	/** Get all trips currently being tracked for wager resolution */
	getTrackedTrips(): Promise<TrackedTrip[]>;

	/** Update tracking state for a trip/stop */
	updateTrackedTrip(
		tripId: string,
		stopId: string,
		patch: {
			lastEstimatedDeparture?: string | null;
			lastSeenAt?: string | null;
			status?: 'tracking' | 'arrived' | 'canceled';
		}
	): Promise<void>;
}
