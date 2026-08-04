# tfi-tenant-api

The stateful, (eventually) tenant-aware write side of BusBet. Unlike
`gtfsr-stop-times` — which is a 100% read-only, global GTFS proxy — this service
**owns user-generated data**: arrival/cancellation reports today, user/tenant
state later.

It is a pure *consumer* of `gtfsr-stop-times` for read enrichment: on each report
it fetches the trip from the read API to resolve the scheduled departure, stop
sequence, and the realtime feed's predicted delay at report time.

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
  `feed_delay_seconds` (the feed's prediction) before storing. Returns the stored
  report's `id`, which can be used for undo.
- `POST /reports/:id/undo` — logically undo a report. The original report row is
  retained, and an undo marker is stored separately.
- `GET /reports?date=YYYYMMDD` — recent active reports (inspection). Add
  `include_undone=1` to include undone reports with `undone`/`undone_at` fields, and
  `source=user` to exclude simulated rows.
- `GET /schema` — applied migrations plus the version this build expects. If they
  disagree, the running process is stale.
- `GET /health`

## Storage

Append-only SQLite at `REPORTS_DB` (default `./data/reports.db`). Separate from the
static GTFS db so reloads never touch user data. Undo is represented by a
`report_undos` row rather than deleting or mutating the original observation.

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
