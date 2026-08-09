# tfi-tenant-api

The stateful, (eventually) tenant-aware write side of BusBet. Unlike
`gtfsr-stop-times`, which is a 100% read-only, global GTFS proxy, this service
**owns user-generated data**: arrival/cancellation reports today, user/tenant
state later.

It is a pure *consumer* of `gtfsr-stop-times` for read enrichment: on each report
it fetches the trip from the read API to resolve the scheduled departure, stop
sequence, and the realtime feed's predicted delay at report time.

## Why it is separate

`gtfsr-stop-times` stays read-only and global (no tenancy, no writes). All writes
and tenant state live here, so the read path can be cached, replaced, or scaled
without dragging user data along.

```
 phone (tfi-app)
   ├── reads  ──▶ gtfsr-stop-times   (<root>/gtfsr-stop-times)   [read-only, global]
   └── writes ──▶ tfi-tenant-api     (<root>/tfi-tenant-api)     [stateful, this service]
                       └── enriches each report via gtfsr /trips/{id}
```

Reports are the first user-generated data in BusBet and the first write path; the
app was a pure front end to the read API until this existed.

## Endpoints

- `POST /report` — submit an observation. Body:
  ```json
  {
    "trip_id": "...", "stop_code": "400", "route_short_name": "46A",
    "service_date": "20260615", "kind": "boarded",
    "actual_time": "08:37", "reported_at": 1781800061
  }
  ```
  `kind` is one of:

  | kind | meaning |
  | --- | --- |
  | `boarded` | the user actually got on this bus at `actual_time` |
  | `arrived` | the bus turned up at `actual_time` (the user may not have boarded) |
  | `cancelled` | the bus never came |

  `actual_time` (HH:MM) is required for `boarded` and `arrived`, omitted/null for
  `cancelled`. `source` is server-assigned (`user`), never taken from the client.
  The server derives `stop_sequence`,
  `scheduled_departure`, `actual_epoch`, `delay_seconds`, and stamps
  `feed_delay_seconds` (the feed's prediction at report time) before storing.
  Returns the stored report's `id`, which can be used for undo.
- `POST /reports/:id/undo` — logically undo a report. The original report row is
  retained, and an undo marker is stored separately.
- `GET /reports?date=YYYYMMDD` — recent active reports (inspection). Add
  `include_undone=1` to include undone reports with `undone`/`undone_at` fields, and
  `source=user` to exclude simulated rows.
- `GET /schema` — applied migrations plus the version this build expects. If they
  disagree, the running process is stale.
- `GET /health`

## Storage

Append-only SQLite at `REPORTS_DB` (default `./data/reports.db`), separate from the
static GTFS db so GTFS reloads never touch user data. Undo is a `report_undos` row
rather than a delete or mutation of the original observation.

```
reports(
  id INTEGER PK,
  kind TEXT,                 -- 'boarded' | 'arrived' | 'cancelled'
  trip_id TEXT,
  route_short_name TEXT,
  stop_code TEXT,
  stop_sequence INTEGER,     -- enriched from gtfsr
  service_date TEXT,         -- 'YYYYMMDD' (GTFS service day)
  scheduled_departure TEXT,  -- 'HH:MM' enriched from gtfsr
  actual_time TEXT,          -- 'HH:MM' as reported (null if cancelled)
  source TEXT,               -- server-assigned: 'user' | 'simulated'
  actual_epoch INTEGER,      -- derived unix seconds (null if cancelled)
  delay_seconds INTEGER,     -- actual minus scheduled (null if cancelled)
  feed_delay_seconds INTEGER,-- GTFS-R prediction at report time (ground-truth vs feed)
  reported_at INTEGER        -- unix seconds
)

report_undos(
  report_id INTEGER PK,      -- reports.id
  undone_at INTEGER,         -- unix seconds
  reason TEXT
)
```

See `src/reports.ts` for the authoritative schema.

### Migrations

User-generated reports are unrecoverable — nobody re-reports a bus from three weeks
ago — so the schema must be able to move forward *underneath* existing rows rather
than being recreated around them. `src/migrations.ts` holds a forward-only, additive
list, applied automatically at boot and stamped into `PRAGMA user_version` inside the
same transaction as the change itself.

- Never edit a released migration; append a new one.
- Never DROP a column or rewrite rows. Obsolete columns are left and ignored.
- Version 1 is deliberately `CREATE TABLE IF NOT EXISTS`, so databases created before
  migrations existed are adopted in place rather than needing to be recreated empty.

Before deploying a schema change, run the guard — it builds a database in the old
shape, seeds it, migrates it, and asserts nothing was lost:

```
npm run verify-migration
```

## Design decisions

- **Actual vs scheduled departure.** A single reported timestamp, compared to the
  stop's scheduled departure. Arrival/departure could be split later if needed.
- **Append-only.** Every observation is kept and aggregated downstream. Two reports
  of the same bus are signal, not a conflict to resolve.
- **Logical undo.** Undo never deletes the original observation; active queries
  exclude any row with a `report_undos` entry unless `include_undone=1`.
- **Feed snapshot stamped server-side** (`feed_delay_seconds`), so backstudies can
  compare ground truth against what the feed predicted at the time.

## Simulation and evaluation

`npm run simulate` writes a synthetic week of reports (`source='simulated'`) through
the real store — same migrations, same insert path, same derivation as `POST /report`
— so what gets evaluated is the pipeline rather than a spreadsheet resembling it.
`npm run evaluate` then reports coverage, the delay distribution, and how wrong the
realtime feed was, alongside pipeline-health numbers (unscorable rows, undo rate).

```
npm run simulate -- --days 7 --reporters 5 --db ./data/sim-reports.db
npm run evaluate -- --db ./data/sim-reports.db
npm run evaluate -- --db ./data/reports.db --source user   # real data only
```

The metric to watch during rollout is *unscorable rows*: an observation stored with no
`scheduled_departure` cost a user a tap and tells us nothing.

## Config (env / .env)

- `TFI_TENANT_API_PORT` (default `8120`)
- `REPORTS_DB` (default `./data/reports.db`)
- `GTFSR_BASE_URL` (default `http://127.0.0.1:8110`)

## Run

```
npm install
npm run dev
npm run typecheck
```

## Open questions / parked

- **Reporter identity and tenancy.** No `reporter` field yet. Ties into app
  settings and user identity; revisit for dedup, trust-weighting, and per-user
  history when that exists. This is the natural home for new user/tenant tables
  (telemetry events, etc.).
- **Aggregation and consumption.** How reports feed estimation and the market is a
  downstream concern and not yet designed.

## Client (`tfi-app`)

The app posts via a second Retrofit client (`Api.tenant`, `TenantApi` in `Api.kt`),
distinct from the read-only `Api.service`. `ReportScreen` is reached by tapping the
due-time column of a departure row, offers **Arrived** (with a time picker) and
**Cancelled**, and shows an **Undo** snackbar after a successful post. Backend base
URLs are runtime configuration in the app (see `BackendConfig.kt`), not build-time
constants.
