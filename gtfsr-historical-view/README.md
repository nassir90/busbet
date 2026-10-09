# gtfsr-historical-view

Browser-side explorer for archived Dublin Bus GTFS-R position data.

## What it is
A single-page React + Leaflet app that replays recorded bus positions over time.
Loads a `positions.json` file produced by `gtfsr-collector/scripts/extract-positions.ts`.

## Run
```
npm run serve
# → http://localhost:3333
```

Regenerate the data file from archived feeds:
```
cd ../gtfsr-collector
npx tsx scripts/extract-positions.ts C2 C1 L53 L51 P29
# writes to ../gtfsr-historical-view/static/positions.json
```

## Layout (matches the `bus-bet` design bundle)
- Full-screen OpenStreetMap map behind everything (filtered dark or light with the theme)
- Floating card column on the left (Date · Routes · Stats · Active Vehicles · Settings · optional Tweaks)
- Full-width time bar at the bottom with play/pause, slider, hour ticks, speed buttons
- Dark/light theme toggle in the header

## Data flow
`positions.json` → `{ timestamps, positions }` where each position carries
`{ ts, tripId, route, directionId, vehicleId, lat, lon, delaySec }`.
The viewer indexes points by `tripId`, then for every animation frame:

1. Picks a `ts` from `timestamps` (slider or playback).
2. For each trip, binary-searches for the nearest sample before `ts`.
3. Linearly interpolates to the next sample unless the gap is too long or the
   coordinate jump too large (likely GPS noise).
4. Walks backwards from each trip's tail, discarding post-arrival idle drift
   (< 50m cumulative), to find the real end time — buses parked at terminus
   stop rendering shortly after they finish moving.
