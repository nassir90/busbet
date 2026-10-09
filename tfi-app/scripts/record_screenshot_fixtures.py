#!/usr/bin/env python3
"""Record the API responses the screenshot tests asked for and didn't have.

The tests log each unanswered call to app/build/screenshot-fixture-misses.txt as a key such as
`departures/7392`. This fetches each one from the backend at the fixture instant, using the
server's time-travel parameter where the endpoint has one, and writes it under
app/src/test/resources/screenshot-fixtures/ — `<key>.json`, or `<key>.404` when the server
answered 404, so the replay gives the same answer the app would have got.

    python3 scripts/record_screenshot_fixtures.py          # from tfi-app/
"""
import json, pathlib, re, sys, urllib.error, urllib.parse, urllib.request

BASE = "https://iompar.mpts.ie/gtfsr-stop-times/"
# Keep in step with FIXTURE_AT_SEC in FixtureGtfsApi.kt.
AT = 1_791_530_100
HERE = pathlib.Path(__file__).resolve().parent.parent
MISSES = HERE / "app/build/screenshot-fixture-misses.txt"
OUT = HERE / "app/src/test/resources/screenshot-fixtures"

# key pattern -> (path template, query params, whether the endpoint takes ?time=)
ROUTES = [
    (r"stops-search/(.+)", lambda m: ("stops", {"q": m[1]}, False)),
    (r"stops/(.+)", lambda m: (f"stops/{m[1]}", {}, False)),
    (r"departures-batch/(.+)", lambda m: ("departures", {"codes": m[1]}, True)),
    (r"departures/(.+)", lambda m: (f"departures/{m[1]}", {}, True)),
    (r"routes-search/(.+)", lambda m: ("routes", {"q": m[1]}, False)),
    (r"route-stops/(.+)/(\d+)", lambda m: ("route-stops", {"route": m[1], "direction": m[2]}, False)),
    (r"trips/(.+)", lambda m: (f"trips/{m[1]}", {}, True)),
    (r"vehicles-trip/(.+)", lambda m: (f"vehicles/trip/{m[1]}", {}, True)),
    (r"vehicles/(.+)", lambda m: (f"vehicles/{m[1]}", {}, True)),
    (r"shapes-trip/(.+)", lambda m: (f"shapes/trip/{m[1]}", {}, False)),
    (r"stop-routes/(.+)", lambda m: (f"stop-routes/{m[1]}", {}, False)),
]

def url_for(key):
    for pattern, build in ROUTES:
        m = re.fullmatch(pattern, key)
        if m:
            path, params, timed = build(m)
            if timed:
                params["time"] = AT
            quoted = "/".join(urllib.parse.quote(p, safe="") for p in path.split("/"))
            return BASE + quoted + ("?" + urllib.parse.urlencode(params) if params else "")
    return None

def main():
    keys = [k for k in (MISSES.read_text().split() if MISSES.exists() else []) if k]
    if not keys:
        print("nothing to record")
        return
    for key in keys:
        url = url_for(key)
        if url is None:
            print(f"skip   {key}  (no endpoint mapping)", file=sys.stderr)
            continue
        target = OUT / key
        target.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": "iompar-screenshot-fixtures"})
        try:
            body = json.load(urllib.request.urlopen(req, timeout=30))
            target.with_name(target.name + ".json").write_text(json.dumps(body, separators=(",", ":")))
            print(f"200    {key}")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
            target.with_name(target.name + ".404").write_text("")
            print(f"404    {key}")
    MISSES.unlink()

if __name__ == "__main__":
    main()
