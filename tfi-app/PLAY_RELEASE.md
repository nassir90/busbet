# Releasing Iompar to Google Play

How to build the artifact, and how to push the store listing from this repo instead of
typing it into a web form.

Listing text and images live in `fastlane/metadata/android/en-GB/`. They are version
controlled, so a listing change is a diff and a commit like anything else.

## 1. Build the bundle

Play takes an **App Bundle** (`.aab`), not an APK. New apps have not been able to ship
APKs since August 2021.

```
cd tfi-app
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Or use the **Build AAB** button in `scripts/icon-tool`, which also reveals the file and
warns if it came out unsigned.

Signing is read from `local.properties` (gitignored):

```
release.storeFile=release.jks
release.storePassword=...
release.keyAlias=iompar
release.keyPassword=...
```

If those are absent the release build still succeeds but is **unsigned**, deliberately —
falling back to the debug key produces an artifact Play silently refuses at upload.

### Play App Signing

Play holds the *app signing* key and re-signs every release. `release.jks` is the
**upload key** — still required on every upload, but a lost upload key can be reset by
Google, unlike an app signing key. Back it up anyway; there is currently no off-machine
copy.

## 2. Produce the API credentials

This is a **service account**, not an API key. Play's API authenticates as a robot
account you grant access to, and the credential is a JSON key file.

1. **Play Console → Setup → API access.**
2. **Link a Google Cloud project.** Create one if you have none. The project only exists
   to own the service account; nothing is billed.
3. In that project (**Google Cloud Console → IAM & Admin → Service Accounts**), create a
   service account. A name like `play-publisher` is enough. It needs no project-level
   IAM roles — its permissions come from Play Console, not GCP.
4. On the service account, **Keys → Add key → Create new key → JSON**. The file
   downloads once and cannot be re-downloaded. Losing it means creating a new key.
5. Back in **Play Console → API access**, the account now appears. **Grant access**, and
   give it app permissions. For pushing listings and releases:
   - View app information
   - Manage store presence
   - Manage production/testing releases
6. Save the JSON outside the repo (for example `~/.config/play/iompar-publisher.json`)
   and `chmod 600` it. It is a live credential to your developer account; anyone holding
   it can publish as you.

`fastlane/` in this repo ignores `*.json` for exactly this reason — do not move the key
inside it and rely on remembering.

### What the API cannot do

Console-only, no API, no automation:

- **Creating the app** and **uploading its first release** — the API cannot bootstrap a
  new app.
- **Content rating** questionnaire.
- **Data safety** form.
- App category, contact details, and the tester opt-in link.

Everything below assumes the app already exists in Console with at least one upload.

## 3. Push the listing

```
gem install fastlane          # once
cd tfi-app

fastlane supply \
  --json_key ~/.config/play/iompar-publisher.json \
  --package_name iompar.mpts.ie \
  --skip_upload_apk --skip_upload_aab \
  --track internal
```

That pushes text and images only. To ship a build as well, drop the two `--skip_upload_*`
flags and add `--aab app/build/outputs/bundle/release/app-release.aab`.

> **Do not run `fastlane supply init`.** It *downloads* the live listing and overwrites
> everything in `fastlane/metadata/`, discarding the copy in this repo. It is for
> importing a listing you have only ever edited in the browser. Once the repo is the
> source of truth, `init` runs the wrong way.

Validate before pushing with `--validate_only true`.

## 4. Asset rules that actually bite

Learned the hard way; all of these are silent rejections rather than helpful errors.

| Asset | Requirement | Note |
|---|---|---|
| Screenshots | **PNG or JPEG**, 320–3840 px, min 2 | **WebP is rejected.** The repo's screenshots are WebP and are converted on the way in. |
| Icon | 512×512, 32-bit PNG | The launcher icon in `res/mipmap-xxxhdpi` is only 192×192, so the listing copy is upscaled. Regenerate from a real 512+ source when one exists. |
| Feature graphic | 1024×500, PNG or JPEG | Required. No transparency. |
| Title | 30 characters | |
| Short description | 80 characters | |
| Full description | 4000 characters | |
| Changelog | 500 characters | `changelogs/<versionCode>.txt`, e.g. `changelogs/5.txt` |

Regenerate the screenshots after a UI change:

```
for f in ../static/images/busbet/v2/*.webp; do
  magick "$f" "fastlane/metadata/android/en-GB/images/phoneScreenshots/$(...)_en-GB.png"
done
```

## 5. Versioning

`versionCode` is bumped **once per ticket implemented**, not once per release — Sentry
groups events by release, so a version that only moves at release time buckets weeks of
unrelated builds together. Play additionally refuses any upload whose `versionCode` is
not strictly greater than the last one on that track.

Add a `changelogs/<versionCode>.txt` for each bump you actually ship.

## 6. The closed-testing requirement

Personal (non-organisation) developer accounts must run a closed test with **12 testers
opted in, continuously, for 14 days** before applying for production.

- 12 *distinct Google accounts*, each opted in through the test link. Not 12 installs,
  and not you.
- If someone opts out, the count breaks and the clock is affected.
- The 14 days start when the closed test goes live, so start recruiting before the
  listing is finished. It is the long pole; nothing else on this page shortens it.

## 7. Privacy policy

Play requires a publicly reachable policy URL, and the app links to the same text
internally.

`PrivacyPolicy.kt` is the single source. Regenerate and redeploy the hosted copy after
editing it:

```
python3 scripts/gen-privacy-policy-html.py     # writes www/iompar/privacy-policy.html
scp www/iompar/privacy-policy.html hetzner:/tmp/pp.html
ssh hetzner 'cp /tmp/pp.html /srv/www/iompar/privacy-policy.html'
```

Served at <https://iompar.mpts.ie/privacy-policy> by Caddy from `/srv/www/iompar`.

Two declarations must stay true to that text: the **Data safety** form, and the claim
that crash reporting is off. Sentry is currently initialised in **debug builds only**, so
release builds report nothing — if that changes, the policy and the Data safety form both
need updating in the same breath.
