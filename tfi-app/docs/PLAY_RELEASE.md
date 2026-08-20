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

> **Do not go looking for "Setup → API access".** Older guides (and the first version of
> this file) send you there. The grant now happens under **Users & permissions**, where
> the service account is invited like a person, by its email address. The API access page
> is also account-level and owner-only, so it is invisible from inside an app and to
> anyone who is not the account owner — which is usually why people cannot find it.

> **Signed into more than one Google account?** Append `&authuser=N` to every console URL
> below, matching the account you actually use. Without it the links silently open as
> your first-signed-in account and the project appears not to exist. The Play account and
> the Cloud project do not have to be the same login — the Users & permissions grant is
> what connects them.

1. **Google Cloud Console** → create or select a project → enable the **Google Play
   Developer API**. Google's current docs say a linked project is no longer required;
   third-party guides still say it is. Doing it costs nothing and works either way.
2. **IAM & Admin → Service Accounts** → create one. A name like `play-publisher` is
   enough. Assign **no GCP roles** — its permissions come from Play Console, not GCP.
3. On the service account, **Actions → Manage keys → Add key → Create new key → JSON**.
   The file downloads once and cannot be re-downloaded. Losing it means creating a new
   key, not recovering this one.
4. Copy the service account's **email address**. It looks like
   `play-publisher@<project>.iam.gserviceaccount.com`.
5. **Play Console → Users & permissions → Invite new user.** Paste that email. On the
   **App permissions** tab, add Iompar and tick exactly these (the labels are verbatim —
   the UI has no "manage releases" checkbox, whatever older guides say):
   - **View app information (read-only)** — `supply` reads current state before writing
   - **Manage store presence** — listing text and images; the one that matters for
     pushing metadata
   - **Release apps to testing tracks** — uploading AABs to internal/closed/open
   - **Manage testing tracks and edit tester lists** — managing the closed-test roster

   Leave **Release to production, exclude devices, and use Play App Signing** OFF.
   fastlane's docs suggest Admin for simplicity; don't. A leaked key that cannot reach
   production is a bad afternoon rather than a bad week, and promoting a release is one
   click in Console. Turn it on only if scripted production rollout is ever wanted.

   Leave Account permissions alone entirely.
6. Save the JSON outside the repo (for example `~/.config/play/iompar-publisher.json`)
   and `chmod 600` it. It is a live credential to your developer account; anyone holding
   it can publish as you.

`fastlane/` in this repo ignores `*.json` for exactly this reason — do not move the key
inside it and rely on remembering.

### What the API cannot do

Console-only, no API, no automation:

- **Creating the app**, and **rolling out its first release**. `supply` can upload a
  bundle to a track on a never-published app, but only as a draft. Asking for
  `--release_status completed` fails with *"Only releases with status draft may be
  created on draft app."* The first rollout is a Console button, and Console will not
  offer it until every item on **Finish setting up your app** is complete — so the
  declarations below gate the first release rather than running alongside it.
- **Content rating** questionnaire.
- **Data safety** form.
- App category, contact details, and the tester opt-in link.

Everything below assumes the app already exists in Console with at least one upload.

## 3. Push the listing

```
gem install --user-install fastlane          # once
cd tfi-app

fastlane supply \
  --json_key ~/.config/play/iompar-publisher.json \
  --package_name iompar.mpts.ie \
  --skip_upload_apk true --skip_upload_aab true \
  --track internal \
  --version_code 5
```

That pushes text and images only. To ship a build as well, drop the two `--skip_upload_*`
flags and add `--aab app/build/outputs/bundle/release/app-release.aab`.

**`--version_code` is required when skipping the binary upload.** Without it supply
uploads every image, then dies at the changelog with *"Cannot find changelog because no
version code given"* — after 30 seconds of work, and having committed nothing. It has to
match a versionCode that already exists on the track, and a `changelogs/<n>.txt` in the
metadata tree. `--skip_upload_changelogs true` does *not* avoid this; it still tries to
resolve a release and fails on production being empty.

### Reading failures

The upload steps and the commit need different permissions, so where it fails tells you
what is missing:

| Symptom | Cause |
|---|---|
| `has not been used in project …` | Play Developer API not enabled in the Cloud project |
| Uploads fine, `commit` → 403 | No **Manage store presence** on the App permissions tab |
| Commit → 403 only when a track is touched | No **Release apps to testing tracks** |
| Everything 403 | Service account not in Users & permissions at all |

Permission changes take a couple of minutes to propagate; an immediate retry can still
403 on a grant that is actually correct.

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

`docs/PRIVACY_POLICY.md` is the single source; the app links to the hosted page rather than
carrying a copy, so this is the only place the wording lives. Regenerate and redeploy
after editing it:

```
python3 scripts/gen-privacy-policy-html.py     # writes www/iompar/privacy-policy.html
scp www/iompar/privacy-policy.html gcp:srv/www/iompar/privacy-policy.html
curl -fsS https://iompar.mpts.ie/privacy-policy | head -5   # confirm it went live
```

Served at <https://iompar.mpts.ie/privacy-policy> by Caddy from `~/srv/www/iompar` on the
**gcp** host. No `sudo` step is needed: gcp runs everything rootless under the login user, so
the scp lands directly in place — unlike the retired hetzner box, where the files lived in
`/srv/www` and had to be staged through `/tmp` and copied as root.

The same page is also published at `/bus-dashboard-for-dublin/privacy-policy`, which is the
older URL already referenced by a live Play listing. Both are served from `~/srv/www`; if you
change one, check whether the other needs the same edit.

Two declarations must stay true to that text: the **Data safety** form, and the claim
that crash reporting is off. Sentry is currently initialised in **debug builds only**, so
release builds report nothing — if that changes, the policy and the Data safety form both
need updating in the same breath.
