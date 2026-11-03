/**
 * What POST /report accepts, checked before anything is looked up or stored.
 *
 * Nothing used to be: a field could be any JSON value of any size up to the 1 MB body cap, so
 * 200 posts stored 325 MB, and one public GET /reports of them cost ~950 MB of memory. A real
 * report is ~250 bytes. The limits below allow several times what the timetable holds (trip IDs
 * are at most 11 characters of digits and `_`, stop codes 6 digits, route names 9 alphanumerics).
 */
import { isReportKind, kindHasTime, type ReportKind } from './derive.js';

/** A report is ~250 bytes; this leaves room for a client that adds fields. */
export const REPORT_MAX_BODY_BYTES = 4096;

const TRIP_ID = /^[\w.:-]{1,64}$/;
const STOP_CODE = /^\w{1,16}$/;
const ROUTE_SHORT_NAME = /^[\w .\/-]{1,32}$/;
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
/** Service dates further than this from the server's clock are refused: reports are for today. */
const SERVICE_DATE_WINDOW_S = 3 * 86400;
/** A reported_at further than this from the server's clock is replaced by the server's time. */
const REPORTED_AT_WINDOW_S = 86400;

export interface ValidReport {
	kind: ReportKind;
	trip_id: string;
	route_short_name: string | null;
	stop_code: string;
	service_date: string;
	actual_time: string | null;
	reported_at: number;
}

export function validateReport(body: unknown, nowSec = Math.floor(Date.now() / 1000)): ValidReport | { error: string } {
	if (typeof body !== 'object' || body === null || Array.isArray(body)) return { error: 'body must be a JSON object' };
	const b = body as Record<string, unknown>;

	const kind = b.kind;
	if (!isReportKind(kind)) return { error: 'kind must be "arrived", "boarded" or "cancelled"' };
	if (typeof b.trip_id !== 'string' || !TRIP_ID.test(b.trip_id)) return { error: 'trip_id must be a GTFS trip ID' };
	if (typeof b.stop_code !== 'string' || !STOP_CODE.test(b.stop_code)) return { error: 'stop_code must be a stop code' };

	const route = b.route_short_name ?? null;
	if (route !== null && route !== '' && (typeof route !== 'string' || !ROUTE_SHORT_NAME.test(route))) {
		return { error: 'route_short_name must be a route name of at most 32 characters' };
	}

	const serviceDate = b.service_date;
	if (typeof serviceDate !== 'string' || !isNearbyDate(serviceDate, nowSec)) {
		return { error: 'service_date must be YYYYMMDD, within a few days of today' };
	}

	let actualTime: string | null = null;
	if (kindHasTime(kind)) {
		if (typeof b.actual_time !== 'string' || !HHMM.test(b.actual_time)) {
			return { error: `actual_time (HH:MM) is required for "${kind}"` };
		}
		actualTime = b.actual_time;
	}

	// A phone with a wrong clock still saw a real bus, so its report is kept with the server's time.
	let reportedAt = nowSec;
	if (b.reported_at != null) {
		if (typeof b.reported_at !== 'number' || !Number.isFinite(b.reported_at)) {
			return { error: 'reported_at must be unix seconds' };
		}
		if (Math.abs(b.reported_at - nowSec) <= REPORTED_AT_WINDOW_S) reportedAt = Math.floor(b.reported_at);
	}

	return {
		kind,
		trip_id: b.trip_id,
		route_short_name: route || null,
		stop_code: b.stop_code,
		service_date: serviceDate,
		actual_time: actualTime,
		reported_at: reportedAt,
	};
}

/** A real calendar date (not 20261399) within SERVICE_DATE_WINDOW_S of `nowSec`. */
function isNearbyDate(yyyymmdd: string, nowSec: number): boolean {
	const m = /^(\d{4})(\d{2})(\d{2})$/.exec(yyyymmdd);
	if (!m) return false;
	const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
	const date = new Date(Date.UTC(y, mo - 1, d));
	if (date.getUTCFullYear() !== y || date.getUTCMonth() !== mo - 1 || date.getUTCDate() !== d) return false;
	return Math.abs(date.getTime() / 1000 - nowSec) <= SERVICE_DATE_WINDOW_S;
}

/** The undo body: `{"undo_token": "..."}`, as returned by POST /report. */
export function validateUndo(body: unknown): { undo_token: string } | { error: string } {
	const token = (body as Record<string, unknown> | null)?.undo_token;
	if (typeof token !== 'string' || !/^[\w-]{1,64}$/.test(token)) {
		return { error: 'undo_token from the report response is required' };
	}
	return { undo_token: token };
}
