/**
 * GTFS Static Data Loader
 *
 * Downloads the NTA GTFS static zip (or uses a local copy) and loads
 * stops, routes, trips, stop_times, and calendar data into the configured backend.
 *
 * Usage:
 *   npm run load-gtfs
 *   npm run load-gtfs -- --file /path/to/gtfs.zip
 *   npm run load-gtfs -- --dir /path/to/extracted/gtfs/
 *
 * Env vars:
 *   STORAGE_BACKEND   — 'sqlite' (default) or 'supabase'
 *   DATABASE_URL      — SQLite path (default: ./data/busbet.db)
 *   SUPABASE_URL      — required when STORAGE_BACKEND=supabase
 *   SUPABASE_ANON_KEY — required when STORAGE_BACKEND=supabase
 *                       (use service_role key for bulk inserts — bypasses RLS)
 */

import { createReadStream, existsSync, mkdirSync } from 'fs';
import { mkdir, rm, writeFile } from 'fs/promises';
import { createInterface } from 'readline';
import { dirname, join } from 'path';
import { tmpdir } from 'os';

const GTFS_ZIP_URL = 'https://www.transportforireland.ie/transitData/Data/GTFS_Realtime.zip';

// ---------------------------------------------------------------------------
// CLI args
// ---------------------------------------------------------------------------

const args = process.argv.slice(2);
let localFile: string | null = null;
let localDir: string | null = null;
let shapeToleranceArg: string | null = null;
for (let i = 0; i < args.length; i++) {
	if (args[i] === '--file' && args[i + 1]) localFile = args[++i];
	if (args[i] === '--dir' && args[i + 1]) localDir = args[++i];
	if (args[i] === '--shape-tolerance' && args[i + 1]) shapeToleranceArg = args[++i];
}

const STORAGE_BACKEND = process.env.STORAGE_BACKEND ?? 'sqlite';
const DATABASE_URL = process.env.DATABASE_URL ?? './data/busbet.db';
const SUPABASE_URL = process.env.SUPABASE_URL ?? '';
const SUPABASE_ANON_KEY = process.env.SUPABASE_ANON_KEY ?? '';

/**
 * Douglas–Peucker tolerance in metres for shape geometry, via --shape-tolerance or
 * SHAPE_TOLERANCE_M. Raw shapes average ~1,244 points each, which is 10–50x more than any
 * map view can use. Higher tolerance = fewer points = coarser line.
 */
const SHAPE_TOLERANCE_M = parseFloat(
	shapeToleranceArg ?? process.env.SHAPE_TOLERANCE_M ?? '20',
);

// ---------------------------------------------------------------------------
// Backend interface (minimal, just for loading)
// ---------------------------------------------------------------------------

interface Loader {
	truncate(table: string): Promise<void>;
	upsertBatch(table: string, rows: Record<string, unknown>[]): Promise<void>;
	markSeeded(): Promise<void>;
}

// ---------------------------------------------------------------------------
// SQLite loader (@libsql/client)
// ---------------------------------------------------------------------------

/**
 * Bumped whenever the table definitions below change. `CREATE TABLE IF NOT EXISTS` silently
 * keeps an existing table with the old columns, so without this a schema change appears to
 * load fine and then fails on insert (or worse, quietly writes the wrong shape of data). On a
 * mismatch we drop and rebuild — every table here is derived from the GTFS feed and carries no
 * state worth preserving.
 */
const SCHEMA_VERSION = '3';

async function createSqliteLoader(dbPath: string): Promise<Loader> {
	mkdirSync(dirname(dbPath), { recursive: true });
	const { createClient } = await import('@libsql/client');
	const db = createClient({ url: `file:${dbPath}` });

	const existing = await db
		.execute("SELECT value FROM _meta WHERE key = 'schema_version'")
		.then((r) => (r.rows[0]?.value as string | undefined) ?? null)
		.catch(() => null); // _meta itself may not exist yet
	if (existing !== SCHEMA_VERSION) {
		if (existing !== null) console.log(`Schema ${existing} != ${SCHEMA_VERSION} — rebuilding tables`);
		await db.executeMultiple(`
			DROP TABLE IF EXISTS stops;
			DROP TABLE IF EXISTS routes;
			DROP TABLE IF EXISTS trips;
			DROP TABLE IF EXISTS stop_times;
			DROP TABLE IF EXISTS calendar;
			DROP TABLE IF EXISTS calendar_dates;
			DROP TABLE IF EXISTS shapes;
			DROP TABLE IF EXISTS _meta;
		`);
	}

	await db.executeMultiple(`
		CREATE TABLE IF NOT EXISTS stops (stop_id TEXT PRIMARY KEY, stop_code TEXT, stop_name TEXT, stop_lat REAL, stop_lon REAL);
		CREATE INDEX IF NOT EXISTS idx_stops_code ON stops(stop_code);
		CREATE INDEX IF NOT EXISTS idx_stops_name ON stops(stop_name COLLATE NOCASE);
		CREATE TABLE IF NOT EXISTS routes (route_id TEXT PRIMARY KEY, route_short_name TEXT, route_long_name TEXT, agency_id TEXT, route_type INTEGER);
		CREATE TABLE IF NOT EXISTS trips (trip_id TEXT PRIMARY KEY, route_id TEXT, service_id TEXT, trip_headsign TEXT, direction_id INTEGER, shape_id TEXT);
		CREATE INDEX IF NOT EXISTS idx_trips_route ON trips(route_id);
		CREATE INDEX IF NOT EXISTS idx_trips_service ON trips(service_id);
		CREATE TABLE IF NOT EXISTS stop_times (trip_id TEXT, stop_id TEXT, stop_sequence INTEGER, arrival_time TEXT, departure_time TEXT, PRIMARY KEY (trip_id, stop_sequence));
		CREATE INDEX IF NOT EXISTS idx_st_stop ON stop_times(stop_id);
		CREATE INDEX IF NOT EXISTS idx_st_trip ON stop_times(trip_id);
		CREATE TABLE IF NOT EXISTS calendar (service_id TEXT PRIMARY KEY, monday INTEGER, tuesday INTEGER, wednesday INTEGER, thursday INTEGER, friday INTEGER, saturday INTEGER, sunday INTEGER, start_date TEXT, end_date TEXT);
		CREATE TABLE IF NOT EXISTS calendar_dates (service_id TEXT, date TEXT, exception_type INTEGER, PRIMARY KEY (service_id, date));
		CREATE TABLE IF NOT EXISTS shapes (shape_id TEXT, seq INTEGER, lat REAL, lon REAL, PRIMARY KEY (shape_id, seq));
		CREATE TABLE IF NOT EXISTS _meta (key TEXT PRIMARY KEY, value TEXT);
	`);

	const INSERT: Record<string, string> = {
		stops: 'INSERT OR REPLACE INTO stops VALUES (:stop_id,:stop_code,:stop_name,:stop_lat,:stop_lon)',
		routes: 'INSERT OR REPLACE INTO routes VALUES (:route_id,:route_short_name,:route_long_name,:agency_id,:route_type)',
		trips: 'INSERT OR REPLACE INTO trips VALUES (:trip_id,:route_id,:service_id,:trip_headsign,:direction_id,:shape_id)',
		shapes: 'INSERT OR REPLACE INTO shapes VALUES (:shape_id,:seq,:lat,:lon)',
		stop_times: 'INSERT OR REPLACE INTO stop_times VALUES (:trip_id,:stop_id,:stop_sequence,:arrival_time,:departure_time)',
		calendar: 'INSERT OR REPLACE INTO calendar VALUES (:service_id,:monday,:tuesday,:wednesday,:thursday,:friday,:saturday,:sunday,:start_date,:end_date)',
		calendar_dates: 'INSERT OR REPLACE INTO calendar_dates VALUES (:service_id,:date,:exception_type)'
	};

	return {
		async truncate(table) {
			await db.execute(`DELETE FROM ${table}`);
		},
		async upsertBatch(table, rows) {
			const sql = INSERT[table];
			const stmts = rows.map((row) => ({ sql, args: row as Record<string, unknown> }));
			await db.batch(stmts as never);
		},
		async markSeeded() {
			await db.execute("INSERT OR REPLACE INTO _meta VALUES ('seeded', '1')");
			// Only recorded on a completed load, so a run that dies partway is rebuilt next time
			// rather than being mistaken for an up-to-date schema.
			await db.execute({
				sql: "INSERT OR REPLACE INTO _meta VALUES ('schema_version', ?)",
				args: [SCHEMA_VERSION],
			});
		}
	};
}

// ---------------------------------------------------------------------------
// Supabase loader (@supabase/supabase-js)
// ---------------------------------------------------------------------------

async function createSupabaseLoader(url: string, key: string): Promise<Loader> {
	const { createClient } = await import('@supabase/supabase-js');
	// Use service_role key here if available for bypassing RLS during bulk load
	const sb = createClient(url, key, { auth: { persistSession: false } });

	return {
		async truncate(table) {
			await sb.from(table).delete().neq('service_id', '__never__').then(() => {}); // delete all
			// Safer: use a truthy filter that matches everything
			const { error } = await sb.rpc('truncate_table', { tbl: table });
			if (error) {
				// RPC not available — fall back to delete with a dummy filter
				// This won't work well for all tables; users should set up the RPC
				console.warn(`  [warn] Could not truncate ${table} via RPC. Create the truncate_table function in Supabase (see supabase/schema.sql).`);
			}
		},
		async upsertBatch(table, rows) {
			const { error } = await sb.from(table).upsert(rows, { ignoreDuplicates: false });
			if (error) throw new Error(`Supabase upsert failed on ${table}: ${error.message}`);
		},
		async markSeeded() {
			await sb.from('_meta').upsert({ key: 'seeded', value: '1' });
		}
	};
}

// ---------------------------------------------------------------------------
// CSV parsing
// ---------------------------------------------------------------------------

function parseCsvRow(line: string): string[] {
	const fields: string[] = [];
	let field = '';
	let inQuote = false;
	for (let i = 0; i < line.length; i++) {
		const ch = line[i];
		if (inQuote) {
			if (ch === '"' && line[i + 1] === '"') { field += '"'; i++; }
			else if (ch === '"') inQuote = false;
			else field += ch;
		} else if (ch === '"') {
			inQuote = true;
		} else if (ch === ',') {
			fields.push(field); field = '';
		} else {
			field += ch;
		}
	}
	fields.push(field);
	return fields;
}

async function* readCsvRows(filePath: string): AsyncGenerator<Record<string, string>> {
	const rl = createInterface({ input: createReadStream(filePath), crlfDelay: Infinity });
	let headers: string[] | null = null;
	for await (const line of rl) {
		if (!line.trim()) continue;
		if (!headers) { headers = line.split(',').map((h) => h.trim().replace(/^"|"$/g, '')); continue; }
		const values = parseCsvRow(line);
		const row: Record<string, string> = {};
		for (let i = 0; i < headers.length; i++) row[headers[i]] = values[i] ?? '';
		yield row;
	}
}

// ---------------------------------------------------------------------------
// Import functions
// ---------------------------------------------------------------------------

const BATCH_SIZE = 2000;

async function batchImport(
	loader: Loader,
	table: string,
	filePath: string,
	transform: (row: Record<string, string>) => Record<string, unknown>
) {
	let batch: Record<string, unknown>[] = [];
	let total = 0;
	for await (const row of readCsvRows(filePath)) {
		batch.push(transform(row));
		if (batch.length >= BATCH_SIZE) {
			await loader.upsertBatch(table, batch.splice(0));
			total += BATCH_SIZE;
			process.stdout.write(`\r  ${table}: ${total.toLocaleString()}`);
		}
	}
	if (batch.length) { await loader.upsertBatch(table, batch); total += batch.length; }
	console.log(`\r  ✓ ${table}: ${total.toLocaleString()}`);
}

// ---------------------------------------------------------------------------
// Shape geometry (shapes.txt)
// ---------------------------------------------------------------------------

interface Pt { lat: number; lon: number }

const M_PER_DEG_LAT = 111_320;

/**
 * Perpendicular distance from `p` to segment `a`–`b`, in metres. Uses an equirectangular
 * projection around the segment — over a few hundred metres at Dublin's latitude the error is
 * far below any tolerance worth simplifying at, and it avoids trigonometry per point.
 */
function perpDistanceM(p: Pt, a: Pt, b: Pt): number {
	const kx = Math.cos(((a.lat + b.lat) / 2) * Math.PI / 180) * M_PER_DEG_LAT;
	const ky = M_PER_DEG_LAT;
	const ax = a.lon * kx, ay = a.lat * ky;
	const bx = b.lon * kx, by = b.lat * ky;
	const px = p.lon * kx, py = p.lat * ky;
	const dx = bx - ax, dy = by - ay;
	const len2 = dx * dx + dy * dy;
	if (len2 === 0) return Math.hypot(px - ax, py - ay);
	const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
	return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/**
 * Douglas–Peucker, iterative rather than recursive — shapes run to thousands of points and a
 * recursive implementation can blow the stack on a pathological one.
 */
function simplify(points: Pt[], toleranceM: number): Pt[] {
	if (points.length <= 2 || toleranceM <= 0) return points;
	const keep = new Uint8Array(points.length);
	keep[0] = 1;
	keep[points.length - 1] = 1;
	const stack: Array<[number, number]> = [[0, points.length - 1]];
	while (stack.length) {
		const [first, last] = stack.pop()!;
		let maxD = 0;
		let idx = -1;
		for (let i = first + 1; i < last; i++) {
			const d = perpDistanceM(points[i], points[first], points[last]);
			if (d > maxD) { maxD = d; idx = i; }
		}
		if (idx !== -1 && maxD > toleranceM) {
			keep[idx] = 1;
			stack.push([first, idx], [idx, last]);
		}
	}
	const out: Pt[] = [];
	for (let i = 0; i < points.length; i++) if (keep[i]) out.push(points[i]);
	return out;
}

/**
 * Streams shapes.txt, simplifying each shape as it completes. shapes.txt groups rows by
 * shape_id contiguously, so we only ever hold one shape (~1.2k points) rather than the whole
 * 288 MB / 6.4M-row file — which would not fit comfortably on a small box.
 */
async function importShapes(loader: Loader, dir: string, toleranceM: number) {
	const file = join(dir, 'shapes.txt');
	if (!existsSync(file)) { console.warn('  [skip] shapes.txt not found'); return; }
	await loader.truncate('shapes');

	let batch: Record<string, unknown>[] = [];
	let currentId: string | null = null;
	let pts: Array<Pt & { seq: number }> = [];
	let rawTotal = 0, keptTotal = 0, shapeCount = 0;
	const seen = new Set<string>();

	const flushShape = async () => {
		if (currentId === null || pts.length === 0) return;
		// Ordering within a shape is by shape_pt_sequence. This feed already emits them in
		// order, but simplification is meaningless on an out-of-order polyline, so don't rely
		// on it.
		pts.sort((a, b) => a.seq - b.seq);
		const simplified = simplify(pts, toleranceM);
		rawTotal += pts.length;
		keptTotal += simplified.length;
		shapeCount++;
		for (let i = 0; i < simplified.length; i++) {
			batch.push({ shape_id: currentId, seq: i, lat: simplified[i].lat, lon: simplified[i].lon });
		}
		if (batch.length >= BATCH_SIZE) {
			await loader.upsertBatch('shapes', batch.splice(0));
			process.stdout.write(`\r  shapes: ${shapeCount.toLocaleString()} shapes, ${keptTotal.toLocaleString()} points`);
		}
	};

	for await (const row of readCsvRows(file)) {
		const id = row.shape_id;
		if (!id) continue;
		if (id !== currentId) {
			await flushShape();
			if (currentId !== null) seen.add(currentId);
			// Contiguity is an assumption, not a guarantee — say so rather than silently
			// truncating a shape that reappears later in the file.
			if (seen.has(id)) console.warn(`\n  [warn] shape ${id} is not contiguous; earlier points dropped`);
			currentId = id;
			pts = [];
		}
		const lat = parseFloat(row.shape_pt_lat);
		const lon = parseFloat(row.shape_pt_lon);
		const seq = parseInt(row.shape_pt_sequence || '0');
		if (Number.isFinite(lat) && Number.isFinite(lon)) pts.push({ lat, lon, seq });
	}
	await flushShape();
	if (batch.length) await loader.upsertBatch('shapes', batch);

	const pct = rawTotal ? ((1 - keptTotal / rawTotal) * 100).toFixed(1) : '0';
	console.log(
		`\r  ✓ shapes: ${shapeCount.toLocaleString()} shapes, ` +
		`${keptTotal.toLocaleString()} points kept of ${rawTotal.toLocaleString()} ` +
		`(${pct}% dropped at ${toleranceM}m, avg ${shapeCount ? Math.round(keptTotal / shapeCount) : 0}/shape)`,
	);
}

async function importAll(loader: Loader, dir: string) {
	const tables: Array<[string, (r: Record<string, string>) => Record<string, unknown>]> = [
		['stops', (r) => ({ stop_id: r.stop_id, stop_code: r.stop_code, stop_name: r.stop_name, stop_lat: parseFloat(r.stop_lat) || null, stop_lon: parseFloat(r.stop_lon) || null })],
		['routes', (r) => ({ route_id: r.route_id, route_short_name: r.route_short_name, route_long_name: r.route_long_name ?? '', agency_id: r.agency_id ?? '', route_type: r.route_type ? parseInt(r.route_type) : null })],
		['trips', (r) => ({ trip_id: r.trip_id, route_id: r.route_id, service_id: r.service_id, trip_headsign: r.trip_headsign ?? '', direction_id: parseInt(r.direction_id || '0') || 0, shape_id: r.shape_id || null })],
		['stop_times', (r) => ({ trip_id: r.trip_id, stop_id: r.stop_id, stop_sequence: parseInt(r.stop_sequence || '0'), arrival_time: r.arrival_time, departure_time: r.departure_time })],
		['calendar', (r) => ({ service_id: r.service_id, monday: +r.monday, tuesday: +r.tuesday, wednesday: +r.wednesday, thursday: +r.thursday, friday: +r.friday, saturday: +r.saturday, sunday: +r.sunday, start_date: r.start_date, end_date: r.end_date })],
		['calendar_dates', (r) => ({ service_id: r.service_id, date: r.date, exception_type: +r.exception_type })]
	];

	const fileMap: Record<string, string> = {
		stops: 'stops.txt', routes: 'routes.txt', trips: 'trips.txt',
		stop_times: 'stop_times.txt', calendar: 'calendar.txt', calendar_dates: 'calendar_dates.txt'
	};

	for (const [table, transform] of tables) {
		const file = join(dir, fileMap[table]);
		if (!existsSync(file)) { console.warn(`  [skip] ${fileMap[table]} not found`); continue; }
		await loader.truncate(table);
		await batchImport(loader, table, file, transform);
	}

	await importShapes(loader, dir, SHAPE_TOLERANCE_M);
}

// ---------------------------------------------------------------------------
// ZIP extraction
// ---------------------------------------------------------------------------

async function extractZip(zipPath: string, outDir: string): Promise<string> {
	await mkdir(outDir, { recursive: true });
	const { execSync } = await import('child_process');
	try {
		execSync(`unzip -o "${zipPath}" -d "${outDir}"`, { stdio: 'inherit' });
		return outDir;
	} catch {
		throw new Error('Could not extract zip — ensure `unzip` is on PATH, or use --dir.');
	}
}

async function downloadZip(url: string): Promise<string> {
	console.log(`Downloading GTFS static data…\n  ${url}`);
	const res = await fetch(url);
	if (!res.ok) throw new Error(`Download failed: ${res.status}`);
	const buf = Buffer.from(await res.arrayBuffer());
	const dest = join(tmpdir(), `busbet-gtfs-${Date.now()}.zip`);
	await writeFile(dest, buf);
	console.log(`  ✓ ${(buf.length / 1_048_576).toFixed(1)} MB`);
	return dest;
}

// ---------------------------------------------------------------------------
// Entrypoint
// ---------------------------------------------------------------------------

async function main() {
	console.log(`Backend: ${STORAGE_BACKEND}\n`);

	let loader: Loader;
	if (STORAGE_BACKEND === 'supabase') {
		if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
			throw new Error('Set SUPABASE_URL and SUPABASE_ANON_KEY (ideally the service_role key for bulk inserts).');
		}
		loader = await createSupabaseLoader(SUPABASE_URL, SUPABASE_ANON_KEY);
	} else {
		loader = await createSqliteLoader(DATABASE_URL);
	}

	let gtfsDir: string;
	let tempZip: string | null = null;
	let tempDir: string | null = null;

	if (localDir) {
		gtfsDir = localDir;
		console.log(`Using local directory: ${gtfsDir}`);
	} else {
		const zipPath = localFile ?? await downloadZip(GTFS_ZIP_URL);
		tempZip = localFile ? null : zipPath;
		const extractTo = join(tmpdir(), `busbet-gtfs-${Date.now()}`);
		tempDir = extractTo;
		console.log('Extracting…');
		gtfsDir = await extractZip(zipPath, extractTo);
	}

	console.log('\nImporting…');
	await importAll(loader, gtfsDir);
	await loader.markSeeded();
	console.log('\nDone!');

	if (tempZip) await rm(tempZip, { force: true });
	if (tempDir) await rm(tempDir, { recursive: true, force: true });
}

main().catch((err) => { console.error(err); process.exit(1); });
