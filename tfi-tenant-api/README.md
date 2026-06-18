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
    "service_date": "20260615", "kind": "arrived",
    "actual_time": "08:37", "reported_at": 1781800061
  }
  ```
  `kind` is `"arrived"` or `"cancelled"`; `actual_time` (HH:MM) is required for
  `arrived`, omitted/null for `cancelled`. The server derives `stop_sequence`,
  `scheduled_departure`, `actual_epoch`, `delay_seconds`, and stamps
  `feed_delay_seconds` (the feed's prediction) before storing.
- `GET /reports?date=YYYYMMDD` — recent reports (inspection).
- `GET /health`

## Storage

Append-only SQLite at `REPORTS_DB` (default `./data/reports.db`). Separate from the
static GTFS db so reloads never touch user data. See `src/reports.ts` for the schema.

## Config (env / .env)

- `TFI_TENANT_API_PORT` (default `8120`)
- `REPORTS_DB` (default `/home/lab/Projects/busbet/tfi-tenant-api/data/reports.db`)
- `GTFSR_BASE_URL` (default `http://127.0.0.1:8110`)

## Run

```
npm install
npm run dev
```
