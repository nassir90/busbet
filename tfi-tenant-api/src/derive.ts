/**
 * Derivation of the stored fields of a report from what the client actually knows.
 *
 * Kept out of `server.ts` so the simulator and any future backfill run the *same*
 * arithmetic as the live endpoint — otherwise simulated data validates a pipeline
 * that does not exist.
 */

/** Observation kinds. `kind` is stored as open TEXT so new kinds need no migration. */
export const REPORT_KINDS = ['arrived', 'boarded', 'cancelled'] as const;
export type ReportKind = (typeof REPORT_KINDS)[number];

/** Kinds that carry a wall-clock observation time; `cancelled` has nothing to time. */
export function kindHasTime(kind: ReportKind): boolean {
	return kind === 'arrived' || kind === 'boarded';
}

export function isReportKind(v: unknown): v is ReportKind {
	return typeof v === 'string' && (REPORT_KINDS as readonly string[]).includes(v);
}

/** Local midnight (unix seconds) for a YYYYMMDD service date. */
export function serviceDateStartSec(yyyymmdd: string): number {
	const y = parseInt(yyyymmdd.slice(0, 4));
	const mo = parseInt(yyyymmdd.slice(4, 6));
	const d = parseInt(yyyymmdd.slice(6, 8));
	return Math.floor(new Date(y, mo - 1, d).getTime() / 1000);
}

export function hmToMin(t: string): number {
	const [h, m] = t.split(':').map(Number);
	return h * 60 + m;
}

export function minToHm(min: number): string {
	const wrapped = ((min % 1440) + 1440) % 1440;
	return `${String(Math.floor(wrapped / 60)).padStart(2, '0')}:${String(wrapped % 60).padStart(2, '0')}`;
}

/**
 * Turn a reported HH:MM into an absolute instant and a delay against the schedule.
 *
 * `scheduledDeparture` may be null when the read API could not resolve the trip; we
 * still pin the instant so the observation is not wasted, we just cannot score it.
 */
export function deriveActual(
	actualTime: string | null,
	serviceDate: string,
	scheduledDeparture: string | null,
): { actualEpoch: number | null; delaySeconds: number | null } {
	if (!actualTime) return { actualEpoch: null, delaySeconds: null };

	if (!scheduledDeparture) {
		return { actualEpoch: serviceDateStartSec(serviceDate) + hmToMin(actualTime) * 60, delaySeconds: null };
	}

	let diff = hmToMin(actualTime) - hmToMin(scheduledDeparture);
	const nextDay = diff < -720; // reported just after midnight against a late-evening schedule
	if (nextDay) diff += 1440;
	return {
		actualEpoch: serviceDateStartSec(serviceDate) + hmToMin(actualTime) * 60 + (nextDay ? 86400 : 0),
		delaySeconds: diff * 60,
	};
}
