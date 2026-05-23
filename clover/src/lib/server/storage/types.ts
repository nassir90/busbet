export interface Stop {
	stop_id: string;
	stop_code: string;
	stop_name: string;
	stop_lat?: number;
	stop_lon?: number;
}

export interface Departure {
	trip_id: string;
	stop_id: string;
	stop_sequence: number;
	route_short_name: string;
	trip_headsign: string;
	scheduled_departure: string;
	estimated_departure: string | null;
	delay_seconds: number | null;
	realtime: boolean;
}

export interface Market {
	id: string;
	route_short_name: string;
	stop_code: string;
	stop_name: string;
	direction: string;
	description: string;
}
