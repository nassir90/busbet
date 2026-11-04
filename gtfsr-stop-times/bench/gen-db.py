# Usage: python3 bench/gen-db.py <dataDir>/busbet.db
# Synthetic GTFS SQLite database with the schema load-gtfs.ts creates, and busy-codes.txt beside
# it: the 60 stops with the most stop_times, for the harness to hit.
import sqlite3, random, sys, os
random.seed(42)
path = sys.argv[1]
for p in (path, path + '-journal'):
    if os.path.exists(p): os.remove(p)
db = sqlite3.connect(path)
db.executescript('''
PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;
CREATE TABLE stops (stop_id TEXT PRIMARY KEY, stop_code TEXT, stop_name TEXT, stop_lat REAL, stop_lon REAL);
CREATE TABLE routes (route_id TEXT PRIMARY KEY, route_short_name TEXT, route_long_name TEXT, agency_id TEXT, route_type INTEGER);
CREATE TABLE trips (trip_id TEXT PRIMARY KEY, route_id TEXT, service_id TEXT, trip_headsign TEXT, direction_id INTEGER, shape_id TEXT);
CREATE TABLE stop_times (trip_id TEXT, stop_id TEXT, stop_sequence INTEGER, arrival_time TEXT, departure_time TEXT, PRIMARY KEY (trip_id, stop_sequence));
CREATE TABLE calendar (service_id TEXT PRIMARY KEY, monday INTEGER, tuesday INTEGER, wednesday INTEGER, thursday INTEGER, friday INTEGER, saturday INTEGER, sunday INTEGER, start_date TEXT, end_date TEXT);
CREATE TABLE calendar_dates (service_id TEXT, date TEXT, exception_type INTEGER, PRIMARY KEY (service_id, date));
CREATE TABLE shapes (shape_id TEXT, seq INTEGER, lat REAL, lon REAL, PRIMARY KEY (shape_id, seq));
CREATE TABLE _meta (key TEXT PRIMARY KEY, value TEXT);
''')
N_STOPS, N_ROUTES, N_TRIPS, STOPS_PER_TRIP, N_SERVICES = 10000, 400, 30000, 40, 20
words = ['Main', 'Church', 'Bridge', 'Park', 'Quay', 'Street', 'Road', 'Green', 'Square', 'Lane', 'Mill', 'Castle', 'Abbey', 'Dame', 'College', 'Bus', 'Station', 'Avenue']
stops = []
for i in range(N_STOPS):
    sid = f'8220DB{i:06d}'
    code = str(1000 + i)
    name = f'{random.choice(words)} {random.choice(words)} {i % 97}'
    stops.append((sid, code, name, 53.3 + random.random() * 0.2, -6.4 + random.random() * 0.3))
db.executemany('INSERT INTO stops VALUES (?,?,?,?,?)', stops)
routes = [(f'R{i}', str(i + 1) if i % 7 else f'{i // 7}X', f'Route {i} long', 'A1', 3 if i % 20 else 0) for i in range(N_ROUTES)]
db.executemany('INSERT INTO routes VALUES (?,?,?,?,?)', routes)
db.executemany('INSERT INTO calendar VALUES (?,1,1,1,1,1,1,1,?,?)', [(f'S{i}', '20260101', '20271231') for i in range(N_SERVICES)])
db.executemany('INSERT INTO calendar_dates VALUES (?,?,?)', [(f'S{i}', '20261225', 2) for i in range(N_SERVICES)])
# Each route has 2 directions; each direction has a fixed stop pattern. Busy stops: a pool of 300
# stops appears in many patterns so some stops see hundreds of departures a day.
busy = random.sample(range(N_STOPS), 300)
patterns = {}
for r in range(N_ROUTES):
    for d in (0, 1):
        pat = [random.choice(busy) if random.random() < 0.3 else random.randrange(N_STOPS) for _ in range(STOPS_PER_TRIP)]
        patterns[(r, d)] = pat
shapes = []
for r in range(N_ROUTES):
    for d in (0, 1):
        for s in range(111):
            shapes.append((f'SH{r}_{d}', s, 53.3 + s * 0.001, -6.3 + s * 0.001))
db.executemany('INSERT INTO shapes VALUES (?,?,?,?)', shapes)
trips, sts = [], []
def t2s(m): return f'{m // 60:02d}:{m % 60:02d}:00'
for i in range(N_TRIPS):
    r = i % N_ROUTES; d = (i // N_ROUTES) % 2
    tid = f'{i}.y1006.60-{r}-d12-1.{d}.O'
    trips.append((tid, f'R{r}', f'S{i % N_SERVICES}', f'Headsign {r}', d, f'SH{r}_{d}'))
    start = 300 + (i * 37) % 1200  # 05:00 .. 25:00
    for seq, s in enumerate(patterns[(r, d)], 1):
        m = start + seq * 2
        sts.append((tid, stops[s][0], seq, t2s(m), t2s(m)))
db.executemany('INSERT INTO trips VALUES (?,?,?,?,?,?)', trips)
db.executemany('INSERT INTO stop_times VALUES (?,?,?,?,?)', sts)
db.executescript('''
CREATE INDEX idx_stops_code ON stops(stop_code);
CREATE INDEX idx_stops_name ON stops(stop_name COLLATE NOCASE);
CREATE INDEX idx_trips_route ON trips(route_id);
CREATE INDEX idx_trips_service ON trips(service_id);
CREATE INDEX idx_st_stop ON stop_times(stop_id);
INSERT INTO _meta VALUES ('seeded', '1');
''')
db.commit()
# Codes of the busiest stops, for the benchmark to hit.
rows = db.execute('SELECT s.stop_code FROM stop_times st JOIN stops s ON s.stop_id = st.stop_id GROUP BY st.stop_id ORDER BY COUNT(*) DESC LIMIT 60').fetchall()
open(os.path.join(os.path.dirname(path), 'busy-codes.txt'), 'w').write('\n'.join(r[0] for r in rows))
print('stops', N_STOPS, 'trips', N_TRIPS, 'stop_times', len(sts))
