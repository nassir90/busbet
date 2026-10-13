#!/usr/bin/env python3
"""
Builds the public site at https://iompar.mpts.ie/ : screenshots of the app and every APK we host.

    python3 scripts/gen-site.py --screens tfi-app/app/build/screenshots --artefacts <dir> [--out www/site]

--screens is the screenshot tests' output (light/ and dark/ PNGs, see AppScreenshots.kt).
--artefacts mirrors the server's ~/srv/www/artefacts: release/<version>.apk and debug/<version>.apk.
Sizes, dates and checksums come from those files, release notes from www/releases.json.

Then copy www/site/ to ~/srv/www/site/ on the host; Caddy serves it at the root and the APKs
under /artefacts/.
"""

import argparse
import datetime
import hashlib
import html
import json
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAY_TESTING = "https://play.google.com/apps/testing/iompar.mpts.ie"

# What the page shows, in order: screenshot name -> caption.
SCREENS = [
    # Sorted by distance (SiteScreenshots.kt): the manual-order layout still wraps long names.
    ("00-home-nearby", "Favourites with their next departures"),
    ("02-stop-aston-quay", "A stop's live board and map"),
    ("04-trip", "Where a bus is on its trip"),
    ("05-route", "Every stop on a route"),
    ("15-journey-options", "Journey options (1.16 preview)"),
    ("16-journey-itinerary", "A journey on the map (1.16 preview)"),
    ("06-settings", "Themes and settings"),
    ("10-feedback", "Feedback from inside the app"),
]


def version_key(v: str):
    return tuple(int(x) for x in v.split("."))


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def export_screens(screens: pathlib.Path, out: pathlib.Path) -> list:
    shots = []
    (out / "screens").mkdir(parents=True, exist_ok=True)
    for name, caption in SCREENS:
        variants = {}
        for theme in ("light", "dark"):
            src = screens / theme / f"{name}.png"
            if not src.exists():
                continue
            im = Image.open(src).convert("RGB")
            im = im.resize((480, round(im.height * 480 / im.width)), Image.Resampling.LANCZOS)
            dest = out / "screens" / f"{name}-{theme}.webp"
            im.save(dest, "WEBP", quality=84)
            variants[theme] = dest.name
        if "light" in variants:
            shots.append((name, caption, variants))
        else:
            print(f"warning: no light render of {name}, skipped", file=sys.stderr)
    return shots


def builds(artefacts: pathlib.Path) -> list:
    meta = {r["version"]: r for r in json.loads((ROOT / "www/releases.json").read_text())}
    rows = []
    for kind in ("release", "debug"):
        for apk in (artefacts / kind).glob("*.apk"):
            version = apk.stem
            st = apk.stat()
            rows.append({
                "version": version,
                "kind": kind,
                "size": st.st_size,
                "date": datetime.date.fromtimestamp(st.st_mtime),
                "sha256": sha256(apk),
                "href": f"/artefacts/{kind}/{apk.name}",
                "notes": meta.get(version, {}).get("notes", ""),
                # A preview is listed but not offered as the download.
                "preview": meta.get(version, {}).get("preview", False),
            })
    rows.sort(key=lambda r: (version_key(r["version"]), r["kind"] == "release"), reverse=True)
    return rows


CSS = """
:root{--bg:#f4f6f5;--fg:#17211f;--mut:#56645f;--card:#fff;--line:#d6dedb;--acc:#3d5663;--onacc:#fff;--soft:#e3eaee;--tag:#e7efe9;--tagfg:#2c5a40;--dbg:#f5ecdc;--dbgfg:#7a5410}
@media (prefers-color-scheme:dark){:root{--bg:#111716;--fg:#e3eae7;--mut:#9aa8a3;--card:#19211f;--line:#2b3633;--acc:#a9c3d1;--onacc:#10202a;--soft:#22303a;--tag:#1f3328;--tagfg:#9fd3b0;--dbg:#3a2e17;--dbgfg:#e8c27a;color-scheme:dark}}
*{box-sizing:border-box}
html{background:var(--bg)}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 "IBM Plex Sans",system-ui,sans-serif}
a{color:var(--acc)}
.wrap{max-width:1080px;margin:0 auto;padding-inline:16px}
header{padding-block:48px 28px}
h1{font-size:2.4rem;line-height:1.1;margin:0 0 8px;letter-spacing:-.01em}
.lede{font-size:1.15rem;color:var(--mut);margin:0 0 24px;max-width:60ch}
.cta{display:flex;flex-wrap:wrap;gap:12px}
.btn{display:inline-block;padding:11px 18px;border-radius:8px;text-decoration:none;font-weight:600}
.btn.primary{background:var(--acc);color:var(--onacc)}
.btn.secondary{background:var(--soft);color:var(--fg)}
h2{font-size:1.3rem;margin:48px 0 16px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(42%,210px),1fr));gap:20px}
figure{margin:0;min-width:0}
figure img{width:100%;height:auto;display:block;border-radius:14px;border:1px solid var(--line);background:var(--card)}
figcaption{font-size:.9rem;color:var(--mut);margin-top:8px}
.tbl{overflow-x:auto;background:var(--card);border:1px solid var(--line);border-radius:12px}
table{border-collapse:collapse;width:100%;font-size:.92rem}
th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--line);vertical-align:top}
th{font-size:.78rem;letter-spacing:.04em;text-transform:uppercase;color:var(--mut);font-weight:600}
tr:last-child td{border-bottom:0}
td.n{font-variant-numeric:tabular-nums;white-space:nowrap}
.tag{display:inline-block;font-size:.75rem;font-weight:600;padding:1px 8px;border-radius:99px;background:var(--tag);color:var(--tagfg)}
.tag.debug{background:var(--dbg);color:var(--dbgfg)}
code{font-family:"IBM Plex Mono",ui-monospace,monospace;font-size:.8rem;word-break:break-all}
.notes{color:var(--mut);max-width:46ch}
.fine{color:var(--mut);font-size:.9rem;max-width:72ch}
footer{margin-block:56px 40px;color:var(--mut);font-size:.88rem;display:flex;flex-wrap:wrap;gap:8px 24px}
"""


def render(shots: list, rows: list) -> str:
    fig = []
    for _name, caption, v in shots:
        dark = f'<source srcset="screens/{v["dark"]}" media="(prefers-color-scheme: dark)">' if "dark" in v else ""
        fig.append(
            f'<figure><picture>{dark}<img src="screens/{v["light"]}" width="480" height="1040" '
            f'loading="lazy" alt="{html.escape(caption)}"></picture><figcaption>{html.escape(caption)}</figcaption></figure>'
        )
    trs = []
    for r in rows:
        mb = f'{r["size"] / 1_000_000:.0f} MB'
        tag = '<span class="tag">release</span>' if r["kind"] == "release" else '<span class="tag debug">debug</span>'
        if r["preview"]:
            tag += ' <span class="tag debug">preview</span>'
        trs.append(
            f'<tr><td class="n"><a href="{r["href"]}">Iompar {html.escape(r["version"])}</a></td><td>{tag}</td>'
            f'<td class="n">{mb}</td><td class="n">{r["date"].isoformat()}</td>'
            f'<td class="notes">{html.escape(r["notes"]) if r["kind"] == "release" else ""}</td>'
            f'<td><code title="SHA-256">{r["sha256"]}</code></td></tr>'
        )
    latest = next((r for r in rows if r["kind"] == "release" and not r["preview"]), None)
    latest_btn = f'<a class="btn secondary" href="{latest["href"]}">Download Iompar {html.escape(latest["version"])}</a>' if latest else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Iompar</title>
<meta name="description" content="Live Dublin bus, Luas and rail departures for Android.">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Mono&display=swap" rel="stylesheet">
<style>{CSS}</style>
</head>
<body>
<div class="wrap">
<header>
<h1>Iompar</h1>
<p class="lede">Live Dublin bus, Luas and rail departures for Android, built to replace the TFI Live app.</p>
<div class="cta">
<a class="btn primary" href="{PLAY_TESTING}">Become a tester on Google Play</a>
{latest_btn}
</div>
</header>
<main>
<h2>Screenshots</h2>
<div class="grid">
{''.join(fig)}
</div>
<h2 id="downloads">Downloads</h2>
<div class="tbl"><table>
<tr><th>Build</th><th>Kind</th><th>Size</th><th>Date</th><th>Changes</th><th>SHA-256</th></tr>
{''.join(trs)}
</table></div>
<p class="fine">Google Play is the recommended way to install. Play, these release downloads and the debug downloads are each signed differently, so one can't be installed over another: uninstall first to switch. Debug builds are for testing.</p>
</main>
<footer>
<a href="/privacy-policy">Privacy policy</a>
<span>Timetables and live data: National Transport Authority</span>
<span>Maps © OpenStreetMap contributors</span>
</footer>
</div>
</body>
</html>
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--screens", required=True, type=pathlib.Path)
    ap.add_argument("--artefacts", required=True, type=pathlib.Path)
    ap.add_argument("--out", type=pathlib.Path, default=ROOT / "www/site")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    shots = export_screens(a.screens, a.out)
    rows = builds(a.artefacts)
    (a.out / "index.html").write_text(render(shots, rows))
    print(f"{a.out / 'index.html'}: {len(shots)} screenshots, {len(rows)} builds")
    return 0


if __name__ == "__main__":
    sys.exit(main())
