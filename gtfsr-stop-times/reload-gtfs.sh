#!/bin/sh
#
# Reloads the GTFS timetable when the NTA publishes a new one, then restarts gtfsr-stop-times.
# Run by busbet-reload.timer.
#
# Why: every new GTFS_Realtime.zip renumbers the trips (5965_… became 6009_…, then 6010_…
# became 6016_…), and the realtime feed switches to the new numbers at once. Until the
# timetable is reloaded no departure matches its live update, so every board shows schedule
# only. That happened on 27 Oct and 2 Nov 2026, both times found by a user.
#
# Cheap when nothing changed: one HEAD request, compared with the Last-Modified of the zip
# last loaded. load-gtfs.ts builds into a new file and swaps it in only on success, so a
# failed load leaves the live database alone and this exits non-zero without restarting.
#
# Optional environment (defaults are the gcp box's layout):
#   GTFS_ZIP_URL  zip to watch; must be the one load-gtfs.ts downloads
#   APP_DIR       gtfsr-stop-times checkout
#   DATABASE_URL  live database
#   STATE_FILE    where the last loaded Last-Modified is kept
set -eu

URL=${GTFS_ZIP_URL:-https://www.transportforireland.ie/transitData/Data/GTFS_Realtime.zip}
APP=${APP_DIR:-$HOME/Projects/busbet/gtfsr-stop-times}
DB=${DATABASE_URL:-$HOME/Projects/busbet/data/busbet.db}
STATE=${STATE_FILE:-$HOME/Projects/busbet/data/gtfs-last-modified}

# One reload at a time: a slow load must not have a second one started underneath it.
exec 9>"$STATE.lock"
flock -n 9 || { echo "a reload is already running"; exit 0; }

current=$(curl -fsSI --max-time 30 "$URL" | tr -d '\r' | awk -F': ' 'tolower($1) == "last-modified" { print $2 }')
[ -n "$current" ] || { echo "no Last-Modified from $URL" >&2; exit 1; }

previous=$(cat "$STATE" 2>/dev/null || true)
[ "$current" = "$previous" ] && exit 0

echo "timetable published ${current} (last loaded: ${previous:-never}); reloading"
# The loader renames the new file over this one; a hard link keeps the previous timetable for
# a manual rollback without copying 1.2 GB.
ln -f "$DB" "$DB.prev" 2>/dev/null || true

cd "$APP"
DATABASE_URL="$DB" npx tsx load-gtfs.ts
systemctl --user restart gtfsr-stop-times.service

# Recorded last, so a load that fails is retried on the next run. If the NTA publishes again
# between the HEAD above and the loader's download, this records the older date and the next
# run reloads once more: harmless.
printf '%s\n' "$current" > "$STATE"
echo "reloaded and restarted gtfsr-stop-times"
