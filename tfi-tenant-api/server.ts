import http from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createReportsStore, type ReportInput } from './src/reports.js';
import { deriveActual, isReportKind, kindHasTime } from './src/derive.js';
import { LATEST_VERSION } from './src/migrations.js';
import { createFeedbackStore, FEEDBACK_MAX_BODY_BYTES } from './src/feedback.js';

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
// Loopback by default, matching gtfsr-stop-times. The cloudflared tunnel is the only intended
// ingress, and it reaches this through Caddy on the same box — so binding 0.0.0.0 doesn't buy
// anything and puts the unauthenticated write API straight on the public internet, past
// Cloudflare. Override only for a deployment where something off-box genuinely must reach it.
const HOST        = process.env.TFI_TENANT_API_HOST ?? '127.0.0.1';
const REPORTS_DB  = process.env.REPORTS_DB ?? './data/reports.db';
// Upstream read-only GTFS API — this service is a pure consumer of it for enrichment.
const GTFSR_BASE  = (process.env.GTFSR_BASE_URL ?? 'http://127.0.0.1:8110').replace(/\/$/, '');

// App feedback: JSON records plus screenshots, in a folder capped at FEEDBACK_MAX_BYTES in total
// (500 MiB unless configured). See src/feedback.ts.
const FEEDBACK_DIR       = process.env.FEEDBACK_DIR ?? './data/feedback';
const FEEDBACK_MAX_BYTES = Number(process.env.FEEDBACK_MAX_BYTES ?? 500 * 1024 * 1024);

mkdirSync(dirname(REPORTS_DB), { recursive: true });
const reports = createReportsStore(REPORTS_DB);
const feedback = createFeedbackStore(FEEDBACK_DIR, FEEDBACK_MAX_BYTES);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function respond(res: http.ServerResponse, status: number, body: unknown) {
	res.writeHead(status, { 'Content-Type': 'application/json' });
	res.end(JSON.stringify(body));
}

class BodyTooLarge extends Error {}

/**
 * The request body as text, refused past [limit] bytes. Oversized bodies used to be cut off with
 * req.destroy() alone, which never settled this promise: the handler hung and the client waited
 * out its own timeout instead of hearing why.
 */
function readBody(req: http.IncomingMessage, limit = 1_000_000): Promise<string> {
	return new Promise((resolve, reject) => {
		const chunks: Buffer[] = [];
		let size = 0;
		req.on('data', (c: Buffer) => {
			size += c.length;
			if (size > limit) {
				reject(new BodyTooLarge());
				req.destroy();
				return;
			}
			chunks.push(c);
		});
		req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
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
	if (!isReportKind(kind)) return { error: 'kind must be "arrived", "boarded" or "cancelled"' };
	const tripId = String(body?.trip_id ?? '');
	const stopCode = String(body?.stop_code ?? '');
	const serviceDate = String(body?.service_date ?? '');
	if (!tripId || !stopCode || !/^\d{8}$/.test(serviceDate)) {
		return { error: 'trip_id, stop_code and service_date (YYYYMMDD) are required' };
	}

	const stop = await fetchTripStop(tripId, stopCode);
	const actualTime: string | null = kindHasTime(kind) ? (body?.actual_time ?? null) : null;
	const { actualEpoch, delaySeconds } = deriveActual(actualTime, serviceDate, stop?.scheduled_departure ?? null);

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
		// Provenance is server-assigned: a client cannot claim to be anything but a user.
		source: 'user',
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

		// Deploy check: what schema the live database is actually on. If `version` here
		// trails `expected`, the running process is older than the code that needs it.
		if (req.method === 'GET' && path === '/schema') {
			await reports.ready();
			const rows = await reports.db.execute('SELECT version, name, applied_at FROM schema_migrations ORDER BY version');
			return respond(res, 200, { expected: LATEST_VERSION, applied: rows.rows });
		}

		if (req.method === 'POST' && path === '/report') {
			let body: any;
			try { body = JSON.parse(await readBody(req) || '{}'); }
			catch (err) {
				if (err instanceof BodyTooLarge) return respond(res, 413, { message: 'body too large' });
				return respond(res, 400, { message: 'invalid JSON' });
			}
			const built = await buildReport(body);
			if ('error' in built) return respond(res, 400, { message: built.error });
			const id = await reports.insert(built);
			return respond(res, 201, { ok: true, id, ...built });
		}

		// Feedback from the app's feedback screen. Stored, never served back: see src/feedback.ts.
		if (req.method === 'POST' && path === '/feedback') {
			let body: unknown;
			try { body = JSON.parse(await readBody(req, FEEDBACK_MAX_BODY_BYTES) || '{}'); }
			catch (err) {
				if (err instanceof BodyTooLarge) return respond(res, 413, { message: 'feedback too large' });
				return respond(res, 400, { message: 'invalid JSON' });
			}
			const result = feedback.save(body);
			if ('error' in result) return respond(res, result.status, { message: result.error });
			return respond(res, 201, { ok: true, id: result.id });
		}

		const undoReportId = req.method === 'POST' ? parseReportId(path) : null;
		if (undoReportId !== null) {
			const result = await reports.undo(undoReportId);
			if (!result.found) return respond(res, 404, { message: 'report not found' });
			return respond(res, 200, { ok: true, id: undoReportId, undone: result.undone });
		}

		// Inspection: recent reports, optionally filtered by ?date=YYYYMMDD and ?source=
		if (req.method === 'GET' && path === '/reports') {
			const date = url.searchParams.get('date');
			const rows = await reports.list({
				serviceDate: date && /^\d{8}$/.test(date) ? date : null,
				includeUndone: url.searchParams.get('include_undone') === '1',
				source: url.searchParams.get('source'),
			});
			return respond(res, 200, rows);
		}

		respond(res, 404, { message: 'Not found' });
	} catch (err) {
		console.error('[tenant-api] error:', err);
		respond(res, 500, { message: 'Internal error' });
	}
});

server.listen(PORT, HOST, () => {
	console.log(`tfi-tenant-api listening on ${HOST}:${PORT}`);
	console.log(`  reports db: ${REPORTS_DB} (schema v${LATEST_VERSION})`);
	console.log(`  gtfsr:      ${GTFSR_BASE}`);
	console.log(`  feedback:   ${FEEDBACK_DIR} (${(feedback.usedBytes / 1048576).toFixed(1)} of ${(FEEDBACK_MAX_BYTES / 1048576).toFixed(0)} MiB used)`);
	// Migrate up front so a schema problem is visible in the deploy log rather than
	// only in whichever unlucky user's report happens to be the first write.
	reports.ready().catch((err) => console.error('[tenant-api] migration failed:', err));
});
