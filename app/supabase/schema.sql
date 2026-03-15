-- BusBet Supabase Schema
-- Run this in your Supabase SQL editor before using STORAGE_BACKEND=supabase

CREATE TABLE IF NOT EXISTS stops (
  stop_id   TEXT PRIMARY KEY,
  stop_code TEXT,
  stop_name TEXT,
  stop_lat  FLOAT,
  stop_lon  FLOAT
);
CREATE INDEX IF NOT EXISTS idx_stops_code ON stops(stop_code);
CREATE INDEX IF NOT EXISTS idx_stops_name ON stops USING gin(to_tsvector('simple', stop_name));

CREATE TABLE IF NOT EXISTS routes (
  route_id         TEXT PRIMARY KEY,
  route_short_name TEXT,
  route_long_name  TEXT,
  agency_id        TEXT
);

CREATE TABLE IF NOT EXISTS trips (
  trip_id       TEXT PRIMARY KEY,
  route_id      TEXT,
  service_id    TEXT,
  trip_headsign TEXT,
  direction_id  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_trips_route   ON trips(route_id);
CREATE INDEX IF NOT EXISTS idx_trips_service ON trips(service_id);

CREATE TABLE IF NOT EXISTS stop_times (
  trip_id        TEXT,
  stop_id        TEXT,
  stop_sequence  INTEGER,
  arrival_time   TEXT,
  departure_time TEXT,
  PRIMARY KEY (trip_id, stop_sequence)
);
CREATE INDEX IF NOT EXISTS idx_st_stop ON stop_times(stop_id);
CREATE INDEX IF NOT EXISTS idx_st_trip ON stop_times(trip_id);

CREATE TABLE IF NOT EXISTS calendar (
  service_id TEXT PRIMARY KEY,
  monday     INTEGER,
  tuesday    INTEGER,
  wednesday  INTEGER,
  thursday   INTEGER,
  friday     INTEGER,
  saturday   INTEGER,
  sunday     INTEGER,
  start_date TEXT,
  end_date   TEXT
);

CREATE TABLE IF NOT EXISTS calendar_dates (
  service_id     TEXT,
  date           TEXT,
  exception_type INTEGER,
  PRIMARY KEY (service_id, date)
);

-- Wager tables
CREATE TABLE IF NOT EXISTS users (
  user_id    TEXT PRIMARY KEY,
  balance    FLOAT NOT NULL DEFAULT 100.0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS wagers (
  wager_id            TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL REFERENCES users(user_id),
  trip_id             TEXT NOT NULL,
  stop_id             TEXT NOT NULL,
  scheduled_departure TEXT NOT NULL,
  scheduled_date      TEXT NOT NULL,
  wager_type          TEXT NOT NULL,
  drift_minutes       INTEGER,
  stake               FLOAT NOT NULL,
  status              TEXT NOT NULL DEFAULT 'open',
  placed_at           TEXT NOT NULL,
  resolved_at         TEXT,
  payout              FLOAT
);
CREATE INDEX IF NOT EXISTS idx_wagers_user ON wagers(user_id);
CREATE INDEX IF NOT EXISTS idx_wagers_trip ON wagers(trip_id, stop_id, status);

CREATE TABLE IF NOT EXISTS tracked_trips (
  trip_id                  TEXT NOT NULL,
  stop_id                  TEXT NOT NULL,
  scheduled_departure      TEXT NOT NULL,
  scheduled_date           TEXT NOT NULL,
  last_estimated_departure TEXT,
  last_seen_at             TEXT,
  status                   TEXT NOT NULL DEFAULT 'tracking',
  PRIMARY KEY (trip_id, stop_id)
);

-- Used by Supabase backend to safely increment user balance
CREATE OR REPLACE FUNCTION increment_balance(p_user_id TEXT, p_delta FLOAT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  UPDATE users SET balance = balance + p_delta WHERE user_id = p_user_id;
END;
$$;

-- Helper used by load-gtfs to truncate tables before re-importing
CREATE OR REPLACE FUNCTION truncate_table(tbl TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
  EXECUTE format('TRUNCATE TABLE %I RESTART IDENTITY CASCADE', tbl);
END;
$$;

-- RPC function used by the Supabase backend
CREATE OR REPLACE FUNCTION get_scheduled_departures(
  p_stop_id      TEXT,
  p_service_ids  TEXT[],
  p_from_time    TEXT,
  p_to_time      TEXT
)
RETURNS TABLE(
  trip_id          TEXT,
  departure_time   TEXT,
  route_short_name TEXT,
  trip_headsign    TEXT
)
LANGUAGE SQL STABLE AS $$
  SELECT
    st.trip_id,
    st.departure_time,
    r.route_short_name,
    t.trip_headsign
  FROM stop_times st
  JOIN trips  t ON t.trip_id  = st.trip_id
  JOIN routes r ON r.route_id = t.route_id
  WHERE st.stop_id = p_stop_id
    AND t.service_id = ANY(p_service_ids)
    AND st.departure_time >= p_from_time
    AND st.departure_time <= p_to_time
  ORDER BY st.departure_time
  LIMIT 30;
$$;
