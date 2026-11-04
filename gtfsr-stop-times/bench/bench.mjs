// Repeatable load harness for gtfsr-stop-times: starts the bundled server against the synthetic
// fixtures, replays a fixed request mix, and reports the server process's CPU time and peak RSS.
// Usage: node bench/bench.mjs <pkgDir> <dataDir> [extra node flags...]
// pkgDir holds a built dist/server.js; dataDir is what gen-db.py and gen-feeds.mjs wrote.
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
const [, , pkgDir, dataDir, ...nodeFlags] = process.argv;
const AT = parseInt(readFileSync(join(dataDir, 'at.txt'), 'utf8'));
const codes = readFileSync(join(dataDir, 'busy-codes.txt'), 'utf8').trim().split('\n');
const PORT = 18110 + Math.floor(Math.random() * 1000);
const env = { ...process.env, GTFSR_STOP_TIMES_PORT: String(PORT), DATABASE_URL: join(dataDir, 'busbet.db'), GTFS_FEEDS_DIR: join(dataDir, 'feeds'), GTFS_VEHICLES_DIR: join(dataDir, 'vehicles') };
const child = spawn('node', [...nodeFlags, 'dist/server.js'], { cwd: pkgDir, env, stdio: ['ignore', 'ignore', 'inherit'] });
const base = `http://127.0.0.1:${PORT}`;
for (let i = 0; ; i++) {
	try { if ((await fetch(`${base}/health`)).ok) break; } catch {}
	if (i > 200) throw new Error('server did not start');
	await new Promise((r) => setTimeout(r, 50));
}
const tripId = (i) => `${i}.y1006.60-${i % 400}-d12-1.${Math.floor(i / 400) % 2}.O`;
const urls = [];
// Live-style polls spread over the last three snapshots, as polls straddle a feed roll-over.
const at = (i) => AT - 30 * (i % 3);
for (let i = 0; i < 40; i++) urls.push(`/departures?codes=${codes.slice(i % 20, i % 20 + 10).join(',')}&time=${at(i)}`);
for (let i = 0; i < 100; i++) urls.push(`/departures/${codes[i % codes.length]}?time=${at(i)}`);
for (let i = 0; i < 60; i++) urls.push(`/vehicles/${codes[i % codes.length]}?time=${at(i)}&window=10`);
for (let i = 0; i < 60; i++) urls.push(`/trips/${encodeURIComponent(tripId(i * 7))}?time=${at(i)}`);
for (let i = 0; i < 20; i++) urls.push(`/vehicles/trip/${encodeURIComponent(tripId(i * 7))}?time=${at(i)}`);
for (let i = 0; i < 3; i++) urls.push(`/departures/${codes[i]}/range?start=${AT - 1500}&end=${AT}&step=60`);
for (let i = 0; i < 3; i++) urls.push(`/vehicles/${codes[i]}/range?start=${AT - 600}&end=${AT}&step=30`);
for (const q of ['Main', 'Church', 'Bridge', 'Park', 'Quay', '12', '13', '140', 'Bus', 'Green']) urls.push(`/stops?q=${q}`, `/routes?q=${q.slice(0, 1)}`);
for (let i = 0; i < 20; i++) urls.push(`/stop-routes/${codes[i]}`, `/route-stops?route=${i + 1}&direction=0`, `/route-shape?route=${i + 1}&direction=1`, `/shapes/trip/${encodeURIComponent(tripId(i))}`, `/stops/${codes[i]}`);
for (let i = 0; i < 5; i++) urls.push('/trip-updates');
// Fixed order, modest concurrency (a handful of phones polling at once).
let seed = 99; const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
for (let i = urls.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [urls[i], urls[j]] = [urls[j], urls[i]]; }
const stat = {}; let bytes = 0;
const t0 = Date.now();
async function worker(start) {
	for (let i = start; i < urls.length; i += 4) {
		const r = await fetch(base + urls[i], { headers: { connection: 'close' } });
		bytes += (await r.text()).length;
		stat[r.status] = (stat[r.status] ?? 0) + 1;
	}
}
await Promise.all([worker(0), worker(1), worker(2), worker(3)]);
const wall = Date.now() - t0;
const pstat = readFileSync(`/proc/${child.pid}/stat`, 'utf8').split(') ')[1].split(' ');
const cpuS = (parseInt(pstat[11]) + parseInt(pstat[12])) / 100;
const hwm = parseInt(readFileSync(`/proc/${child.pid}/status`, 'utf8').match(/VmHWM:\s+(\d+)/)[1]) / 1024;
const rss = parseInt(readFileSync(`/proc/${child.pid}/status`, 'utf8').match(/VmRSS:\s+(\d+)/)[1]) / 1024;
child.kill('SIGTERM');
await new Promise((r) => child.on('exit', r));
console.log(JSON.stringify({ requests: urls.length, status: stat, bytes, wall_ms: wall, cpu_s: cpuS, peak_rss_mb: +hwm.toFixed(1), end_rss_mb: +rss.toFixed(1) }));
