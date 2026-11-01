# Dead Ground

Private weather brief for one person. No account, no trackers, no Play Services library.

## Privacy
- Location: coarse permission only, rounded to 2 decimals (~1 km) before it leaves the phone.
- Weather and place search: Open-Meteo (no key, no account). That is the only network destination.
- Official warnings: MeteoAlarm's whole-country feed (Latvia) is downloaded and checked on the phone; MeteoAlarm never sees a position.
- Air quality: Open-Meteo air-quality API, same provider.
- Radar is a MODEL SCOPE: Open-Meteo 15-min forecasts for 24 points around the AO, not live radar.
- Place search sends only the typed text. Saved AOs live on the device; the AO tab fetches them in one request.
- Note: on phones with Google services, Android's *network* location provider is itself
  answered by Google. A GPS-only mode is planned for later.

## Build and updates
Every push to `main` runs the tests, builds a signed APK and publishes it as a GitHub Release
(`v0.1.<run>`). Obtainium on the phone watches those releases and offers each update.

One-time setup:
1. Repo → Settings → Secrets and variables → Actions → add `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`
   (values are in `signing-secrets.txt`, which is NOT in this repo).
2. Push. Check Actions → build succeeded and a release appeared.
3. On the phone: install Obtainium, add this repo's URL, install Dead Ground from it.
   Private repo → give Obtainium a fine-grained GitHub token with read-only "Contents" access to this repo.

Keep `dead-ground.jks` backed up. If it's lost, the next build can't update the installed app
and you have to uninstall once.

## Layout
- `Brief.kt` — pure logic: WXCON, GO/NO-GO window, threats, T+24H strip, loadout (tested)
- `Data.kt` — location (rounded) + Open-Meteo fetch/parse
- `Ui.kt` — flap tiles, topo background + rain/snow layer, brief screen, nav
- `Scope.kt` — model scope logic: ring of 25 points, contact, ETA, motion (tested)
- `Screens.kt` — Radar scope, 7 Day ops calendar, AO roster with search
- `Alerts.kt` — alert rules, quiet hours/cap gate, warnings → WXCON (tested)
- `Notify.kt` — 30-min JobScheduler check, notifications, ACK
- `Widget.kt` — home screen widget, drawn as one bitmap, refreshes for the last AO
- `MainActivity.kt` — permission, cache, refresh

## Milestones
1. Data + Brief ✓
2. Widget (W3 terrain, resizable 4×2 / 2×2) ✓
3. Radar (model scope), 7 Day (ops calendar), AO (roster + place search) ✓
4. **Alerts** ← this build: rain inbound, precip type change, ice, MeteoAlarm warnings, air quality.
   Signal format, quiet hours 22–07, max 4 per 24 h (red warnings always break through).
   Alerts follow the last CURRENT POS fix — no background location permission.

Fonts: Oswald, Share Tech Mono (SIL OFL, see `licenses/`).
