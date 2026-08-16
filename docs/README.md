# Documentation

Everything that isn't a component's own `README.md` lives here or under a component's
`docs/` directory. This page is the index.

## Repository-wide

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the pieces fit together — services, hosts, data flow |
| [SEED.md](SEED.md) | Project seed: the original goals and constraints |
| [TICKETS.md](TICKETS.md) | Ticket conventions and workflow |
| [KUBERNETES-MIGRATION.md](KUBERNETES-MIGRATION.md) | Notes on a possible move to Kubernetes |

## Android app (`tfi-app/`)

| Document | What it covers |
|---|---|
| [PLAY_RELEASE.md](../tfi-app/docs/PLAY_RELEASE.md) | Building the AAB, Play App Signing, pushing the store listing, the declarations Play requires, and the closed-testing rules |
| [PRIVACY_POLICY.md](../tfi-app/docs/PRIVACY_POLICY.md) | The privacy policy source. Published to <https://iompar.mpts.ie/privacy-policy> by `scripts/gen-privacy-policy-html.py` — edit here, never the generated HTML |

## Web app (`app/`)

| Document | What it covers |
|---|---|
| [SPEC.md](../app/docs/SPEC.md) | Feature specification |
| [DEPLOYMENT.md](../app/docs/DEPLOYMENT.md) | Deployment procedure |

## Component READMEs

These stay next to the code they describe:

- [`tfi-app/README.md`](../tfi-app/README.md) — Android client
- [`tfi-tenant-api/README.md`](../tfi-tenant-api/README.md) — reports/tenant API
- [`gtfsr-historical-view/README.md`](../gtfsr-historical-view/README.md) — historical GTFS-R view
- [`bus-notifier/README.md`](../bus-notifier/README.md) — reference Java/Spring GTFS-R project
- [`bronto-pronto/README.md`](../bronto-pronto/README.md), [`bronto-proxy/README.md`](../bronto-proxy/README.md), [`hydroid/README.md`](../hydroid/README.md)

## Where things are built

The release AAB is **built by hand**, not in CI — see
[PLAY_RELEASE.md](../tfi-app/docs/PLAY_RELEASE.md). The GitHub Actions workflow
`.github/workflows/android-build.yml` assembles a *debug APK* as a compile check and is
deliberately not the release path: signing comes from a gitignored `local.properties`
that no runner has.
