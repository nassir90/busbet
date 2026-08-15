#!/usr/bin/env python3
"""
Generates the hosted privacy policy page from tfi-app/PRIVACY_POLICY.md.

The app used to carry the policy text as a Kotlin constant and this script parsed it back out.
That inverted once the app dropped its in-app copy and started linking to the hosted page: the
markdown is now the only place the wording lives, and this renders it. Re-run after editing it,
then redeploy www/ to the server.

    python3 scripts/gen-privacy-policy-html.py

Served at https://iompar.mpts.ie/privacy-policy
(Caddy file_server over /srv/www/iompar).
"""

import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "tfi-app/PRIVACY_POLICY.md"
OUT = ROOT / "www/iompar/privacy-policy.html"

CSS = """
  body{font:16px/1.6 system-ui,-apple-system,sans-serif;max-width:42rem;margin:2rem auto;padding:0 1rem;color:#1a1a1a}
  h1{font-size:1.5rem;margin-bottom:.25rem}
  h2{font-size:1rem;text-transform:uppercase;letter-spacing:.04em;margin-top:2rem;color:#444}
  .updated{color:#666;font-size:.875rem;margin-top:0}
  @media(prefers-color-scheme:dark){body{background:#111;color:#e8e8e8}h2{color:#aaa}.updated{color:#999}}
""".strip()


def main() -> int:
    if not SRC.exists():
        print(f"error: {SRC} not found", file=sys.stderr)
        return 1
    src = SRC.read_text()

    # Drop the HTML comment block holding the maintenance notes — it is for whoever edits the
    # markdown, not for readers of the published page.
    src = re.sub(r"<!--.*?-->", "", src, flags=re.S)

    updated_match = re.search(r"^Updated:\s*(.+)$", src, re.M)
    if updated_match is None:
        print("error: no 'Updated: <date>' line found", file=sys.stderr)
        return 1
    updated = updated_match.group(1).strip()
    src = src.replace(updated_match.group(0), "", 1)

    rendered = []
    for block in (b.strip() for b in src.split("\n\n")):
        if not block:
            continue
        if block.startswith("## "):
            rendered.append(f"<h2>{html.escape(block[3:].strip())}</h2>")
        else:
            # Paragraphs are single logical lines; unwrap any that got hard-wrapped.
            rendered.append(f"<p>{html.escape(' '.join(block.split()))}</p>")

    if not rendered:
        print("error: no content parsed", file=sys.stderr)
        return 1

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
    print(f"{OUT.relative_to(ROOT)}: {len(rendered)} blocks, last updated {updated}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
