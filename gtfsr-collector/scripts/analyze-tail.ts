/**
 * Analyze whether buses keep reporting GPS after they stop moving.
 * Focus on C2 and P29 trips.
 */

import { readFileSync } from "fs";

interface Position {
  ts: number;
  tripId: string;
  route: string;
  directionId: number;
  vehicleId: string;
  lat: number;
  lon: number;
  delaySec: number | null;
}

interface Data {
  timestamps: number[];
  positions: Position[];
}

const data: Data = JSON.parse(
  readFileSync("/media/projects/vibes/busbet/gtfsr-collector/static/positions.json", "utf-8")
);

// Group positions by tripId, filtering to C2 and P29
const tripMap = new Map<string, Position[]>();
for (const p of data.positions) {
  if (p.route !== "C2" && p.route !== "P29") continue;
  let arr = tripMap.get(p.tripId);
  if (!arr) {
    arr = [];
    tripMap.set(p.tripId, arr);
  }
  arr.push(p);
}

// Sort each trip's positions by timestamp
for (const [, positions] of tripMap) {
  positions.sort((a, b) => a.ts - b.ts);
}

// Deduplicate: keep only one position per unique timestamp
for (const [tripId, positions] of tripMap) {
  const deduped: Position[] = [];
  let lastTs = -1;
  for (const p of positions) {
    if (p.ts !== lastTs) {
      deduped.push(p);
      lastTs = p.ts;
    }
  }
  tripMap.set(tripId, deduped);
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function fmtDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}m${s.toFixed(0)}s`;
}

function fmtTime(ts: number): string {
  return new Date(ts * 1000).toISOString().replace("T", " ").replace(".000Z", "");
}

// Pick trips with enough data points (>20) — take up to 5 per route
const selectedTrips: string[] = [];
const routeCounts = { C2: 0, P29: 0 };
const sorted = [...tripMap.entries()]
  .filter(([, pos]) => pos.length > 20)
  .sort((a, b) => b[1].length - a[1].length);

for (const [tripId, pos] of sorted) {
  const route = pos[0].route as "C2" | "P29";
  if (routeCounts[route] < 4) {
    selectedTrips.push(tripId);
    routeCounts[route]++;
  }
  if (routeCounts.C2 >= 4 && routeCounts.P29 >= 4) break;
}

console.log(`\nFound ${tripMap.size} C2/P29 trips total. Analyzing ${selectedTrips.length} with most data points.\n`);

for (const tripId of selectedTrips) {
  const positions = tripMap.get(tripId)!;
  const route = positions[0].route;
  const vehicleId = positions[0].vehicleId;
  const firstTs = positions[0].ts;
  const lastTs = positions[positions.length - 1].ts;
  const totalSpan = lastTs - firstTs;

  // Compute inter-position distances
  const segments: { fromIdx: number; dist: number; dt: number }[] = [];
  for (let i = 1; i < positions.length; i++) {
    const prev = positions[i - 1];
    const curr = positions[i];
    const dist = haversineMeters(prev.lat, prev.lon, curr.lat, curr.lon);
    const dt = curr.ts - prev.ts;
    segments.push({ fromIdx: i - 1, dist, dt });
  }

  // Find when the bus "stops moving": last segment where cumulative distance
  // from the end is > 50m (i.e., the bus moved meaningfully after that point)
  // We want to find the point where remaining travel < 50m
  let cumulFromEnd = 0;
  let lastMovingIdx = positions.length - 1;
  for (let i = segments.length - 1; i >= 0; i--) {
    cumulFromEnd += segments[i].dist;
    if (cumulFromEnd > 50) {
      lastMovingIdx = i + 1; // the position AFTER which the bus barely moved
      break;
    }
  }

  const lastMovingTs = positions[lastMovingIdx].ts;
  const tailDuration = lastTs - lastMovingTs;
  const tailPositions = positions.length - lastMovingIdx;

  // Total distance traveled
  const totalDist = segments.reduce((s, seg) => s + seg.dist, 0);

  // Distance in the tail
  const tailDist = segments
    .slice(lastMovingIdx)
    .reduce((s, seg) => s + seg.dist, 0);

  // Final position
  const finalPos = positions[positions.length - 1];
  const lastMovingPos = positions[lastMovingIdx];

  console.log(`=== ${route} trip ${tripId} (vehicle ${vehicleId}) ===`);
  console.log(`  Positions: ${positions.length} reports`);
  console.log(`  Time span: ${fmtDuration(totalSpan)} (${fmtTime(firstTs)} → ${fmtTime(lastTs)})`);
  console.log(`  Total distance: ${(totalDist / 1000).toFixed(2)} km`);
  console.log(`  --- Tail analysis (movement < 50m from end) ---`);
  console.log(`  Last "moving" position: index ${lastMovingIdx} at ${fmtTime(lastMovingTs)}`);
  console.log(`    Location: ${lastMovingPos.lat.toFixed(5)}, ${lastMovingPos.lon.toFixed(5)}`);
  console.log(`  Final position: ${fmtTime(lastTs)}`);
  console.log(`    Location: ${finalPos.lat.toFixed(5)}, ${finalPos.lon.toFixed(5)}`);
  console.log(`  Tail duration: ${fmtDuration(tailDuration)} (${tailPositions} positions still reporting)`);
  console.log(`  Tail drift: ${tailDist.toFixed(1)}m across those ${tailPositions} positions`);

  // Show the last 8 positions with gaps and movement
  console.log(`  --- Last 10 positions ---`);
  const showFrom = Math.max(0, positions.length - 10);
  for (let i = showFrom; i < positions.length; i++) {
    const p = positions[i];
    const distFromPrev =
      i > 0
        ? haversineMeters(positions[i - 1].lat, positions[i - 1].lon, p.lat, p.lon).toFixed(0)
        : "-";
    const gap = i > 0 ? p.ts - positions[i - 1].ts : 0;
    const marker = i === lastMovingIdx ? " ◄ LAST MOVING" : i > lastMovingIdx ? " (tail)" : "";
    console.log(
      `    [${i}] ${fmtTime(p.ts)} | +${gap}s | ${distFromPrev}m | (${p.lat.toFixed(5)}, ${p.lon.toFixed(5)}) | delay=${p.delaySec ?? "null"}${marker}`
    );
  }
  console.log();
}

// Summary statistics
console.log("=== SUMMARY ===");
console.log("Across all C2/P29 trips with >20 positions:");

const allStats: { route: string; tripId: string; totalSpan: number; tailDuration: number; tailPositions: number; tailDist: number }[] = [];
for (const [tripId, positions] of tripMap) {
  if (positions.length <= 20) continue;
  const totalSpan = positions[positions.length - 1].ts - positions[0].ts;
  const segments: { dist: number }[] = [];
  for (let i = 1; i < positions.length; i++) {
    segments.push({
      dist: haversineMeters(
        positions[i - 1].lat, positions[i - 1].lon,
        positions[i].lat, positions[i].lon
      ),
    });
  }
  let cumulFromEnd = 0;
  let lastMovingIdx = positions.length - 1;
  for (let i = segments.length - 1; i >= 0; i--) {
    cumulFromEnd += segments[i].dist;
    if (cumulFromEnd > 50) {
      lastMovingIdx = i + 1;
      break;
    }
  }
  const lastMovingTs = positions[lastMovingIdx].ts;
  const tailDuration = positions[positions.length - 1].ts - lastMovingTs;
  const tailPositions = positions.length - lastMovingIdx;
  const tailDist = segments.slice(lastMovingIdx).reduce((s, seg) => s + seg.dist, 0);

  allStats.push({
    route: positions[0].route,
    tripId,
    totalSpan,
    tailDuration,
    tailPositions,
    tailDist,
  });
}

allStats.sort((a, b) => b.tailDuration - a.tailDuration);

console.log(`\nTrips analyzed: ${allStats.length}`);
console.log(`\nTail duration (time reporting after < 50m movement):`);
const tailDurations = allStats.map((s) => s.tailDuration);
tailDurations.sort((a, b) => a - b);
const median = tailDurations[Math.floor(tailDurations.length / 2)];
const mean = tailDurations.reduce((s, v) => s + v, 0) / tailDurations.length;
const p90 = tailDurations[Math.floor(tailDurations.length * 0.9)];
const p95 = tailDurations[Math.floor(tailDurations.length * 0.95)];
console.log(`  Min:    ${fmtDuration(tailDurations[0])}`);
console.log(`  Median: ${fmtDuration(median)}`);
console.log(`  Mean:   ${fmtDuration(Math.round(mean))}`);
console.log(`  P90:    ${fmtDuration(p90)}`);
console.log(`  P95:    ${fmtDuration(p95)}`);
console.log(`  Max:    ${fmtDuration(tailDurations[tailDurations.length - 1])}`);

console.log(`\nTop 10 longest tails:`);
for (const s of allStats.slice(0, 10)) {
  console.log(
    `  ${s.route} ${s.tripId}: tail ${fmtDuration(s.tailDuration)} (${s.tailPositions} positions, ${s.tailDist.toFixed(0)}m drift, trip span ${fmtDuration(s.totalSpan)})`
  );
}
