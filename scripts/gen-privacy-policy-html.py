#!/usr/bin/env python3
"""
Generates the hosted privacy policy page from the app's copy of the text.

Google Play needs the policy at a public URL as well as inside the app. Keeping two hand-written
copies guarantees they drift, so the Kotlin is the single source and this renders the HTML from
it. Re-run after editing PRIVACY_POLICY_BODY, then redeploy www/ to the server.

    python3 scripts/gen-privacy-policy-html.py

Served at https://iompar.mpts.ie/privacy-policy
(Caddy file_server over /srv/www/iompar).
"""

import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "tfi-app/app/src/main/java/iompar/mpts/ie/PrivacyPolicy.kt"
OUT = ROOT / "www/iompar/privacy-policy.html"

CSS = """
  body{font:16px/1.6 system-ui,-apple-system,sans-serif;max-width:42rem;margin:2rem auto;padding:0 1rem;color:#1a1a1a}
  h1{font-size:1.5rem;margin-bottom:.25rem}
  h2{font-size:1rem;text-transform:uppercase;letter-spacing:.04em;margin-top:2rem;color:#444}
  .updated{color:#666;font-size:.875rem;margin-top:0}
  @media(prefers-color-scheme:dark){body{background:#111;color:#e8e8e8}h2{color:#aaa}.updated{color:#999}}
""".strip()


def main() -> int:
    src = SRC.read_text()

    try:
        body = src.split("val PRIVACY_POLICY_BODY = listOf(")[1].split(").joinToString")[0]
    except IndexError:
        print(f"error: could not find PRIVACY_POLICY_BODY in {SRC}", file=sys.stderr)
        return 1

    paragraphs = [
        p.replace('\\"', '"')
        for p in re.findall(r'^\s*"((?:[^"\\]|\\.)*)",\s*$', body, re.M)
    ]
    if not paragraphs:
        print("error: no paragraphs parsed — has the Kotlin format changed?", file=sys.stderr)
        return 1

    updated_match = re.search(r'PRIVACY_POLICY_LAST_UPDATED = "([^"]+)"', src)
    if updated_match is None:
        print("error: PRIVACY_POLICY_LAST_UPDATED not found", file=sys.stderr)
        return 1
    updated = updated_match.group(1)

    # Short all-caps paragraphs are the section headings.
    rendered = [
        f"<h2>{html.escape(p.title())}</h2>" if p.isupper() and len(p) < 60
        else f"<p>{html.escape(p)}</p>"
        for p in paragraphs
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        '<!doctype html>\n<html lang="en"><head>\n'
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width,initial-scale=1">\n'
        "<title>Privacy policy — Iompar</title>\n"
        f"<style>\n{CSS}\n</style>\n"
        "</head><body>\n<h1>Privacy policy</h1>\n"
        f'<p class="updated">Iompar · last updated {html.escape(updated)}</p>\n'
        + "\n".join(rendered)
        + "\n</body></html>\n"
    )
    print(f"{OUT.relative_to(ROOT)}: {len(paragraphs)} paragraphs, last updated {updated}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
