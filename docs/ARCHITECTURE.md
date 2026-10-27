# BusBet — Architecture

BusBet is a Dublin bus departure-board app (a bahn.bet-style clone) plus a small
fleet of backend microservices. This is the canonical description of the stack as
it actually is. Repo: `git@github.com:nassir90/busbet`, default branch `master`.

The repo is a monorepo. BusBet-relevant top-level dirs: `tfi-app/` (Android),
`gtfsr-stop-times/`, `tfi-tenant-api/`, `gtfsr-collector/`, `gtfsr-historical-view/`,
`busbet-exporter/`, `www/`, `docs/`, `scripts/`, and the older web clone in `app/`.
Other dirs in the tree (`bronto-*`, `hydroid`, etc.) are separate, unrelated
projects and are out of scope here.

## 1. High-level shape

```
Android app (tfi-app)  ──reads──▶  gtfsr-stop-times   (read-only, global GTFS proxy)
                       ──writes─▶  tfi-tenant-api     (stateful user data: reports)
                                        └─ enriches via gtfsr-stop-times over HTTP
gtfsr-collector  ──polls NTA GTFS-R, archives .pb.gz snapshots──▶ (consumed by gtfsr-stop-times)
```

Everything backend is **Node + TypeScript run directly via `tsx` (no build step)**,
**plain `node:http`** (no framework), **`@libsql/client`** for SQLite. Services run
as **systemd user services** behind a reverse proxy that maps
`<host>/<service-name>/` to a local port.

## 2. Android app (`tfi-app/`)

**Identity.** The app is **Iompar** (Irish for "transport"). `applicationId` and
`namespace` are **`iompar.mpts.ie`**. (History: `com.example.tfiapp` →
`net.uzoukwu.tfiapp` → `iompar.mpts.ie`; public listing name went "Bus Dashboard
for Dublin" → Iompar.) `versionCode 1` / `versionName "1.0"`.

**Build system.**
- Gradle wrapper **8.9**; AGP **8.6.0**; Kotlin **2.2.21** (bumped for the MCP
  Kotlin SDK); Compose compiler plugin 2.2.21.
- **JDK 17 required**, not wired into the environment: every build runs as
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew …` from `tfi-app/`.
- `compileSdk`/`targetSdk` **35**, `minSdk` **24**.
- **Release signing exists** (`signingConfigs.release`); both debug and release are
  buildable. CI builds run via **`.github/workflows/android-build.yml`**.

**UI / architecture.**
- 100% **Jetpack Compose**, **Material 3**, edge-to-edge.
- **No Navigation component.** Custom navigation: a `sealed class Screen` plus a
  `SnapshotStateList<Screen>` stack rendered in `MainActivity.App()`, with a 2-page
  `HorizontalPager` (home stack + notifications). Single choke point for screen
  views.
- **No DI framework, no ViewModels of note.** State lives in composables plus
  DataStore Preferences (`ThemeStore`, `SettingsStore`, `FavouritesStore`,
  notification windows, backend config). No Room/on-device SQLite.
- Networking: **Retrofit + OkHttp + Gson**. Two clients in `Api.kt`: `Api.service`
  (read API) and `Api.tenant` (write API). **Base URLs are runtime configuration**
  (`BackendConfig.kt`), settable in-app and over the app's own MCP/API, defaulting
  to `https://pet.uzoukwu.net`. They are no longer build-time constants.
- Maps: **osmdroid** (no Google Maps). Route geometry from GTFS `shapes.txt` is
  drawn on the stop and trip maps (`MapCommon.kt`, `StopMap.kt`, `TripMap.kt`).
- **Glance** home-screen widget; **WorkManager** + foreground service + AlarmManager
  for notifications; framework `LocationManager` (de-Googled: no Play Services, no
  Firebase).
- **On-device MCP server + HTTP API** over a shared services layer, so the app can
  be driven as a backend as well as a UI.
- **Sentry** is wired in but initialised in **debug builds only**.

**Build/install loop (largely manual):**
```
cd tfi-app && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out screencap -p > shot.png
```
Target device is a physical **Nothing phone** (`cmf-by-nothing-phone-2-pro`) over
wireless ADB on the tailnet; the connection drops and needs re-pairing. No emulator.

## 3. Backend services (Node + tsx + libsql)

Common pattern: a single `server.ts` run by `npx tsx server.ts`, a `*.service`
systemd unit (`Restart=on-failure`, `EnvironmentFile` for port assignment), and
`@libsql/client` for any SQLite. No compile/bundle step. Deploys are manual:
`git pull` on the host plus `systemctl --user restart <svc>`.

- **`gtfsr-stop-times`** (read-only, port `8110`): proxies static GTFS (SQLite
  `busbet.db`) plus the NTA GTFS-R realtime overlay. Stops, departures (with
  Prometheus-style `?time=`/`/range` time-travel), trips, vehicles, routes,
  route-stops, stop-routes, and route `shapes` (ingested from `shapes.txt` at a
  variable, decimated resolution). Stateless, global, no tenancy.
- **`tfi-tenant-api`** (write, port `8120`): the only stateful/user-data service.
  Arrival/cancellation reports with logical undo, stored in a separate `reports.db`,
  each enriched by calling `gtfsr-stop-times`. See `tfi-tenant-api/README.md` for
  the endpoints, schema, and design decisions.
- **`gtfsr-collector`**: polls the NTA GTFS-R feed and writes timestamped `.pb.gz`
  snapshots that `gtfsr-stop-times` reads.

**Observability.** A **Grafana + Prometheus** stack (`busbet-exporter`, local
Grafana on `:3131`) scrapes the archive. Infra metrics today, not product/app
telemetry.

## 4. Hosts & network

- **tailnet (Tailscale)** ties the hosts together; internal service calls are plain
  HTTP over it.
- **Backend host**: currently a **Hetzner VM**, exposed publicly as
  **`pet.uzoukwu.net`** via a **Cloudflare tunnel** (origin behind Caddy path-routing
  `/<service>/`). This replaced the original `lab` Raspberry Pi
  (`lab.uzoukwu.net`) and is treated as a temporary deployment.
- **Dev workstation** (`no-backup-no-life`): builds the app, runs ad-hoc service
  instances.
- **Test phone** (`cmf-by-nothing-phone-2-pro`): wireless ADB over the tailnet.

## 5. Pointers

- `tfi-tenant-api/README.md` — the write API: endpoints, schema, design decisions,
  open questions. The only user-data write path.
- `gtfsr-stop-times/` — the read API; read-only and global by design.
- `TICKETS.md` — how to write tickets. Actual requests live in Linear.
- `SEED.md` — the original project brief, kept as history (it describes the earlier
  SvelteKit web clone in `app/`, not the current Android app).
