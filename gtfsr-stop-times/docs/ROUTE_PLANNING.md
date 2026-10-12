# Journey planning: prototype and trade-offs

Branch `route-planning`, 12–13 October 2026. Walk + bus/Luas/rail journey planning, saved places
in the app, and a look at fuel prices.

## What was built

| Piece | Where |
|---|---|
| RAPTOR planner over one service day, in typed arrays | `src/plan/` (`timetable.ts`, `raptor.ts`, `planner.ts`) |
| Realtime: GTFS-R delays and cancellations folded into the timetable | `src/plan/realtime.ts` |
| `POST /plan`, off unless `PLANNER=1` | `server.ts` |
| Timetable built in a child process (see Memory) | `src/plan/build-child.ts` |
| Tests on a toy network | `test/plan.test.ts` |
| Benchmarks, export for the phone, fixtures | `scripts/plan-cli.ts`, `scripts/export-ondevice.ts`, `scripts/plan-fixture.ts` |
| App: journey screen, itinerary map, saved places (home/work/other) | `tfi-app/.../JourneyScreen.kt`, `JourneyMap.kt`, `SavedPlaces.kt` |
| On-device experiment: the same RAPTOR in Kotlin | `tfi-app/app/src/test/.../plan/OnDeviceRaptor*.kt` |

Request:

```json
POST /plan
{"from": {"lat": 53.3433, "lon": -6.4498}, "to": {"lat": 53.3468, "lon": -6.2585}, "depart_at": 1791880200}
```

The response holds itineraries, each a list of legs. A leg is either a walk or a ride on one vehicle, with times, the trip id, the stops, realtime delay and geometry.

POST rather than GET keeps both points out of URLs, so they never reach a request log. The server neither logs nor keeps them.

## The algorithm

RAPTOR (Delling, Pajor & Werneck 2012). Round *k* finds the earliest arrival at every stop using at most *k* vehicles. The result is a Pareto set over arrival time and number of changes.

Data preparation:
- GTFS routes are regrouped into patterns: identical stop sequences, split wherever one trip would overtake another.
- Walking transfers between stops within 400 m are precomputed.
- Late trips from the previous day (25:10 and the like) are included, so 00:30 queries work. So are the next day's early trips.

Planning:
- Alternatives come from re-running at successively later departures.
- Change buffers: 2 min to board after getting off another vehicle, 1 min after a walking transfer. These were tuned against Google Maps (below).
- Itineraries are filtered by dominance. On top of that, a change has to save about 6 minutes to be shown over a journey with fewer changes.

Realtime handling:
- Delays propagate the same way as on the departure boards.
- Delays outside −10/+60 min are ignored. That was about 5% of updated trips in a Monday 08:30 snapshot (167 of 3,491), including +113 and −45 min. Planning on them produced journeys that arrived before they left.
- Trip times are forced to be non-decreasing.
- Patterns that delays put out of order are searched linearly.

## Numbers

One weekday (Tue 13 Oct 2026) from `GTFS_Realtime.zip`:
- 10,262 stops
- 1,586 patterns
- 20,708 trips
- 810,403 stop events
- 60,902 walking transfers

All timings were taken on the workstation (i5-6300U, 2015 laptop).

### Hosted (Node, the backend)

| | |
|---|---|
| Build a day from SQLite | 13.9 s |
| Typed arrays per day | 10.6 MB |
| One RAPTOR run | 3–10 ms |
| One `/plan` (3–5 runs, shapes from SQLite) | median 13 ms, max 24 ms |
| End to end over HTTP, locally | ~200 ms |

### Memory on the gcp host

The host's spare RAM is what keeps the 1.3 GB database in page cache: 1.18 GB of it was resident when measured. So the planner's footprint matters more than its speed.

| How the timetable is built | Service RSS after build |
|---|---|
| In-process | +406 MB (heap after GC only +5 MB; V8/glibc keep the pages) |
| Worker thread | +450 MB (freed, not returned to the OS) |
| **Child process** (`build-child.ts`) | **+31 MB** one day, **+85 MB** two days cached |

The child peaks at about 500 MB for about 14 s, once a day per cached date. That briefly competes with the page cache. Building from compact arrays instead of row objects would cut the peak further.

### On the phone (Kotlin, JVM)

| | |
|---|---|
| One day as a flat binary | 7.8 MB raw, 3.0 MB gzip, **1.24 MB brotli** |
| A week (Tue–Mon) | 8.1 MB brotli as separate days, **3.9 MB** compressed together |
| Times with a purpose-built encoding (minute deltas) | 1.55 MB instead of 6.18 MB raw |
| Load (memory-mapped) | 313 ms |
| One RAPTOR run, warm | 6–17 ms (cold up to 67 ms) |
| Agreement with the Node planner | 8 of 8 journeys, identical arrival per round |

A current phone's single core is roughly as fast as this laptop's.

Realtime on the phone would mean fetching delays:

| Payload | Size |
|---|---|
| Rush-hour GTFS-R snapshot, raw protobuf | 978 KB |
| Same, gzip | 244 KB |
| Delays only (trip, stop_sequence:delay where it changes) | 43 KB brotli |

## Against existing planners

Depart Tuesday 13 Oct 08:30; the earliest arrival each planner offered (Transitous returns UTC, converted here):

| Journey | Ours | Google Maps | Transitous (MOTIS) |
|---|---|---|---|
| Finnstown Abbey → Aston Quay | 52, 08:38–09:34 | 52, 08:38–09:35 | C1+39, 08:35–09:38 |
| Lucan Village → St James's | 115, 08:35–09:17 | C3+Red, 08:42–09:27 | C4+Red, 08:39–09:23 |
| Heuston → Airport | 126+100X, 08:31–09:29 | Red+Dublin Express, 08:30–09:26 | Red+702, 08:35–09:22 |
| Tallaght → DCU | Red+101, 08:32–09:55 | Red+E2, 08:32–10:01 | Red+24+E1, 08:40–09:58 |
| Howth → Trinity | DART, 08:47–09:34 | DART, 08:47–09:38 | DART, 08:48–09:41 |
| Blanchardstown → UCD | 39A, 08:33–10:01 | 39A, 08:32–10:01 | 39A, 08:31–10:02 (a 4-change option arrives 09:52) |
| Dún Laoghaire → Phibsborough | DART+103, 08:31–09:20 | DART+E2, 08:31–09:24 | DART+73+E1, 08:31–09:25 |
| Swords → Tallaght Hospital | 33+Red, 08:37–10:21 | 41+750+Red, 08:33–10:05 | 33A+750+Red, 08:33–09:51 |


Where the route matches, arrivals agree within about 4 minutes, and ours are the optimistic ones. Two causes:

1. **Walking.** We use straight line × 1.3 at 1.3 m/s. Transitous's walks are street-routed. Across 34 of its walking legs, the routed distance was a median **1.38×** straight line, with p90 **2.1×** and a maximum of 3.3×. Housing estates and the Liffey are where the straight line is worst.
2. **Coverage.** The backend loads `GTFS_Realtime.zip`, the operators with realtime. Google and Transitous also plan on Dublin Express, Aircoach, Swords Express (500) and JJ Kavanagh (726/750). Those are in `GTFS_All.zip`. That's why both find faster airport and Swords journeys than we do.

## On-device or hosted?

| | Hosted RAPTOR (built) | On-device RAPTOR (measured) | Transitous | Self-hosted MOTIS |
|---|---|---|---|---|
| Where the start and end go | Our server; not logged or stored | Nowhere | Transitous (logs URL + IP for ≤2 days) | Our server |
| Data on the phone | None | 1.2 MB/day, ~4 MB/week, refreshed when NTA republishes | None | None |
| Realtime | Folded in on the server | Separate delays fetch, 43–244 KB each | Yes | Yes |
| Walking | Straight line × 1.3 | Straight line × 1.3 | Street-routed (OSM) | Street-routed (OSM) |
| Works offline | No | Yes, schedule only | No | No |
| Cost | +31–85 MB RSS, plus a daily 14 s build child | Phone CPU ~0.1 s per plan; a second implementation to keep in step | Free; non-commercial only; asks to be contacted first; no SLA | ~1 GB RAM estimated; tight on e2-medium |
| Fits "thin client, no domain logic on device" | Yes | No | Yes, if proxied | Yes |

Recommendation: keep planning hosted, as built.
- It keeps the app thin.
- Realtime stays one server-side step.
- The privacy cost is contained by POST + no logging, and stated in the policy.

On-device planning is clearly feasible (fast, small, identical results). It's worth revisiting if offline use or never sending locations becomes a product goal. The Kotlin port and the export format are the starting point.

## Not done yet

- **Street-routed walking.** The biggest accuracy gap. Precompute stop-to-stop and access walking times from OSM, either with MOTIS's one-to-many endpoint or a small foot graph from a Geofabrik extract.
- **Load `GTFS_All.zip` for planning**, to include the commercial operators (schedule only).
- **Range RAPTOR**, for more departure options, and **arrive-by**.
- **Address search.** Places come from your location, a stop, a saved place or a pin on the map.
  - Nominatim's policy forbids search-as-you-type.
  - Transitous and Photon have geocoders.
- **The DST night.** Times use GTFS's noon-minus-12h, which is right; this hasn't been tested across 25 Oct.
- **Disclosures, before release:** the Data safety form ("location: collected, processed ephemerally, for app functionality") and the regenerated privacy-policy page.

## Fuel prices: not built

The condition was per-station prices online, with a suitable update frequency and access policy. For Dublin, nothing meets that:

| Source | Coverage | Verdict |
|---|---|---|
| Government (CCPC, SEAI, data.gov.ie) | — | No scheme. "PumpWatch" (Mar 2026) is only a proposal. |
| pickapump.com | ROI + UK, crowdsourced | ToS forbid extracting or republishing prices, and API use other than "human-driven" |
| FuelWatch.ie, FuelFinder.ie | ROI, crowdsourced | ToS forbid reuse; FuelWatch allows a "separate agreement" |
| Retailers (Applegreen, Circle K, Maxol, …) | — | No public ROI price feeds |
| CSO CPM12 / EU Oil Bulletin | National average, monthly/weekly | Open (CC BY), but not per station |
| UK Fuel Finder (statutory, OGL + fair-use policy) | UK incl. Northern Ireland, ≤35 min | Usable server-side only; registration needed; returns 403 from Ireland, reachable from the London host |

What would be legitimate:
- station locations from OpenStreetMap, cached on the server (192 `amenity=fuel` in Co. Dublin, 96% named);
- a national average, labelled as one;
- live prices for Northern Ireland only.

Per-station Dublin prices need written permission from FuelWatch or pickapump, or a PumpWatch-style law.
