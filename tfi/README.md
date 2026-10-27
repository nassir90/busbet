# tfi — the Iompar web app

The web version of the Android app (`tfi-app/`), served at https://iompar.mpts.ie/.
Preact + Vite in `src/`, built to `dist/`; `server.js` serves `dist/` and proxies `api/*` to
gtfsr-stop-times. The server is plain node with no packages, so the box needs no `node_modules`.

Every URL the page uses is relative (`api/…`, `assets/…`, `?stop=315`), so it works at `/` or
behind any path prefix.

| URL | View |
|---|---|
| `/` | Favourites (one batch call for every card), search, recent searches |
| `?stop=315` | Stop board: departures, route filter, map with buses, bus list |
| `?route=39A&dir=1` | Both directions of a route, as tabs |
| `?trip=<id>&from=315` | One trip's stops with live times, its shape and its bus on a map |
| `?settings` | Nearest-first, maps, colours, editing favourites, history |

The old page's `?code=` and `?route=&direction=` URLs still work.

## Develop

```bash
npm install
npm run dev        # Vite on :5173, api/* proxied to the live read-only API
```

Or the production server against the live API:

```bash
npm run build
TFI_PORT=8188 GTFSR_STOP_TIMES_URL=https://iompar.mpts.ie/gtfsr-stop-times node server.js
```

Set `localStorage['tfi-perf']` to log each navigation's click-to-data time; they are also kept
on `window.__tfiNav`.

## Deploy (GCP box, systemd user units)

Build locally and ship only `server.js`, `dist/` and the unit; nothing is installed on the box.

```bash
npm run build
COPYFILE_DISABLE=1 tar --no-xattrs -czf /tmp/tfi.tgz server.js package.json tfi.service dist
# The box isn't named in the repo: set these from your own notes.
gcloud compute ssh "$TFI_GCE_TARGET" \
  --project "$TFI_GCE_PROJECT" --zone "$TFI_GCE_ZONE" \
  --command 'cd ~/Projects/busbet && rm -rf tfi/dist && tar -xzf - -C tfi &&
             cp tfi/tfi.service ~/.config/systemd/user/ && systemctl --user daemon-reload &&
             systemctl --user restart tfi && curl -sf http://127.0.0.1:8108/health' < /tmp/tfi.tgz
```

Routing lives in `deploy/Caddyfile` (copy of `~/.config/caddy/Caddyfile`). Caddy there runs with
`admin off`, so `caddy reload` can't apply a change; validate, install and restart instead (about a
second of downtime for every path):

```bash
~/.local/bin/caddy validate --config ~/Caddyfile.new --adapter caddyfile
cp ~/Caddyfile.new ~/.config/caddy/Caddyfile && systemctl --user restart caddy
```

Rollback (first deployed 27 Oct 2026): `~/Projects/busbet/tfi.bak-20261027`,
`~/tfi.service.bak-20261027`, `~/.config/caddy/Caddyfile.bak-20261027`.

Memory: about 15 MB idle and 40 MB peak under a burst of 1,700 requests. The unit caps it at
`MemoryHigh=96M` and `MemoryMax=160M`, because the box has no swap.
