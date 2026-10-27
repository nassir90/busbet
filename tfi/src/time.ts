import { useEffect, useState } from 'preact/hooks';

/**
 * Every clock time the API returns is wall-clock on the agency's zone, so "now" has to be read
 * off the same clock. The browser's own zone is wrong for anyone outside Ireland, and was wrong
 * for the old server-rendered page too, which used the VM's UTC clock and ran an hour off for the
 * whole of Irish summer time.
 */
export const SERVICE_TZ = 'Europe/Dublin';

const HM = new Intl.DateTimeFormat('en-GB', { timeZone: SERVICE_TZ, hourCycle: 'h23', hour: '2-digit', minute: '2-digit' });
const HMS = new Intl.DateTimeFormat('en-GB', { timeZone: SERVICE_TZ, hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit' });

/** Minutes since midnight in the service zone. */
export function serviceMinutes(d = new Date()): number {
	const [h, m] = HM.format(d).split(':').map(Number);
	return h * 60 + m;
}

/** "HH:MM:SS" in the service zone for an epoch-seconds instant. */
export function clockAt(epochSec: number): string {
	return HMS.format(new Date(epochSec * 1000));
}

/** "HH:MM" or "HH:MM:SS" to minutes. GTFS runs past 24:00 for after-midnight trips, so this can exceed 1440. */
export function toMins(hhmm: string): number {
	const [h, m] = hhmm.split(':').map(Number);
	return h * 60 + m;
}

/**
 * Signed minutes from now to target, taken the short way round the clock. That absorbs both the
 * 23:58 → 00:05 wrap and GTFS's "25:10" encoding; nothing on a board is ever 12 hours out.
 */
export function minutesUntil(target: number, now: number): number {
	let diff = (target - now) % 1440;
	if (diff <= -720) diff += 1440;
	else if (diff > 720) diff -= 1440;
	return diff;
}

export function dueLabel(diff: number): string {
	if (diff <= 0) return 'Due';
	if (diff === 1) return '1 min';
	return `${diff} mins`;
}

/** Display form of a GTFS time: wraps "25:10" to "01:10". */
export function wallClock(hhmm: string): string {
	const [h, m] = hhmm.split(':');
	return `${String(Number(h) % 24).padStart(2, '0')}:${m}`;
}

/**
 * Minutes late (positive) or early (negative). Prefers the feed's own delay, which is to the
 * second, over the difference of two minute-rounded clock strings.
 */
export function driftMinutes(sched: string, est: string | null, delaySeconds: number | null): number | null {
	if (delaySeconds != null) return Math.round(delaySeconds / 60);
	if (est == null) return null;
	return minutesUntil(toMins(est), toMins(sched));
}

export function driftLabel(drift: number | null): string {
	if (drift == null) return '';
	if (drift > 0) return `${drift} min late`;
	if (drift < 0) return `${-drift} min early`;
	return 'on time';
}

/**
 * Service-zone minutes, re-rendering on each minute boundary so countdowns tick by themselves
 * between polls.
 */
export function useNowMinutes(): number {
	const [now, setNow] = useState(serviceMinutes);
	useEffect(() => {
		let timer: number;
		const arm = () => {
			timer = window.setTimeout(() => { setNow(serviceMinutes()); arm(); }, 60_000 - (Date.now() % 60_000) + 50);
		};
		arm();
		return () => clearTimeout(timer);
	}, []);
	return now;
}
