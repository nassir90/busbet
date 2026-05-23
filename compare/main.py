"""
Compare busbet departure data against bustimes.org for a given stop code.

Uses:
  - SQLite DB for scheduled departures + active service IDs
  - Latest on-disk GTFS-R protobuf feed for realtime delays (per stop, not per trip)
  - bustimes.org for ground truth
"""

import gzip
import re
import sqlite3
import sys
from datetime import datetime
from pathlib import Path

import requests
from google.transit import gtfs_realtime_pb2

DB_PATH     = Path('/home/lab/Projects/busbet/app/data/busbet.db')
FEEDS_DIR   = Path('/home/lab/Projects/busbet/gtfsr-collector/data/feeds')
STOP_CODE   = sys.argv[1] if len(sys.argv) > 1 else '3368'
WINDOW_MINS = 105

DAY_NAMES = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

def gtfs_date(d: datetime) -> str:
    return d.strftime('%Y%m%d')

def mins_to_gtfs(mins: int) -> str:
    h, m = divmod(abs(mins), 60)
    return f'{h:02d}:{m:02d}:00'

def get_active_service_ids(db: sqlite3.Connection, day_name: str, date_str: str) -> list:
    base = {row[0] for row in db.execute(
        f"SELECT service_id FROM calendar WHERE {day_name}=1 AND start_date<=? AND end_date>=?",
        (date_str, date_str)
    )}
    added = {row[0] for row in db.execute(
        "SELECT service_id FROM calendar_dates WHERE date=? AND exception_type=1", (date_str,)
    )}
    removed = {row[0] for row in db.execute(
        "SELECT service_id FROM calendar_dates WHERE date=? AND exception_type=2", (date_str,)
    )}
    return list((base | added) - removed)

def get_stop(db: sqlite3.Connection, code: str):
    row = db.execute(
        "SELECT stop_id, stop_name FROM stops WHERE stop_code=?", (code,)
    ).fetchone()
    if not row:
        sys.exit(f"Stop {code} not found in DB")
    return row

def get_scheduled_departures(db: sqlite3.Connection, stop_id: str, service_ids: list,
                              now: datetime, hour_offset: int = 0) -> list:
    if not service_ids:
        return []
    current_mins = now.hour * 60 + now.minute + hour_offset * 60
    end_mins     = current_mins + WINDOW_MINS
    placeholders = ','.join('?' * len(service_ids))
    rows = db.execute(f"""
        SELECT st.trip_id, st.stop_id, st.departure_time,
               r.route_short_name, t.trip_headsign
        FROM stop_times st
        JOIN trips t  ON t.trip_id  = st.trip_id
        JOIN routes r ON r.route_id = t.route_id
        WHERE st.stop_id = ?
          AND t.service_id IN ({placeholders})
          AND st.departure_time >= ?
          AND st.departure_time <= ?
        ORDER BY st.departure_time
        LIMIT 30
    """, [stop_id, *service_ids, mins_to_gtfs(current_mins), mins_to_gtfs(end_mins)]).fetchall()

    result = []
    for trip_id, sid, dep_time, route, headsign in rows:
        raw_h = int(dep_time[:2])
        display_h = raw_h - 24 if raw_h >= 24 else raw_h
        scheduled = f"{display_h:02d}:{dep_time[3:5]}"
        result.append({
            'trip_id':   trip_id,
            'stop_id':   sid,
            'route':     route,
            'headsign':  headsign,
            'scheduled': scheduled,
            'estimated': None,
            'delay_secs': None,
        })
    return result


# ---------------------------------------------------------------------------
# GTFS-R: per-stop delay lookup from latest on-disk feed
# ---------------------------------------------------------------------------

def load_latest_feed() -> gtfs_realtime_pb2.FeedMessage:
    latest = max(FEEDS_DIR.glob('*.pb.gz'), key=lambda p: p.name)
    print(f"[feed] {latest.name}")
    feed = gtfs_realtime_pb2.FeedMessage()
    feed.ParseFromString(gzip.decompress(latest.read_bytes()))
    return feed

def build_trip_updates(feed: gtfs_realtime_pb2.FeedMessage) -> dict:
    """
    Returns {trip_id: [(stop_sequence, stop_id, delay_secs), ...]} sorted by sequence.
    Enables delay propagation — if a stop has no explicit update, use the last
    known delay from an earlier stop in the same trip (standard GTFS-R practice).
    """
    trips = {}
    for entity in feed.entity:
        tu = entity.trip_update
        if not tu or not tu.trip.trip_id:
            continue
        trip_id = tu.trip.trip_id
        updates = []
        for stu in tu.stop_time_update:
            delay = None
            if stu.HasField('departure') and stu.departure.HasField('delay'):
                delay = stu.departure.delay
            elif stu.HasField('arrival') and stu.arrival.HasField('delay'):
                delay = stu.arrival.delay
            if delay is not None:
                updates.append((stu.stop_sequence, stu.stop_id, delay))
        if updates:
            trips[trip_id] = sorted(updates, key=lambda x: x[0])
    return trips

def get_stop_sequences(db: sqlite3.Connection, trip_ids: list, stop_id: str) -> dict:
    """Returns {trip_id: stop_sequence} for the given stop across all trips."""
    if not trip_ids:
        return {}
    placeholders = ','.join('?' * len(trip_ids))
    rows = db.execute(
        f"SELECT trip_id, stop_sequence FROM stop_times WHERE trip_id IN ({placeholders}) AND stop_id=?",
        [*trip_ids, stop_id]
    ).fetchall()
    return {row[0]: row[1] for row in rows}

def apply_delays(departures: list, trip_updates: dict, db: sqlite3.Connection) -> list:
    """Apply delays using propagation: use last known delay at or before our stop."""
    if not departures:
        return departures

    trip_ids = list({d['trip_id'] for d in departures})
    stop_id  = departures[0]['stop_id']
    sequences = get_stop_sequences(db, trip_ids, stop_id)

    for dep in departures:
        updates = trip_updates.get(dep['trip_id'])
        if not updates:
            continue
        our_seq = sequences.get(dep['trip_id'])
        if our_seq is None:
            continue

        # Find the last update at or before our stop sequence
        delay = None
        for seq, sid, d in updates:
            if seq <= our_seq:
                delay = d
            else:
                break

        if delay is not None:
            dep['delay_secs'] = delay
            h, m = map(int, dep['scheduled'].split(':'))
            total = h * 60 + m + round(delay / 60)
            rh, rm = divmod(max(0, total), 60)
            dep['estimated'] = f"{rh % 24:02d}:{rm:02d}"
    return departures


# ---------------------------------------------------------------------------
# bustimes.org ground truth
# ---------------------------------------------------------------------------

def fetch_bustimes(stop_code: str) -> list:
    url = f"https://bustimes.org/stops/8230DB00{stop_code}"
    r = requests.get(url, headers={'User-Agent': 'busbet-compare/1.0'}, timeout=10)
    rows = re.findall(r'<tr[^>]*>(.*?)</tr>', r.text, re.DOTALL)
    results = []
    for row in rows:
        cells = re.findall(r'<t[hd][^>]*>(.*?)</t[hd]>', row, re.DOTALL)
        cells = [' '.join(re.sub(r'<[^>]+>', '', c).split()) for c in cells if c.strip()]
        if len(cells) >= 3 and re.match(r'\d{1,2}:\d{2}', cells[2]):
            results.append({
                'route':     cells[0],
                'headsign':  cells[1],
                'scheduled': cells[2],
                'estimated': cells[3] if len(cells) > 3 else None,
            })
    return results


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    now = datetime.now()
    db  = sqlite3.connect(str(DB_PATH))

    stop_id, stop_name = get_stop(db, STOP_CODE)
    print(f"\nStop {STOP_CODE}: {stop_name} (id={stop_id})")
    print(f"Time: {now.strftime('%H:%M:%S')}  Window: +{WINDOW_MINS}min\n")

    today    = gtfs_date(now)
    day_name = DAY_NAMES[now.weekday()]
    svc_ids  = get_active_service_ids(db, day_name, today)
    print(f"Active services ({day_name} {today}): {len(svc_ids)}")

    deps = get_scheduled_departures(db, stop_id, svc_ids, now)
    print(f"Scheduled: {len(deps)}")

    feed         = load_latest_feed()
    trip_updates = build_trip_updates(feed)
    deps         = apply_delays(deps, trip_updates, db)

    rt = sum(1 for d in deps if d['delay_secs'] is not None)
    print(f"Realtime matches: {rt}/{len(deps)}\n")

    print(f"{'OURS':=<62}")
    print(f"{'Route':<8} {'Headsign':<32} {'Sched':>6} {'Est':>6} {'Delay':>7}")
    print('-' * 62)
    for d in deps:
        delay_str = f"{d['delay_secs']:+.0f}s" if d['delay_secs'] is not None else ''
        print(f"{d['route']:<8} {d['headsign']:<32} {d['scheduled']:>6} {d['estimated'] or '':>6} {delay_str:>7}")

    print(f"\n{'BUSTIMES.ORG':=<62}")
    bt = fetch_bustimes(STOP_CODE)
    print(f"{'Route':<8} {'Headsign':<32} {'Sched':>6} {'Est':>6}")
    print('-' * 62)
    for d in bt:
        print(f"{d['route']:<8} {d['headsign']:<32} {d['scheduled']:>6} {d['estimated'] or '':>6}")

    print(f"\n{'DIFF':=<62}")
    our_set = {(d['route'], d['scheduled']) for d in deps}
    bt_set  = {(d['route'], d['scheduled']) for d in bt}
    missing = sorted(bt_set - our_set)
    extra   = sorted(our_set - bt_set)
    if not missing and not extra:
        print("  Perfect match")
    for r, t in missing: print(f"  MISSING from ours: {r} at {t}")
    for r, t in extra:   print(f"  EXTRA in ours:     {r} at {t}")

if __name__ == '__main__':
    main()
