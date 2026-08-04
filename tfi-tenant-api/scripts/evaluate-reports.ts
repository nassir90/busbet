/**
 * Evaluate a reports database — real, simulated, or a mix.
 *
 * Two questions, per TFI-34:
 *   1. Is the data any good? (coverage, delay distribution, how wrong the feed was)
 *   2. Is the *pipeline* any good? (how many observations arrive unscorable, how many
 *      get undone, whether the schema is where this build expects it to be)
 *
 * (2) matters more. A week of collection that quietly stored 40% unscorable rows is a
 * week wasted, and you only find out by looking.
 *
 *   npx tsx scripts/evaluate-reports.ts --db ./data/sim-reports.db [--source simulated]
 */
import { createReportsStore } from '../src/reports.js';
import { LATEST_VERSION } from '../src/migrations.js';

function arg(name: string, fallback: string | null): string | null {
	const i = process.argv.indexOf(`--${name}`);
	return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const DB_PATH = arg('db', './data/reports.db')!;
const SOURCE  = arg('source', null);

function num(v: unknown): number | null {
	if (v === null || v === undefined) return null;
	const n = Number(v);
	return Number.isFinite(n) ? n : null;
}

function quantile(sorted: number[], q: number): number {
	if (sorted.length === 0) return NaN;
	const pos = (sorted.length - 1) * q;
	const lo = Math.floor(pos);
	const hi = Math.ceil(pos);
	return lo === hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
}

function mean(xs: number[]): number {
	return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : NaN;
}

function pct(n: number, of: number): string {
	return of === 0 ? '—' : `${((n / of) * 100).toFixed(1)}%`;
}

/** Same ASCII histogram shape as scripts/monitor-drift.ts, for eyeball comparability. */
function histogram(values: number[], buckets = 12) {
	if (values.length === 0) return;
	const lo = Math.min(...values);
	const hi = Math.max(...values);
	const width = (hi - lo) / buckets || 1;
	const counts = new Array(buckets).fill(0);
	for (const v of values) counts[Math.min(buckets - 1, Math.floor((v - lo) / width))]++;
	const peak = Math.max(...counts);
	for (let i = 0; i < buckets; i++) {
		const from = (lo + i * width).toFixed(1).padStart(6);
		const to = (lo + (i + 1) * width).toFixed(1).padStart(6);
		const bar = '#'.repeat(Math.round((counts[i] / peak) * 40));
		console.log(`  ${from}..${to} min | ${bar} ${counts[i]}`);
	}
}

async function main() {
	const store = createReportsStore(DB_PATH);
	await store.ready();

	const versions = await store.db.execute('SELECT version, name, applied_at FROM schema_migrations ORDER BY version');
	const rows = (await store.list({ limit: 1_000_000, includeUndone: true, source: SOURCE })) as any[];

	console.log(`db: ${DB_PATH}${SOURCE ? ` (source='${SOURCE}')` : ''}`);
	console.log(`schema: v${versions.rows.length ? versions.rows[versions.rows.length - 1].version : 0} `
		+ `applied, v${LATEST_VERSION} expected by this build`);

	if (rows.length === 0) { console.log('\nno reports'); return; }

	const undone = rows.filter((r) => Number(r.undone) === 1);
	const active = rows.filter((r) => Number(r.undone) !== 1);
	const dates = new Set(active.map((r) => String(r.service_date)));

	console.log(`\n── volume ──`);
	console.log(`  reports:        ${rows.length} (${undone.length} undone, ${pct(undone.length, rows.length)})`);
	console.log(`  service days:   ${dates.size}  (${(active.length / Math.max(dates.size, 1)).toFixed(1)}/day)`);
	console.log(`  distinct trips: ${new Set(active.map((r) => r.trip_id)).size}`);
	console.log(`  distinct stops: ${new Set(active.map((r) => r.stop_code)).size}`);
	console.log(`  distinct routes:${new Set(active.map((r) => r.route_short_name)).size}`);

	const byKind = new Map<string, number>();
	const bySource = new Map<string, number>();
	for (const r of active) {
		byKind.set(String(r.kind), (byKind.get(String(r.kind)) ?? 0) + 1);
		bySource.set(String(r.source ?? 'user'), (bySource.get(String(r.source ?? 'user')) ?? 0) + 1);
	}
	console.log(`\n── composition ──`);
	for (const [k, n] of [...byKind].sort((a, b) => b[1] - a[1])) console.log(`  kind ${k.padEnd(10)} ${String(n).padStart(6)}  ${pct(n, active.length)}`);
	for (const [k, n] of [...bySource].sort((a, b) => b[1] - a[1])) console.log(`  src  ${k.padEnd(10)} ${String(n).padStart(6)}  ${pct(n, active.length)}`);

	// Pipeline health. Unresolved enrichment is the silent killer: the row is stored, the
	// user is thanked, and the observation is worthless because there is nothing to
	// compare it against.
	const timed = active.filter((r) => r.kind !== 'cancelled');
	const unresolved = timed.filter((r) => r.scheduled_departure === null);
	const unscored = timed.filter((r) => num(r.delay_seconds) === null);
	const noFeed = timed.filter((r) => num(r.feed_delay_seconds) === null);
	console.log(`\n── pipeline health ──`);
	console.log(`  timed reports:            ${timed.length}`);
	console.log(`  no scheduled departure:   ${unresolved.length}  ${pct(unresolved.length, timed.length)}`);
	console.log(`  unscorable (no delay):    ${unscored.length}  ${pct(unscored.length, timed.length)}`);
	console.log(`  no feed prediction:       ${noFeed.length}  ${pct(noFeed.length, timed.length)}`);

	const delays = timed.map((r) => num(r.delay_seconds)).filter((v): v is number => v !== null).map((s) => s / 60);
	if (delays.length) {
		const sorted = [...delays].sort((a, b) => a - b);
		console.log(`\n── observed delay (minutes, ground truth) ──`);
		console.log(`  n=${delays.length}  mean ${mean(delays).toFixed(2)}  median ${quantile(sorted, 0.5).toFixed(2)}`);
		console.log(`  p10 ${quantile(sorted, 0.1).toFixed(1)}  p90 ${quantile(sorted, 0.9).toFixed(1)}  `
			+ `p99 ${quantile(sorted, 0.99).toFixed(1)}  max ${sorted[sorted.length - 1].toFixed(1)}`);
		histogram(delays);
	}

	// The payoff metric: how much better is ground truth than the feed we already had?
	const paired = timed
		.map((r) => ({ actual: num(r.delay_seconds), feed: num(r.feed_delay_seconds) }))
		.filter((p): p is { actual: number; feed: number } => p.actual !== null && p.feed !== null)
		.map((p) => (p.actual - p.feed) / 60);
	if (paired.length) {
		const sorted = [...paired].sort((a, b) => a - b);
		console.log(`\n── feed error (observed − predicted, minutes) ──`);
		console.log(`  n=${paired.length}  bias ${mean(paired).toFixed(2)}  median ${quantile(sorted, 0.5).toFixed(2)}`);
		console.log(`  MAE ${mean(paired.map(Math.abs)).toFixed(2)}  p90 |err| ${quantile(paired.map(Math.abs).sort((a, b) => a - b), 0.9).toFixed(2)}`);
		console.log(`  within 1 min: ${pct(paired.filter((e) => Math.abs(e) <= 1).length, paired.length)}`
			+ `   within 3 min: ${pct(paired.filter((e) => Math.abs(e) <= 3).length, paired.length)}`);
	} else {
		console.log(`\n── feed error ──\n  no paired observations yet`);
	}
}

main().catch((err) => { console.error(err); process.exit(1); });
