# BusBet Specification

## Initial Request

> *From SEED.md*

The bus-notifier reference project (a Java/Spring Boot application) was provided as a pointer to how NTA's GTFS-R feed works — specifically how to authenticate against the API, how to parse the protobuf response, and how static GTFS data (stops, routes, trips, stop\_times, calendar) relates to realtime trip updates. The stated goal was to build something similar in spirit to [bahn.bet](https://bahn.bet/) — a live departure board with a clean card-based UI — but scoped to Dublin Bus rather than Deutsche Bahn.

The visual target was the Dublin Bus brand: yellow (`#ffd200`) and blue (`#003b8c`) colour palette drawn from the Dublin Bus identity. The tech stack was prescribed by TECH.md: SvelteKit 5, Zod validation, thin routes with logic in `lib/server/service`, storage models in `lib/server/storage`, and component files in `lib/components`. The storage backend was to be abstracted from day one so that local SQLite could be used during development without any external services, with Supabase as a drop-in replacement for production.


## Features

### Core departure board

The primary view is a departure board for a single bus stop, styled after the bahn.bet card grid. Each card shows the route number (e.g. 46A), the headsign (destination), the scheduled departure time, and — when realtime data is available — the estimated departure time alongside a delay badge (on time / +N min). Cards are colour-coded by delay severity: green for on time, amber for minor delays (≤ 5 min), red for larger delays.

The board auto-refreshes every 30 seconds by polling `/api/departures/[code]` client-side, with a visible live indicator in the header during refresh. A manual refresh button is also provided.

### Stop search

A debounced search input on the home page and stop page allows finding stops by name or stop number. Results are drawn from the local database and presented as a dropdown. Selecting a stop navigates to `/stop/[code]`.

### Storage abstraction

Three backends are supported, selected via the `STORAGE_BACKEND` environment variable:

- **`mock`** — entirely in-memory, no import required, generates realistic departure schedules from a hardcoded set of 20 Dublin Bus stops and 10 routes with randomised but stable delays. Intended for rapid UI development.
- **`sqlite`** — powered by `@libsql/client` writing to a local `.db` file. Requires running `npm run load-gtfs` once to populate from the NTA static GTFS feed.
- **`supabase`** — uses `@supabase/supabase-js` against a remote Supabase (PostgreSQL) project. Required for Vercel deployment. Shares the same interface; a `get_scheduled_departures` Postgres RPC replaces the JOIN query used in the SQLite backend.

### GTFS static data loader

`scripts/load-gtfs.ts` downloads the NTA static GTFS zip from the Transport for Ireland open data page, extracts it, and bulk-loads stops, routes, trips, stop\_times, and calendar/calendar\_dates into whichever backend is configured. It respects `STORAGE_BACKEND` at runtime: SQLite uses `@libsql/client` batch statements, Supabase uses `upsert` in batches of 2 000 rows with a `truncate_table` RPC for pre-import cleanup.

### Realtime delay overlay

The NTA GTFS-R TripUpdates endpoint (`https://api.nationaltransport.ie/gtfsr/v2/TripUpdates`) is polled server-side on each departure request. The protobuf response is decoded with `gtfs-realtime-bindings` and the delay for each trip is applied on top of the scheduled departure times returned from the database. The feed response is cached for 30 seconds to avoid hammering the API.

### Request logging

All stop requests log each step to the server console with cumulative elapsed time, making it straightforward to identify which phase (storage init, stop lookup, service ID resolution, scheduled query, realtime overlay) is slow or erroring:

```
[stop/7634] request
[stop/7634] storage ready +2ms
[stop/7634] found "Dún Laoghaire (Stop 7634)" +9ms
[stop/7634] 312 active services (thursday) +51ms
[stop/7634] 6 scheduled departures +1820ms
[stop/7634] 2/6 with realtime data, done +2190ms
```

### Vercel deployment

The adapter is `@sveltejs/adapter-vercel` targeting `nodejs22.x` (explicit because the development machine runs Node 25, which the adapter rejects without a pinned runtime). A `DEPLOYMENT.md` documents the end-to-end flow: run `supabase/schema.sql` in the Supabase SQL editor, run `load-gtfs` locally with the service\_role key to populate the database, then set the four required environment variables in the Vercel dashboard and deploy.


### Prediction market wagering

#### Overview

Each departure card exposes a wagering interface. Users begin with a virtual balance of €100 (no real money). Two wager types exist:

- **Drift wager** — the user bets that a bus will arrive N minutes after the current moment. For example, if the bus is scheduled in 5 minutes and the user wagers a 2-minute drift, they are betting the bus arrives in 7 minutes from now.
- **Cancellation wager** — the user bets that the bus will be cancelled outright.

Bus operators are the intended power users of the cancellation market: a company confident in its service places large sums on "not cancelled", providing a public, on-chain-style signal of operational confidence.

#### Market structure and resolution (Polymarket-adapted)

Markets follow the Polymarket binary-outcome model adapted to a parimutuel pool:

- All wagers placed on a given trip at a given stop share a single prize pool.
- At resolution, the pool is distributed entirely to winners, proportional to each winner's stake. Losers receive nothing.
- If no wager matches the outcome (no winners), all wagers on that trip are voided and stakes refunded — equivalent to Polymarket's N/A resolution.

**Drift resolution:** the actual drift (in whole minutes) is computed as `floor(actual_arrival - scheduled_departure)`. A drift wager wins if `|wagered_drift - actual_drift| ≤ 1` minute.

**Cancellation resolution:** if the trip's `scheduleRelationship` becomes `CANCELED` in the GTFS-R feed, cancellation wagers win and all drift wagers lose. If the bus arrives normally, drift wagers resolve and cancellation wagers lose.

#### Trip tracking

The application tracks the GTFS-R state of every trip that has at least one open wager. On each request that fetches the live feed, tracked trips are examined:

- The latest estimated departure for the wagered stop is recorded.
- If a trip is marked `CANCELED` in the feed, it resolves immediately.
- If a trip disappears from the feed after its scheduled departure time plus a grace period, it is presumed arrived; the last known estimated departure is used to compute actual drift.

#### Balance and session

Users are identified by a UUID stored in a session cookie; no account creation or login is required. The balance is stored server-side in the database. Stakes are deducted at the moment a wager is placed; winnings are credited at resolution.


## Difficulties

### Midnight bus problem

The most significant correctness bug. At 00:29 on a Thursday, querying Thursday's active services for departure times between `00:29:00` and `01:59:00` returns nothing, because GTFS does not represent late-night buses that way. Buses running past midnight on a Wednesday service are stored as Wednesday trips with departure times of `24:29:00`, `25:15:00`, etc. — the hour field simply exceeds 23 to indicate continuation past midnight rather than a new calendar day.

The fix has two parts. First, `getScheduledDepartures` was given an `hourOffset` parameter: passing `24` shifts the SQL time window from `00:29–01:59` to `24:29–25:59`, landing on the extended-hour rows. Second, `getDepartures` was updated to perform a second query before 4 am — fetching yesterday's active service IDs and issuing the offset query against them — then merging and sorting the two result sets. Display times are normalised on the way out (`24:29` → `00:29`) so the UI always shows clock face times.

### Native module build failure (`better-sqlite3`)

The initial SQLite choice was `better-sqlite3`, a native Node.js addon. Building it requires the C standard library headers (`stdint.h` and friends), which were not present on the development machine. Rather than patching the system, the dependency was replaced with `@libsql/client` (Turso's libSQL driver), which ships as pure JavaScript/WASM and requires no compilation step. The API is async rather than synchronous, which required rewriting the storage backend and making `getStorage()` return a `Promise`.

### CJS/ESM interop with `gtfs-realtime-bindings`

The `gtfs-realtime-bindings` package is CommonJS. TypeScript type-checks cleanly against its named export (`import { transit_realtime }`), but Vite's SSR module runner rejects the named import at runtime with "named export not found". The resolution was to use a default import for the runtime value and a `type`-only named import for annotations:

```typescript
import GtfsRealtimeBindings from 'gtfs-realtime-bindings';
import type { transit_realtime } from 'gtfs-realtime-bindings';

const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime as typeof transit_realtime;
```

### Vercel adapter Node.js version check

`@sveltejs/adapter-vercel` validates the local Node.js version at build time and refuses to proceed if it is not one of its supported versions (20, 22, 24). The development machine runs Node 25. The fix is to pass `runtime: 'nodejs22.x'` explicitly in `svelte.config.js`, which tells the adapter which runtime to target on Vercel's infrastructure without relying on the local version for that decision.
