/**
 * Builds a timetable in a child process and hands back only the result.
 *
 * Building a service day walks ~2.4M stop_times rows across three days of trips, and the memory
 * that takes stays resident long after it is garbage: measured, the service stayed ~400 MB larger
 * after a build that leaves ~50 MB of live data. On the gcp host that comes straight out of the
 * page cache holding the GTFS database.
 *
 * A worker thread was tried first and doesn't help: its heap is freed on exit, but the allocator
 * keeps the pages for reuse rather than returning them (+450 MB measured). A process that exits
 * gives everything back, so the build runs in one and only the arrays come home over IPC.
 */
import { fork } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sqliteSource } from './sources.js';
import { GridIndex } from './geo.js';
import { buildTimetable, type BuildOptions, type Timetable } from './timetable.js';

interface Job { dbPath: string; date: string; timeZone: string; opts: BuildOptions }

/** Everything in a Timetable except what can't cross a thread boundary (the Map and the grid). */
type Wire = Omit<Timetable, 'stopIndex' | 'grid'>;

export function buildTimetableInChild(dbPath: string, date: string, timeZone: string, opts: BuildOptions = {}): Promise<Timetable> {
	return new Promise((resolve, reject) => {
		const child = fork(fileURLToPath(import.meta.url), [], {
			// The service runs under tsx; the child needs the same loader to read this .ts file.
			execArgv: process.execArgv.some((a) => a.includes('tsx')) ? process.execArgv : [...process.execArgv, '--import', 'tsx'],
			// Structured clone, so typed arrays arrive as typed arrays rather than JSON.
			serialization: 'advanced',
			stdio: ['ignore', 'inherit', 'inherit', 'ipc'],
		});
		let done = false;
		child.once('message', (wire) => {
			done = true;
			const w = wire as Wire;
			const stopIndex = new Map<string, number>();
			w.stopId.forEach((id, i) => stopIndex.set(id, i));
			resolve({ ...w, stopIndex, grid: new GridIndex(w.stopLat, w.stopLon, 500) });
		});
		child.once('error', reject);
		child.once('exit', (code) => { if (!done) reject(new Error(`timetable builder exited with ${code}`)); });
		const job: Job = { dbPath, date, timeZone, opts: { ...opts, routeTypes: undefined } };
		child.send({ ...job, routeTypes: opts.routeTypes ? [...opts.routeTypes] : undefined });
	});
}

// Child side: build, send, exit.
if (process.send && process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
	process.once('message', async (msg) => {
		const job = msg as Job & { routeTypes?: number[] };
		const opts: BuildOptions = { ...job.opts, routeTypes: job.routeTypes ? new Set(job.routeTypes) : undefined };
		const tt = await buildTimetable(await sqliteSource(job.dbPath), job.date, job.timeZone, opts);
		const { stopIndex: _i, grid: _g, ...wire } = tt;
		process.send!(wire, () => process.exit(0));
	});
}
