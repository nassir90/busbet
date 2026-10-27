// Serves the built web app (dist/, from `npm run build`) and proxies its api/* calls to
// gtfsr-stop-times. Plain node with no packages: on the box this is the only process, and it only
// streams files and responses, so it stays at a few tens of MB.
import http from 'node:http';
import { createReadStream, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT      = parseInt(process.env.TFI_PORT ?? '8108');
const HOST      = process.env.TFI_HOST ?? '127.0.0.1';
// "localhost" is rewritten to the IPv4 literal. gtfsr-stop-times binds 127.0.0.1 only, and a name
// that resolves to ::1 first costs a failed IPv6 connect before every fallback to IPv4 (the same
// stall the Android app once had). The literal skips resolution altogether.
const GTFSR_URL = (process.env.GTFSR_STOP_TIMES_URL ?? 'http://127.0.0.1:8110')
	.replace(/\/$/, '')
	.replace(/^(https?:\/\/)localhost(?=[:/]|$)/, '$1127.0.0.1');
const DIST      = fileURLToPath(new URL('./dist/', import.meta.url));
const UPSTREAM_TIMEOUT_MS = 10_000;

// ---------------------------------------------------------------------------
// API proxy
//
// Only the read endpoints the client uses, and only their own query parameters. The /range
// endpoints are left out on purpose: each is up to 1000 departure computations, which a public
// page has no reason to trigger.
// ---------------------------------------------------------------------------

const API = [
	{ re: /^stops$/,                 params: ['q'] },
	{ re: /^stops\/[^/]+$/,          params: [] },
	{ re: /^routes$/,                params: ['q'] },
	{ re: /^departures$/,            params: ['codes'] },
	{ re: /^departures\/[^/]+$/,     params: [] },
	{ re: /^stop-routes\/[^/]+$/,    params: [] },
	{ re: /^route-stops$/,           params: ['route', 'direction'] },
	{ re: /^trips\/[^/]+$/,          params: [] },
	{ re: /^vehicles\/trip\/[^/]+$/, params: [] },
	{ re: /^vehicles\/[^/]+$/,       params: ['window'] },
	{ re: /^shapes\/trip\/[^/]+$/,   params: [] },
];

const PASS_HEADERS = ['content-type', 'cache-control', 'x-feed-timestamp', 'x-stale'];

async function proxy(req, res, rest, search) {
	const rule = API.find((r) => r.re.test(rest));
	if (!rule || (req.method !== 'GET' && req.method !== 'HEAD')) {
		return json(res, 404, { message: 'Not found' });
	}
	const qs = new URLSearchParams();
	for (const p of rule.params) {
		const v = search.get(p);
		if (v != null) qs.set(p, v);
	}
	const target = `${GTFSR_URL}/${rest}${qs.size ? `?${qs}` : ''}`;
	try {
		const up = await fetch(target, { signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS) });
		const headers = {};
		for (const h of PASS_HEADERS) {
			const v = up.headers.get(h);
			if (v != null) headers[h] = v;
		}
		// Realtime answers must not be reused by a browser or Cloudflare; the static ones carry
		// their own max-age from upstream.
		headers['cache-control'] ??= 'no-store';
		const body = Buffer.from(await up.arrayBuffer());
		headers['content-length'] = body.length;
		res.writeHead(up.status, headers);
		res.end(req.method === 'HEAD' ? undefined : body);
	} catch (err) {
		console.error('[tfi] upstream', rest, err.name === 'TimeoutError' ? 'timeout' : err.message);
		json(res, 502, { message: 'Upstream unavailable' });
	}
}

function json(res, status, body) {
	res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
	res.end(JSON.stringify(body));
}

// ---------------------------------------------------------------------------
// Static files
// ---------------------------------------------------------------------------

const TYPES = {
	'.html': 'text/html; charset=utf-8',
	'.js': 'text/javascript; charset=utf-8',
	'.css': 'text/css; charset=utf-8',
	'.svg': 'image/svg+xml',
	'.png': 'image/png',
	'.webp': 'image/webp',
	'.ico': 'image/x-icon',
	'.json': 'application/json',
	'.txt': 'text/plain; charset=utf-8',
};

// index.html is tiny and read on every page view; held in memory rather than re-read. Read lazily
// so the server still starts (and /health answers) before the first build.
let indexHtml = null;
function index() {
	indexHtml ??= readFileSync(join(DIST, 'index.html'));
	return indexHtml;
}

function serveIndex(req, res) {
	let body;
	try { body = index(); } catch {
		res.writeHead(503, { 'content-type': 'text/plain' });
		return res.end('Not built: run npm run build in tfi/.');
	}
	res.writeHead(200, {
		'content-type': TYPES['.html'],
		'content-length': body.length,
		// Revalidated every time, so a deploy is picked up at once; the hashed assets it names
		// are the long-lived part.
		'cache-control': 'no-cache',
		'x-content-type-options': 'nosniff',
		'referrer-policy': 'strict-origin-when-cross-origin',
	});
	res.end(req.method === 'HEAD' ? undefined : body);
}

function serveAsset(req, res, rel) {
	const file = normalize(join(DIST, rel));
	if (!file.startsWith(DIST)) return notFound(res);
	let st;
	try { st = statSync(file); } catch { return notFound(res); }
	if (!st.isFile()) return notFound(res);
	res.writeHead(200, {
		'content-type': TYPES[extname(file)] ?? 'application/octet-stream',
		'content-length': st.size,
		// Vite content-hashes everything under assets/.
		'cache-control': rel.startsWith('assets/') ? 'public, max-age=31536000, immutable' : 'public, max-age=3600',
		'x-content-type-options': 'nosniff',
	});
	if (req.method === 'HEAD') return res.end();
	createReadStream(file).pipe(res);
}

function notFound(res) {
	res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
	res.end('Not found');
}

// ---------------------------------------------------------------------------
// Server
//
// Paths are taken relative to wherever the proxy mounted us, so this works at / and under a
// stripped prefix alike: "", "index.html", "api/...", "assets/...".
// ---------------------------------------------------------------------------

http.createServer((req, res) => {
	// Prefixed rather than resolved against a base: a request line of "//x" would otherwise parse
	// as a host and throw, and an unhandled throw here takes the whole process down.
	let url, rel;
	try {
		url = new URL(`http://localhost${req.url ?? '/'}`);
		rel = decodeURIComponent(url.pathname).replace(/^\/+/, '');
	} catch {
		res.writeHead(400, { 'content-type': 'text/plain' });
		return res.end('Bad request');
	}

	if (rel === 'health') return json(res, 200, { ok: true });
	// The API path is passed on still encoded, so a stop code can't smuggle a "/" past the rules.
	if (rel.startsWith('api/')) return proxy(req, res, url.pathname.replace(/^\/+api\//, ''), url.searchParams);
	if (req.method !== 'GET' && req.method !== 'HEAD') return notFound(res);
	if (rel === '' || rel === 'index.html') return serveIndex(req, res);
	return serveAsset(req, res, rel);
}).listen(PORT, HOST, () => {
	console.log(`tfi on ${HOST}:${PORT}, upstream ${GTFSR_URL}, serving ${DIST}`);
});
