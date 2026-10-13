#!/usr/bin/env python3
"""
Builds the public site at https://iompar.mpts.ie/ : screenshots of the app and every APK we host.

    python3 scripts/gen-site.py --screens tfi-app/app/build/screenshots/site --artefacts <dir> [--out www/site]

--screens is SiteScreenshots.kt's output (tfi-app/app/build/screenshots/site).
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

# What the page shows, in order: screenshot name (SiteScreenshots.kt) -> caption.
SCREENS = [
    ("01-home", "Favourites, nearest first"),
    ("02-stop", "Live departures and buses on the map"),
    ("03-trip", "Every stop on a trip, with live times"),
    ("04-route", "A route in both directions"),
    ("05-journey-options", "Journey planning (1.16 preview)"),
    ("06-journey-itinerary", "A journey on the map (1.16 preview)"),
    ("07-settings", "Themes and settings"),
    ("08-feedback", "Feedback from inside the app"),
]

GREEN = "#3D5663"  # the Green palette's primary (Theme.kt, preset-green)


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
        src = screens / f"{name}.png"
        if not src.exists():
            print(f"warning: no render of {name}, skipped", file=sys.stderr)
            continue
        im = Image.open(src).convert("RGB")
        im = im.resize((480, round(im.height * 480 / im.width)), Image.Resampling.LANCZOS)
        dest = out / "screens" / f"{name}.webp"
        im.save(dest, "WEBP", quality=86)
        shots.append((dest.name, caption, im.height))
    return shots


def builds(artefacts: pathlib.Path) -> list:
    """One entry per version, with its release and debug APKs side by side."""
    meta = {r["version"]: r for r in json.loads((ROOT / "www/releases.json").read_text())}
    versions = {}
    for kind in ("release", "debug"):
        for apk in (artefacts / kind).glob("*.apk"):
            st = apk.stat()
            v = versions.setdefault(apk.stem, {
                "version": apk.stem,
                "notes": meta.get(apk.stem, {}).get("notes", ""),
                # A preview is listed but not offered as the download at the top.
                "preview": meta.get(apk.stem, {}).get("preview", False),
                "date": datetime.date.fromtimestamp(st.st_mtime),
            })
            v[kind] = {"href": f"/artefacts/{kind}/{apk.name}", "size": st.st_size, "sha256": sha256(apk), "name": f"{kind}/{apk.name}"}
            v["date"] = min(v["date"], datetime.date.fromtimestamp(st.st_mtime))
    return sorted(versions.values(), key=lambda v: version_key(v["version"]), reverse=True)


CSS = """
*{box-sizing:border-box}
html,body{background:#fff}
body{margin:0;color:#1c1b1f;font:16px/1.5 Roboto,system-ui,-apple-system,"Segoe UI",sans-serif}
a{color:#3D5663}
.wrap{max-width:1040px;margin:0 auto;padding-inline:20px}
.banner{background:#3D5663;color:#fff}
.banner .wrap{padding-block:28px}
.banner h1{margin:0;font-size:2.25rem;font-weight:500;letter-spacing:.01em}
.intro{padding-block:28px 8px}
.intro p{margin:0 0 20px;font-size:1.125rem;color:#49454f;max-width:56ch}
.actions{display:flex;flex-wrap:wrap;gap:12px}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:40px;padding:0 24px;border-radius:20px;
  font-weight:500;font-size:.9375rem;text-decoration:none;border:1px solid #3D5663;letter-spacing:.01em}
.btn.filled{background:#3D5663;color:#fff}
.btn.outlined{background:#fff;color:#3D5663}
.btn.small{min-height:32px;padding:0 16px;border-radius:16px;font-size:.875rem}
h2{font-size:1.375rem;font-weight:500;color:#3D5663;margin:48px 0 20px}
.shots{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:28px 20px}
@media (max-width:820px){.shots{grid-template-columns:repeat(2,minmax(0,1fr))}}
.shot img{width:100%;height:auto;display:block;border-radius:16px;box-shadow:0 1px 3px rgba(0,0,0,.12),0 4px 16px rgba(0,0,0,.08)}
.shot p{margin:10px 2px 0;font-size:.875rem;color:#49454f}
.versions{width:100%;border-collapse:collapse}
.versions th{text-align:left;font-weight:500;color:#3D5663;padding:0 16px 10px 0;border-bottom:2px solid #3D5663}
.versions td{padding:16px 16px 16px 0;border-bottom:1px solid #e0e3e2;vertical-align:top}
.versions td.v{white-space:nowrap;font-weight:500}
.versions td.d{white-space:nowrap;color:#49454f;font-variant-numeric:tabular-nums}
.versions td.dl{white-space:nowrap}
.versions td.dl .btn+.btn{margin-left:8px}
.preview{font-weight:400;color:#49454f}
.size{display:block;margin-top:6px;font-size:.8125rem;color:#49454f}
.scroll{overflow-x:auto}
.note{color:#49454f;font-size:.9375rem;max-width:70ch;margin:20px 0 0}
details{margin-top:20px;color:#49454f;font-size:.875rem}
summary{cursor:pointer;color:#3D5663}
details code{display:block;font:12px/1.7 "Roboto Mono",ui-monospace,monospace;word-break:break-all}
footer{margin-block:56px 40px;font-size:.875rem}
"""


def render(shots: list, versions: list) -> str:
    fig = "".join(
        f'<div class="shot"><img src="screens/{f}" width="480" height="{h}" loading="lazy" alt="{html.escape(c)}">'
        f'<p>{html.escape(c)}</p></div>'
        for f, c, h in shots
    )
    rows = []
    for v in versions:
        links, sizes = [], []
        for kind, cls in (("release", "filled"), ("debug", "outlined")):
            if kind in v:
                links.append(f'<a class="btn small {cls}" href="{v[kind]["href"]}">{kind.capitalize()}</a>')
                sizes.append(f'{kind} {v[kind]["size"] / 1_000_000:.0f} MB')
        preview = ' <span class="preview">· preview</span>' if v["preview"] else ""
        rows.append(
            f'<tr><td class="v">{html.escape(v["version"])}{preview}</td>'
            f'<td class="d">{v["date"].strftime("%-d %b %Y")}</td>'
            f'<td>{html.escape(v["notes"])}</td>'
            f'<td class="dl">{"".join(links)}<span class="size">{" · ".join(sizes)}</span></td></tr>'
        )
    sums = "".join(
        f'<code>{v[k]["sha256"]}  {v[k]["name"]}</code>' for v in versions for k in ("release", "debug") if k in v
    )
    latest = next((v for v in versions if "release" in v and not v["preview"]), None)
    latest_btn = f'<a class="btn outlined" href="{latest["release"]["href"]}">Download Iompar {html.escape(latest["version"])}</a>' if latest else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Iompar</title>
<meta name="description" content="Live Dublin bus, Luas and rail departures for Android.">
<meta name="theme-color" content="{GREEN}">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Roboto:wght@400;500&family=Roboto+Mono&display=swap" rel="stylesheet">
<style>{CSS}</style>
</head>
<body>
<header class="banner"><div class="wrap"><h1>Iompar</h1></div></header>
<div class="wrap">
<section class="intro">
<p>Live Dublin bus, Luas and rail departures for Android, built to replace the TFI Live app.</p>
<div class="actions">
<a class="btn filled" href="{PLAY_TESTING}">Become a tester on Google Play</a>
{latest_btn}
</div>
</section>
<h2>Screenshots</h2>
<div class="shots">{fig}</div>
<h2 id="downloads">Downloads</h2>
<div class="scroll"><table class="versions">
<tr><th>Version</th><th>Date</th><th>Changes</th><th>Download</th></tr>
{"".join(rows)}
</table></div>
<p class="note">Installing from Google Play is recommended. Play, release and debug builds are each signed differently, so one can't be installed over another: uninstall first to switch.</p>
<details><summary>SHA-256 checksums</summary>{sums}</details>
<footer><a href="/privacy-policy">Privacy policy</a></footer>
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
    versions = builds(a.artefacts)
    (a.out / "index.html").write_text(render(shots, versions))
    print(f"{a.out / 'index.html'}: {len(shots)} screenshots, {len(versions)} versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
