import http from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createReportsStore, type ReportInput } from './src/reports.js';

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

function loadEnv(path: string) {
	try {
		for (const line of readFileSync(path, 'utf8').split('\n')) {
			const trimmed = line.trim();
			if (!trimmed || trimmed.startsWith('#')) continue;
			const eq = trimmed.indexOf('=');
			if (eq === -1) continue;
			const key = trimmed.slice(0, eq).trim();
			const val = trimmed.slice(eq + 1).trim().replace(/^["']|["']$/g, '');
			if (!(key in process.env)) process.env[key] = val;
		}
	} catch { /* no .env */ }
}
loadEnv('.env');

const PORT        = parseInt(process.env.TFI_TENANT_API_PORT ?? '8120');
const REPORTS_DB  = process.env.REPORTS_DB ?? './data/reports.db';
// Upstream read-only GTFS API — this service is a pure consumer of it for enrichment.
const GTFSR_BASE  = (process.env.GTFSR_BASE_URL ?? 'http://127.0.0.1:8110').replace(/\/$/, '');

mkdirSync(dirname(REPORTS_DB), { recursive: true });
const reports = createReportsStore(REPORTS_DB);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function respond(res: http.ServerResponse, status: number, body: unknown) {
	res.writeHead(status, { 'Content-Type': 'application/json' });
	res.end(JSON.stringify(body));
}

function readBody(req: http.IncomingMessage): Promise<string> {
	return new Promise((resolve, reject) => {
		let data = '';
		req.on('data', (c) => { data += c; if (data.length > 1_000_000) req.destroy(); });
		req.on('end', () => resolve(data));
		req.on('error', reject);
	});
}

interface TripStop {
	stop_sequence: number;
	stop_code: string;
	scheduled_departure: string;   // "HH:MM"
	delay_seconds: number | null;  // realtime feed prediction at fetch time
}

/** Fetch a trip's stops (with live delays) from the read-only GTFS API. */
async function fetchTripStop(tripId: string, stopCode: string): Promise<TripStop | null> {
	try {
		const r = await fetch(`${GTFSR_BASE}/trips/${encodeURIComponent(tripId)}`);
		if (!r.ok) return null;
		const detail = await r.json() as { stops?: TripStop[] };
		return detail.stops?.find((s) => s.stop_code === stopCode) ?? null;
	} catch {
		return null;
	}
}

/** Local midnight (unix seconds) for a YYYYMMDD service date. */
function serviceDateStartSec(yyyymmdd: string): number {
	const y = parseInt(yyyymmdd.slice(0, 4));
	const mo = parseInt(yyyymmdd.slice(4, 6));
	const d = parseInt(yyyymmdd.slice(6, 8));
	return Math.floor(new Date(y, mo - 1, d).getTime() / 1000);
}

function hmToMin(t: string): number {
	const [h, m] = t.split(':').map(Number);
	return h * 60 + m;
}

function parseReportId(path: string): number | null {
	const match = /^\/reports\/(\d+)\/undo$/.exec(path);
	if (!match) return null;
	return Number(match[1]);
}

/**
 * Enriches a thin client report: looks up scheduled departure, stop_sequence and the
 * feed's predicted delay from the read-only GTFS API, then derives the actual instant
 * and the ground-truth delay.
 */
async function buildReport(body: any): Promise<ReportInput | { error: string }> {
	const kind = body?.kind;
	if (kind !== 'arrived' && kind !== 'cancelled') return { error: 'kind must be "arrived" or "cancelled"' };
	const tripId = String(body?.trip_id ?? '');
	const stopCode = String(body?.stop_code ?? '');
	const serviceDate = String(body?.service_date ?? '');
	if (!tripId || !stopCode || !/^\d{8}$/.test(serviceDate)) {
		return { error: 'trip_id, stop_code and service_date (YYYYMMDD) are required' };
	}

	const stop = await fetchTripStop(tripId, stopCode);
	const actualTime: string | null = kind === 'arrived' ? (body?.actual_time ?? null) : null;

	let actualEpoch: number | null = null;
	let delaySeconds: number | null = null;
	if (kind === 'arrived' && actualTime && stop) {
		let diff = hmToMin(actualTime) - hmToMin(stop.scheduled_departure);
		const nextDay = diff < -720;          // reported just after midnight vs late-evening schedule
		if (nextDay) diff += 1440;
		delaySeconds = diff * 60;
		actualEpoch = serviceDateStartSec(serviceDate) + hmToMin(actualTime) * 60 + (nextDay ? 86400 : 0);
	} else if (kind === 'arrived' && actualTime) {
		actualEpoch = serviceDateStartSec(serviceDate) + hmToMin(actualTime) * 60;
	}

	return {
		kind,
		trip_id: tripId,
		route_short_name: body?.route_short_name ?? null,
		stop_code: stopCode,
		stop_sequence: stop?.stop_sequence ?? null,
		service_date: serviceDate,
		scheduled_departure: stop?.scheduled_departure ?? null,
		actual_time: actualTime,
		actual_epoch: actualEpoch,
		delay_seconds: delaySeconds,
		feed_delay_seconds: stop?.delay_seconds ?? null,
		reported_at: Number(body?.reported_at) || Math.floor(Date.now() / 1000),
	};
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
	const url = new URL(req.url ?? '/', 'http://localhost');
	const path = url.pathname;

	try {
		if (path === '/health') return respond(res, 200, { ok: true });

		if (req.method === 'POST' && path === '/report') {
			let body: any;
			try { body = JSON.parse(await readBody(req) || '{}'); }
			catch { return respond(res, 400, { message: 'invalid JSON' }); }
			const built = await buildReport(body);
			if ('error' in built) return respond(res, 400, { message: built.error });
			const id = await reports.insert(built);
			return respond(res, 201, { ok: true, id, ...built });
		}

		const undoReportId = req.method === 'POST' ? parseReportId(path) : null;
		if (undoReportId !== null) {
			const result = await reports.undo(undoReportId);
			if (!result.found) return respond(res, 404, { message: 'report not found' });
			return respond(res, 200, { ok: true, id: undoReportId, undone: result.undone });
		}

		// Inspection: recent reports, optionally filtered by ?date=YYYYMMDD
		if (req.method === 'GET' && path === '/reports') {
			const date = url.searchParams.get('date');
			const includeUndone = url.searchParams.get('include_undone') === '1';
			const rows = await reports.list(date && /^\d{8}$/.test(date) ? date : null, 200, includeUndone);
			return respond(res, 200, rows);
		}

		respond(res, 404, { message: 'Not found' });
	} catch (err) {
		console.error('[tenant-api] error:', err);
		respond(res, 500, { message: 'Internal error' });
	}
});

server.listen(PORT, '0.0.0.0', () => {
	console.log(`tfi-tenant-api listening on port ${PORT}`);
	console.log(`  reports db: ${REPORTS_DB}`);
	console.log(`  gtfsr:      ${GTFSR_BASE}`);
});
