/**
 * Calendar arithmetic on the *agency's* clock.
 *
 * GTFS static times are local wall-clock times on the agency's service day, and every
 * schedule decision here — which service IDs are running, which departures fall inside
 * the window, which trips a bus can still be on — is a comparison against one of them.
 * Doing that with `Date#getHours()` and friends silently adopts whatever `TZ` the node
 * process happens to have inherited, so a server running UTC serves departures an hour
 * off for the whole of Irish summer time. Everything below pins the zone explicitly.
 */

export const SERVICE_TZ = process.env.GTFS_SERVICE_TZ ?? 'Europe/Dublin';

const FMT = new Intl.DateTimeFormat('en-US', {
	timeZone: SERVICE_TZ,
	hourCycle: 'h23',
	year: 'numeric',
	month: '2-digit',
	day: '2-digit',
	hour: '2-digit',
	minute: '2-digit'
});

interface ServiceFields {
	year: number;
	month: number;  // 1-12
	day: number;
	hour: number;   // 0-23
	minute: number;
}

function fieldsOf(d: Date): ServiceFields {
	const parts = FMT.formatToParts(d);
	const get = (type: Intl.DateTimeFormatPartTypes): number => {
		const p = parts.find((x) => x.type === type);
		return p ? parseInt(p.value, 10) : 0;
	};
	return {
		year: get('year'),
		month: get('month'),
		day: get('day'),
		hour: get('hour'),
		minute: get('minute')
	};
}

function pad2(n: number): string {
	return String(n).padStart(2, '0');
}

/** `YYYYMMDD` for the calendar date the instant falls on, in the service timezone. */
export function serviceDate(d: Date): string {
	const f = fieldsOf(d);
	return `${f.year}${pad2(f.month)}${pad2(f.day)}`;
}

/** Day of week, 0 = Sunday, matching `calendar.txt`'s column order. */
export function serviceDayIndex(d: Date): number {
	const f = fieldsOf(d);
	// UTC midnight of the service date: the weekday of a date is zone-independent
	// once the y/m/d have already been resolved in the service zone.
	return new Date(Date.UTC(f.year, f.month - 1, f.day)).getUTCDay();
}

/** Hour of day (0-23) in the service timezone. */
export function serviceHour(d: Date): number {
	return fieldsOf(d).hour;
}

/** Minutes since midnight in the service timezone. */
export function serviceMinutes(d: Date): number {
	const f = fieldsOf(d);
	return f.hour * 60 + f.minute;
}

/**
 * The service date and weekday of the day before the one the instant falls on.
 * Stepping the y/m/d in UTC rather than subtracting 24h from the instant keeps this
 * correct across the DST transitions, where a local day is 23 or 25 hours long.
 */
export function previousServiceDay(d: Date): { date: string; dayIndex: number } {
	const f = fieldsOf(d);
	const prev = new Date(Date.UTC(f.year, f.month - 1, f.day) - 86_400_000);
	return {
		date: `${prev.getUTCFullYear()}${pad2(prev.getUTCMonth() + 1)}${pad2(prev.getUTCDate())}`,
		dayIndex: prev.getUTCDay()
	};
}

/**
 * Signed minutes from `nowMins` to `targetMins`, both minutes-since-midnight.
 * Both wrap at midnight, so a raw subtraction is only meaningful modulo a day:
 * normalise into (-720, 720] so a departure just after midnight reads as a few
 * minutes away rather than most of a day in the past.
 */
export function minutesUntil(targetMins: number, nowMins: number): number {
	let diff = (targetMins - nowMins) % 1440;
	if (diff <= -720) diff += 1440;
	else if (diff > 720) diff -= 1440;
	return diff;
}
