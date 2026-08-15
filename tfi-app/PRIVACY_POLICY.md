<!--
Source of truth for the Iompar privacy policy.

The app no longer carries a copy of this text; it links to the hosted page instead, so this file
is the only place the wording lives. Edit here, then regenerate and redeploy:

    python3 scripts/gen-privacy-policy-html.py
    scp www/iompar/privacy-policy.html hetzner:/tmp/pp.html
    ssh hetzner 'cp /tmp/pp.html /srv/www/iompar/privacy-policy.html'

Served at https://iompar.mpts.ie/privacy-policy

Written against what the app actually does, verified in the source rather than assumed. If any of
the following changes, this text and the Play Data safety declaration both need updating:
  - location leaving the device (today it never does)
  - anything identifying being attached to a report
  - crash reporting being enabled in release builds
  - the map tile provider
-->

Updated: 15 August 2026

Iompar shows live Dublin bus departures using open transport data. This policy describes what the app does with your information.

## What this app does not do

The app has no accounts and no sign-in. It does not ask for your name, email or phone number. It contains no advertising and no analytics or tracking SDKs.

## Location

If you enable location sorting, the app reads your device location to order nearby stops by distance and to show the closest favourite in the home screen widget.

Your location is used entirely on your device. It is never transmitted anywhere. Turning off location sorting in Settings stops the app reading it.

The home screen widget reads your location while the app is not open, so that it can show the nearest stop without you launching the app. This only happens if location sorting is enabled.

## Information stored on your device

Your favourite stops, theme, notification schedules, recent searches and settings are stored on your device only. Uninstalling the app removes them.

## Servers and network

The app talks to the backend servers, which supply timetable and live departure data. They are hosted in Germany and reached over an encrypted connection through Cloudflare, which acts as a network provider for the connection.

The backend servers do not log the IP addresses of requests.

Advanced users can point the app at a different backend server in Settings. If you do that, the data described above goes to whoever operates that server, and this policy no longer governs it.

## Maps

Map imagery is served by CartoDB, drawn from OpenStreetMap data. When you open a map, your device requests the map squares it needs directly from CartoDB's servers. Those requests carry your IP address, so CartoDB can see what part of the map you are viewing. The app cannot show maps without it.

## On-device server

The app can optionally run a server on your device so that tools on your own network can read and change its data. It is off by default. When enabled it has no authentication, so anyone who can reach your device on the chosen network can use it. Only enable it on networks you trust.

## Children

The app is not directed at children and does not knowingly collect information from them.

## Changes

If this policy changes, the updated version will appear on this page and in the app listing.

## Contact

Questions about this policy: uzoukwuc@tcd.ie
