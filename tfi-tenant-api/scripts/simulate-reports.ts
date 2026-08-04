/**
 * Generate a week (or any span) of synthetic boarding reports.
 *
 * Purpose, per TFI-34: know what one week of this data actually looks like *before*
 * spending a week of real users' goodwill collecting it. Rows are written through the
 * real store — same migrations, same INSERT, same derivation as `POST /report` — so
 * what we evaluate is the pipeline, not a spreadsheet that resembles it.
 *
 * Simulated rows carry `source = 'simulated'` and are therefore separable from (and
 * deletable without touching) real observations. Point this at a scratch database by
 * default; writing simulated rows into the live db is possible but must be deliberate.
 *
 *   npx tsx scripts/simulate-reports.ts --days 7 --reporters 5 --db ./data/sim-reports.db
 */
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { createReportsStore, type ReportInput } from '../src/reports.js';
import { deriveActual, hmToMin, minToHm, type ReportKind } from '../src/derive.js';

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------

function arg(name: string, fallback: string): string {
	const i = process.argv.indexOf(`--${name}`);
	return i !== -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
function flag(name: string): boolean {
	return process.argv.includes(`--${name}`);
}

const DAYS      = parseInt(arg('days', '7'));
const REPORTERS = parseInt(arg('reporters', '5'));
const SEED      = parseInt(arg('seed', '20260621'));
const DB_PATH   = arg('db', './data/sim-reports.db');
const SOURCE    = arg('source', 'simulated');
const DRY       = flag('dry');

// ---------------------------------------------------------------------------
// Deterministic randomness — a simulation you cannot reproduce is an anecdote.
// ---------------------------------------------------------------------------

function mulberry32(seed: number) {
	let a = seed >>> 0;
	return () => {
		a = (a + 0x6d2b79f5) >>> 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}
const rnd = mulberry32(SEED);

/** Box–Muller; delays are heavy-tailed so we use this through a lognormal below. */
function gaussian(): number {
	const u = Math.max(rnd(), 1e-9);
	return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rnd());
}

function pick<T>(xs: readonly T[]): T {
	return xs[Math.floor(rnd() * xs.length)];
}

// ---------------------------------------------------------------------------
// The simulated world
// ---------------------------------------------------------------------------

/** Dublin commutes: a real reporter uses two or three stops, not a random walk of the network. */
const COMMUTES = [
	{ route: '46A', outbound: { stopCode: '767', seq: 12 }, inbound: { stopCode: '1358', seq: 34 } },
	{ route: '145', outbound: { stopCode: '792', seq: 8 },  inbound: { stopCode: '1024', seq: 41 } },
	{ route: '15',  outbound: { stopCode: '324', seq: 19 }, inbound: { stopCode: '2019', seq: 27 } },
	{ route: '39A', outbound: { stopCode: '1580', seq: 6 }, inbound: { stopCode: '4412', seq: 30 } },
	{ route: '9',   outbound: { stopCode: '338', seq: 22 }, inbound: { stopCode: '1180', seq: 15 } },
];

/**
 * Delay in minutes against schedule. Peak-hour services run later and with a fatter
 * tail; buses are rarely more than a minute early because they hold at timing points.
 */
function sampleDelayMin(hour: number): number {
	const peak = (hour >= 7 && hour <= 9) || (hour >= 16 && hour <= 18);
	const mu = peak ? 1.5 : 0.9;      // lognormal location, in log-minutes
	const sigma = peak ? 0.85 : 0.7;
	return Math.round(Math.exp(mu + sigma * gaussian()) - 3);
}

/**
 * What the realtime feed was predicting when the report was filed. The feed is broadly
 * right but lags reality and systematically under-predicts the tail — which is the whole
 * reason for collecting ground truth in the first place.
 */
function sampleFeedDelayMin(trueDelayMin: number): number | null {
	if (rnd() < 0.08) return null;                      // no prediction for this stop
	const shrunk = trueDelayMin * 0.7;                  // under-predicts long delays
	return Math.round(shrunk + gaussian() * 1.5);
}

function yyyymmdd(d: Date): string {
	return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

interface Generated {
	report: ReportInput;
	undo: boolean;
}

function generate(): Generated[] {
	const out: Generated[] = [];
	// Each reporter keeps one commute for the whole span, and a personal habit strength.
	const reporters = Array.from({ length: REPORTERS }, () => ({
		commute: pick(COMMUTES),
		diligence: 0.45 + rnd() * 0.5,      // probability they bother to report a given leg
		morning: 7 * 60 + Math.floor(rnd() * 120),
		evening: 16 * 60 + Math.floor(rnd() * 150),
	}));

	const end = new Date();
	end.setHours(12, 0, 0, 0);

	for (let dayOffset = DAYS - 1; dayOffset >= 0; dayOffset--) {
		const day = new Date(end);
		day.setDate(day.getDate() - dayOffset);
		const serviceDate = yyyymmdd(day);
		const weekend = day.getDay() === 0 || day.getDay() === 6;

		for (const r of reporters) {
			// Weekends are a fraction of weekday volume — a week of data is not seven
			// identical days, and any capacity forecast that assumes so is wrong.
			const legs: { minute: number; leg: 'outbound' | 'inbound' }[] = weekend
				? (rnd() < 0.3 ? [{ minute: 11 * 60 + Math.floor(rnd() * 480), leg: 'outbound' as const }] : [])
				: [{ minute: r.morning, leg: 'outbound' as const }, { minute: r.evening, leg: 'inbound' as const }];

			for (const { minute, leg } of legs) {
				if (rnd() > r.diligence) continue;

				const stop = r.commute[leg];
				const scheduledMin = minute;
				const scheduled = minToHm(scheduledMin);
				const hour = Math.floor(scheduledMin / 60);

				// Kind mix: boarding is the new one-tap primary action, so it dominates;
				// "arrived" is the retro-dated flow; cancellations are genuinely rare.
				const roll = rnd();
				const kind: ReportKind = roll < 0.72 ? 'boarded' : roll < 0.98 ? 'arrived' : 'cancelled';

				const trueDelay = sampleDelayMin(hour);
				const actualTime = kind === 'cancelled' ? null : minToHm(scheduledMin + trueDelay);

				// 6% of the time the read API cannot resolve the trip (stale static GTFS,
				// trip cancelled upstream). The observation is still kept — it just cannot
				// be scored. Pipeline health depends on watching this rate, not hiding it.
				const resolved = rnd() > 0.06;
				const scheduledDeparture = resolved ? scheduled : null;
				const { actualEpoch, delaySeconds } = deriveActual(actualTime, serviceDate, scheduledDeparture);

				const feedDelay = resolved ? sampleFeedDelayMin(trueDelay) : null;
				// Reports are filed at the moment of boarding, give or take fumbling for the phone.
				const reportedAt = Math.floor(
					new Date(day.getFullYear(), day.getMonth(), day.getDate()).getTime() / 1000,
				) + (hmToMin(actualTime ?? scheduled) + Math.floor(rnd() * 3)) * 60;

				out.push({
					report: {
						kind,
						trip_id: `SIM-${r.commute.route}-${serviceDate}-${scheduled.replace(':', '')}`,
						route_short_name: r.commute.route,
						stop_code: stop.stopCode,
						stop_sequence: resolved ? stop.seq : null,
						service_date: serviceDate,
						scheduled_departure: scheduledDeparture,
						actual_time: actualTime,
						actual_epoch: actualEpoch,
						delay_seconds: delaySeconds,
						feed_delay_seconds: feedDelay === null ? null : feedDelay * 60,
						reported_at: reportedAt,
						source: SOURCE,
					},
					// Mis-taps happen; the undo path has to be exercised by the sample too.
					undo: rnd() < 0.03,
				});
			}
		}
	}

	return out;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
	const generated = generate();

	if (DRY) {
		console.log(JSON.stringify(generated.map((g) => g.report), null, 2));
		return;
	}

	mkdirSync(dirname(DB_PATH), { recursive: true });
	const store = createReportsStore(DB_PATH);
	await store.ready();

	let undone = 0;
	for (const g of generated) {
		const id = await store.insert(g.report);
		if (g.undo) { await store.undo(id, g.report.reported_at + 20, 'simulated mis-tap'); undone++; }
	}

	const days = DAYS;
	const perWeek = (generated.length / days) * 7;
	console.log(`wrote ${generated.length} reports (${undone} undone) to ${DB_PATH} as source='${SOURCE}'`);
	console.log(`  span:       ${days} day(s), ${REPORTERS} reporter(s), seed ${SEED}`);
	console.log(`  rate:       ${(generated.length / days).toFixed(1)}/day, ${perWeek.toFixed(0)}/week`);
	console.log(`  per user:   ${(perWeek / REPORTERS).toFixed(1)} reports/week`);
	// Roughly 150 bytes/row on disk with the indexes; enough to answer "does this fit".
	console.log(`  at 1k users: ~${Math.round((perWeek / REPORTERS) * 1000 * 52).toLocaleString()} rows/year `
		+ `(~${Math.round((perWeek / REPORTERS) * 1000 * 52 * 150 / 1e6)} MB)`);
	console.log(`\nnext: npx tsx scripts/evaluate-reports.ts --db ${DB_PATH}`);
}

main().catch((err) => { console.error(err); process.exit(1); });
