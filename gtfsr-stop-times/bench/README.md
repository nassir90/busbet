# Load harness

Replays a fixed mix of requests against a built server and reports the server process's CPU
time and peak RSS, against a synthetic timetable and snapshot set shaped like the NTA data.

```sh
npm run build
D=/tmp/busbet-bench
mkdir -p $D
python3 bench/gen-db.py $D/busbet.db                       # 10k stops, 30k trips, 1.2M stop_times
AT=$(( $(date +%s) / 60 * 60 )); echo $AT > $D/at.txt
node bench/gen-feeds.mjs $D $AT                            # 60 snapshots each of trip updates and vehicles
for i in 1 2 3 4 5; do node bench/bench.mjs $PWD $D --import $PWD/bench/graceful.mjs; done
```

Each run prints one JSON line: request count and statuses, bytes received, wall time, CPU
seconds (user + system from /proc) and peak RSS (VmHWM). Compare medians across runs.

To profile instead, add `--cpu-prof --cpu-prof-dir=/tmp/prof` after `graceful.mjs` (which
makes the server exit cleanly on SIGTERM so the profile is written).
