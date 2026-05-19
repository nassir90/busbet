import { createServer, IncomingMessage, ServerResponse } from "node:http";
import { readFileSync, readdirSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { join } from "node:path";
import GtfsRealtimeBindings from "gtfs-realtime-bindings";

const { FeedMessage } = (GtfsRealtimeBindings as any).transit_realtime;

// ---------------------------------------------------------------------------
// Index archived feeds
// ---------------------------------------------------------------------------

interface FeedIndex {
  timestamps: number[]; // sorted ascending
  files: Map<number, string>; // timestamp → full path
}

function indexDir(dir: string): FeedIndex {
  const files = new Map<number, string>();
  let entries: string[];
  try {
    entries = readdirSync(dir).filter((f) => f.endsWith(".pb.gz"));
  } catch {
    entries = [];
  }
  for (const name of entries) {
    const m = name.match(/_(\d+)\.pb\.gz$/);
    if (!m) continue;
    files.set(Number(m[1]), join(dir, name));
  }
  const timestamps = [...files.keys()].sort((a, b) => a - b);
  return { timestamps, files };
}

const DATA = join(process.cwd(), "data");
const feeds = indexDir(join(DATA, "feeds"));
const vehicles = indexDir(join(DATA, "vehicles"));

// ---------------------------------------------------------------------------
// Virtual clock
// ---------------------------------------------------------------------------

let anchorRealTime = Date.now();
let anchorVirtualTime =
  Math.min(
    feeds.timestamps[0] ?? Infinity,
    vehicles.timestamps[0] ?? Infinity
  ) || 0;
let speed = 0; // paused

function virtualNow(): number {
  return anchorVirtualTime + ((Date.now() - anchorRealTime) * speed) / 1000;
}

function setClock(opts: { time?: number; speed?: number }) {
  const now = virtualNow();
  anchorRealTime = Date.now();
  anchorVirtualTime = opts.time ?? now;
  if (opts.speed !== undefined) speed = opts.speed;
}

// ---------------------------------------------------------------------------
// Binary search: largest timestamp <= target
// ---------------------------------------------------------------------------

function lookupFile(index: FeedIndex, t: number): string | null {
  const { timestamps, files } = index;
  if (timestamps.length === 0) return null;
  let lo = 0;
  let hi = timestamps.length - 1;
  if (t < timestamps[0]) return null;
  while (lo < hi) {
    const mid = (lo + hi + 1) >>> 1;
    if (timestamps[mid] <= t) lo = mid;
    else hi = mid - 1;
  }
  return files.get(timestamps[lo]) ?? null;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function cors(res: ServerResponse) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
}

function json(res: ServerResponse, data: unknown, status = 200) {
  cors(res);
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(data));
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let body = "";
    req.on("data", (c: Buffer) => (body += c.toString()));
    req.on("end", () => resolve(body));
  });
}

function serveFeed(
  res: ServerResponse,
  index: FeedIndex,
  asJson: boolean
) {
  const path = lookupFile(index, virtualNow());
  if (!path) return json(res, { error: "no feed available" }, 404);
  const raw = gunzipSync(readFileSync(path));
  if (asJson) {
    const msg = FeedMessage.decode(raw);
    return json(res, FeedMessage.toObject(msg, { longs: Number }));
  }
  cors(res);
  res.writeHead(200, { "Content-Type": "application/x-protobuf" });
  res.end(raw);
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host}`);
  const path = url.pathname;
  const method = req.method ?? "GET";

  cors(res);
  if (method === "OPTIONS") {
    res.writeHead(204);
    return res.end();
  }

  // Control
  if (path === "/api/control/time" && method === "GET") {
    return json(res, {
      virtualTime: virtualNow(),
      speed,
      paused: speed === 0,
    });
  }

  if (path === "/api/control/time" && method === "POST") {
    const body = JSON.parse(await readBody(req));
    setClock(body);
    return json(res, {
      virtualTime: virtualNow(),
      speed,
      paused: speed === 0,
    });
  }

  if (path === "/api/control/range" && method === "GET") {
    const all = [...feeds.timestamps, ...vehicles.timestamps].sort(
      (a, b) => a - b
    );
    return json(res, {
      earliest: all[0] ?? 0,
      latest: all[all.length - 1] ?? 0,
      feedCount: feeds.timestamps.length,
      vehicleCount: vehicles.timestamps.length,
    });
  }

  // Data
  if (path === "/api/feed/trip-updates" && method === "GET")
    return serveFeed(res, feeds, false);
  if (path === "/api/feed/trip-updates/json" && method === "GET")
    return serveFeed(res, feeds, true);
  if (path === "/api/feed/vehicles" && method === "GET")
    return serveFeed(res, vehicles, false);
  if (path === "/api/feed/vehicles/json" && method === "GET")
    return serveFeed(res, vehicles, true);

  json(res, { error: "not found" }, 404);
});

const PORT = Number(process.env.PLAYBACK_PORT) || 3456;
server.listen(PORT, () => {
  console.log(`playback server listening on :${PORT}`);
  console.log(
    `indexed ${feeds.timestamps.length} feeds, ${vehicles.timestamps.length} vehicle snapshots`
  );
  if (feeds.timestamps.length || vehicles.timestamps.length) {
    const t = anchorVirtualTime;
    console.log(
      `virtual clock paused at ${new Date(t * 1000).toISOString()} (${t})`
    );
  }
});
