#!/usr/bin/env python3
"""
Re-implements the app's route-line geometry against live API data, so the maths can be checked
without a build/install/look cycle.

Mirrors StopMap.kt: nearestPointIndex -> pointsAhead -> resampleEvenly, and reports where each
line actually starts relative to the bus.

    python3 scripts/debug-route-lines.py [stop_code]     # default 399, Pearse Street
"""

import json
import math
import subprocess
import sys

BASE = "https://pet.uzoukwu.net/gtfsr-stop-times"
LINE_AHEAD_M = 700.0
FADE_STEPS = 28
M_PER_DEG_LAT = 111_320.0


def get(path):
    # curl, not urllib: Cloudflare 403s urllib's default user agent.
    out = subprocess.run(
        ["curl", "-s", "-m", "25", f"{BASE}{path}"], capture_output=True, text=True
    ).stdout
    return json.loads(out) if out.strip() else None


def metres(a_lat, a_lon, b_lat, b_lon):
    kx = math.cos(math.radians((a_lat + b_lat) / 2)) * M_PER_DEG_LAT
    return math.hypot((a_lon - b_lon) * kx, (a_lat - b_lat) * M_PER_DEG_LAT)


def nearest_point_index(points, lat, lon):
    """What StopMap.kt does today: nearest *vertex*."""
    return min(range(len(points)), key=lambda i: metres(lat, lon, points[i]["lat"], points[i]["lon"]))


def project_onto_polyline(points, lat, lon):
    """Nearest point on the polyline itself, not just a vertex. Returns (seg_index, t, lat, lon, dist)."""
    best = (0, 0.0, points[0]["lat"], points[0]["lon"], float("inf"))
    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        kx = math.cos(math.radians((a["lat"] + b["lat"]) / 2)) * M_PER_DEG_LAT
        ax, ay = a["lon"] * kx, a["lat"] * M_PER_DEG_LAT
        bx, by = b["lon"] * kx, b["lat"] * M_PER_DEG_LAT
        px, py = lon * kx, lat * M_PER_DEG_LAT
        dx, dy = bx - ax, by - ay
        len2 = dx * dx + dy * dy
        t = 0.0 if len2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        qx, qy = ax + t * dx, ay + t * dy
        d = math.hypot(px - qx, py - qy)
        if d < best[4]:
            best = (i, t, a["lat"] + (b["lat"] - a["lat"]) * t, a["lon"] + (b["lon"] - a["lon"]) * t, d)
    return best


def polyline_length(points):
    return sum(
        metres(points[i]["lat"], points[i]["lon"], points[i + 1]["lat"], points[i + 1]["lon"])
        for i in range(len(points) - 1)
    )


def main():
    code = sys.argv[1] if len(sys.argv) > 1 else "399"
    stop = get(f"/stops/{code}")
    vehicles = get(f"/vehicles/{code}?window=30") or []
    print(f"stop {code}: {stop['stop_name']}  ({stop['stop_lat']:.5f},{stop['stop_lon']:.5f})")
    print(f"{len(vehicles)} vehicles\n")

    if not vehicles:
        print("no vehicles right now — rerun during service hours")
        return 0

    print(f"{'route':>6} {'shape':>6} {'vtx→bus':>8} {'proj→bus':>9} {'startErr':>9} {'behind?':>8} {'runLen':>7}")
    print("-" * 64)

    for v in vehicles:
        pts = get(f"/shapes/trip/{v['trip_id']}")
        if not pts:
            print(f"{v['route_short_name']:>6} {'NONE':>6}   (no shape for this trip)")
            continue

        # What the app does now.
        idx = nearest_point_index(pts, v["lat"], v["lon"])
        vtx = pts[idx]
        vtx_dist = metres(v["lat"], v["lon"], vtx["lat"], vtx["lon"])

        # What it should do.
        seg_i, t, plat, plon, proj_dist = project_onto_polyline(pts, v["lat"], v["lon"])

        # Is the chosen start vertex before the bus along the route?
        behind = "YES" if idx <= seg_i else "no"

        # How far the drawn line's start is from the bus, along the route.
        start_err = metres(vtx["lat"], vtx["lon"], plat, plon)

        run = pts[idx:]
        acc, end = 0.0, idx
        while end < len(pts) - 1 and acc < LINE_AHEAD_M:
            acc += metres(pts[end]["lat"], pts[end]["lon"], pts[end + 1]["lat"], pts[end + 1]["lon"])
            end += 1
        run = pts[idx : end + 1]

        print(
            f"{v['route_short_name']:>6} {len(pts):>6} {vtx_dist:>7.0f}m {proj_dist:>8.0f}m "
            f"{start_err:>8.0f}m {behind:>8} {polyline_length(run):>6.0f}m"
        )

    print()
    print("vtx→bus   distance from bus to the vertex the line starts at (current behaviour)")
    print("proj→bus  distance from bus to the nearest point ON the line (ideal)")
    print("startErr  how far the line's start is from where the bus actually is")
    print("behind?   whether the line starts BEFORE the bus along the route")
    return 0


if __name__ == "__main__":
    sys.exit(main())
