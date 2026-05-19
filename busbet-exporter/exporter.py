#!/usr/bin/env python3
"""
Prometheus exporter for the busbet GTFS-R archive on the lab host.

Exposes metrics about archived vehicle position + trip update feeds:
file counts, total bytes, time range, scrape latency.

Scrapes via SSH; caches results between requests to avoid hammering lab.
"""

import http.server
import json
import os
import re
import socketserver
import subprocess
import threading
import time
from dataclasses import dataclass, field

SSH_HOST = os.environ.get("BUSBET_SSH_HOST", "lab@lab")
ARCHIVE_ROOT = os.environ.get(
    "BUSBET_ARCHIVE_ROOT", "/home/lab/Projects/busbet/gtfsr-collector/data"
)
LISTEN_PORT = int(os.environ.get("BUSBET_EXPORTER_PORT", "9200"))
REFRESH_INTERVAL_SECONDS = int(os.environ.get("BUSBET_REFRESH_INTERVAL", "60"))

FILENAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}T[\d-]+Z)_(\d+)\.pb\.gz$")


@dataclass
class SubdirStats:
    files: int = 0
    bytes: int = 0
    first_feed_ts: int = 0
    last_feed_ts: int = 0


@dataclass
class Snapshot:
    collected_at: float = 0.0
    scrape_duration_seconds: float = 0.0
    ok: bool = False
    error: str = ""
    vehicles: SubdirStats = field(default_factory=SubdirStats)
    feeds: SubdirStats = field(default_factory=SubdirStats)


_cache_lock = threading.Lock()
_cache: Snapshot = Snapshot()


def scrape_lab() -> Snapshot:
    """SSH to lab and collect archive stats in one round trip."""
    start = time.time()
    snap = Snapshot(collected_at=start)

    # Use find to emit "<size> <name>" per file. Cheap even at 60k files.
    remote_cmd = (
        f"for sub in vehicles feeds; do "
        f"  dir={ARCHIVE_ROOT}/$sub; "
        f'  if [ -d "$dir" ]; then '
        f'    echo "BEGIN $sub"; '
        f'    find "$dir" -maxdepth 1 -type f -name "*.pb.gz" -printf "%s %f\\n"; '
        f'    echo "END $sub"; '
        f"  fi; "
        f"done"
    )

    try:
        result = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", SSH_HOST, remote_cmd],
            capture_output=True,
            text=True,
            timeout=60,
        )
    except subprocess.TimeoutExpired:
        snap.error = "ssh timeout"
        snap.scrape_duration_seconds = time.time() - start
        return snap
    except Exception as exc:
        snap.error = f"ssh failed: {exc}"
        snap.scrape_duration_seconds = time.time() - start
        return snap

    if result.returncode != 0:
        snap.error = f"ssh exit {result.returncode}: {result.stderr.strip()[:200]}"
        snap.scrape_duration_seconds = time.time() - start
        return snap

    current: SubdirStats | None = None
    for line in result.stdout.splitlines():
        if line.startswith("BEGIN "):
            name = line.split(" ", 1)[1]
            current = snap.vehicles if name == "vehicles" else snap.feeds
            continue
        if line.startswith("END "):
            current = None
            continue
        if current is None:
            continue
        try:
            size_str, fname = line.split(" ", 1)
            size = int(size_str)
        except ValueError:
            continue
        current.files += 1
        current.bytes += size
        m = FILENAME_RE.match(fname)
        if m:
            feed_ts = int(m.group(2))
            if current.first_feed_ts == 0 or feed_ts < current.first_feed_ts:
                current.first_feed_ts = feed_ts
            if feed_ts > current.last_feed_ts:
                current.last_feed_ts = feed_ts

    snap.ok = True
    snap.scrape_duration_seconds = time.time() - start
    return snap


def get_snapshot() -> Snapshot:
    with _cache_lock:
        return _cache


def refresh_loop():
    """Background worker — refreshes the cache every REFRESH_INTERVAL_SECONDS."""
    global _cache
    while True:
        fresh = scrape_lab()
        with _cache_lock:
            if not fresh.ok and _cache.ok:
                # Preserve last-good metrics but surface the latest error + duration
                _cache.error = fresh.error
                _cache.scrape_duration_seconds = fresh.scrape_duration_seconds
                _cache.collected_at = fresh.collected_at
            else:
                _cache = fresh
        time.sleep(REFRESH_INTERVAL_SECONDS)


def format_metrics(snap: Snapshot) -> str:
    lines: list[str] = []
    add = lines.append

    add("# HELP busbet_exporter_up 1 if last SSH scrape of lab succeeded")
    add("# TYPE busbet_exporter_up gauge")
    add(f"busbet_exporter_up {1 if snap.ok else 0}")

    add("# HELP busbet_exporter_scrape_duration_seconds Duration of the last SSH scrape")
    add("# TYPE busbet_exporter_scrape_duration_seconds gauge")
    add(f"busbet_exporter_scrape_duration_seconds {snap.scrape_duration_seconds:.4f}")

    add("# HELP busbet_archive_files Number of archived .pb.gz files")
    add("# TYPE busbet_archive_files gauge")
    add(f'busbet_archive_files{{kind="vehicles"}} {snap.vehicles.files}')
    add(f'busbet_archive_files{{kind="feeds"}} {snap.feeds.files}')

    add("# HELP busbet_archive_bytes Total bytes of archived .pb.gz files")
    add("# TYPE busbet_archive_bytes gauge")
    add(f'busbet_archive_bytes{{kind="vehicles"}} {snap.vehicles.bytes}')
    add(f'busbet_archive_bytes{{kind="feeds"}} {snap.feeds.bytes}')

    add("# HELP busbet_first_feed_timestamp_seconds Earliest feed timestamp in archive (unix seconds)")
    add("# TYPE busbet_first_feed_timestamp_seconds gauge")
    add(f'busbet_first_feed_timestamp_seconds{{kind="vehicles"}} {snap.vehicles.first_feed_ts}')
    add(f'busbet_first_feed_timestamp_seconds{{kind="feeds"}} {snap.feeds.first_feed_ts}')

    add("# HELP busbet_last_feed_timestamp_seconds Latest feed timestamp in archive (unix seconds)")
    add("# TYPE busbet_last_feed_timestamp_seconds gauge")
    add(f'busbet_last_feed_timestamp_seconds{{kind="vehicles"}} {snap.vehicles.last_feed_ts}')
    add(f'busbet_last_feed_timestamp_seconds{{kind="feeds"}} {snap.feeds.last_feed_ts}')

    vdur = max(0, snap.vehicles.last_feed_ts - snap.vehicles.first_feed_ts)
    fdur = max(0, snap.feeds.last_feed_ts - snap.feeds.first_feed_ts)
    add("# HELP busbet_archive_duration_seconds Span between earliest and latest feed in archive")
    add("# TYPE busbet_archive_duration_seconds gauge")
    add(f'busbet_archive_duration_seconds{{kind="vehicles"}} {vdur}')
    add(f'busbet_archive_duration_seconds{{kind="feeds"}} {fdur}')

    return "\n".join(lines) + "\n"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):  # noqa: A002 (match parent signature)
        pass  # silence default logging

    def do_GET(self):
        if self.path == "/metrics":
            snap = get_snapshot()
            body = format_metrics(snap).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/debug":
            snap = get_snapshot()
            body = json.dumps(
                {
                    "collected_at": snap.collected_at,
                    "scrape_duration_seconds": snap.scrape_duration_seconds,
                    "ok": snap.ok,
                    "error": snap.error,
                    "vehicles": snap.vehicles.__dict__,
                    "feeds": snap.feeds.__dict__,
                },
                indent=2,
            ).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)


class ThreadedServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def main():
    addr = ("127.0.0.1", LISTEN_PORT)
    srv = ThreadedServer(addr, Handler)
    print(
        f"busbet-exporter listening on http://{addr[0]}:{addr[1]}/metrics "
        f"(ssh={SSH_HOST}, root={ARCHIVE_ROOT}, refresh={REFRESH_INTERVAL_SECONDS}s)",
        flush=True,
    )
    worker = threading.Thread(target=refresh_loop, daemon=True)
    worker.start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
