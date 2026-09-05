<div align="center" id = "top">
  <img alt="logo" height="200px" src="https://raw.githubusercontent.com/Mobile-Artificial-Intelligence/atlas/main/logo.svg">
</div>

# Atlas — fully-offline maps for Android

Atlas is an open-source Android maps app that renders, searches, routes, and
navigates **entirely offline**. You supply a [PMTiles](https://protomaps.io)
archive (OpenMapTiles schema, e.g. produced with
[Planetiler](https://github.com/onthegomap/planetiler)); Atlas does the rest
with zero network access.

The zero-network rule is structural, not a setting: the merged APK does not
hold the `INTERNET` permission at all — no `ACCESS_NETWORK_STATE`, no
`ACCESS_WIFI_STATE` either — and a build-time check fails the build (and CI)
if any dependency ever smuggles one back in through its manifest.

## Features

- **Offline map rendering** — [MapLibre Native](https://maplibre.org) with an
  OSM Liberty-derived style in light and dark themes, dynamic Material You
  accent, bundled sprites and glyphs. No tile server, ever.
- **Offline search** — places and POIs indexed into an on-device full-text
  index (Room FTS) at archive import and as routing data is prepared.
- **Offline routing** — [BeeRouter](https://codeberg.org/jgillich/beerouter)
  (a BRouter descendant) drives turn-by-turn car, bike, and walking routes.
  The routing graph is built on demand per 5° region from the same PMTiles
  archive, in a separate process so a heavy build can't disturb navigation.
- **Turn-by-turn navigation** — GPS (no Play Services), voice guidance via the
  system TTS engine (works great with
  [Maise](https://github.com/Mobile-Artificial-Intelligence/maise)), spoken
  cues and turn banners, automatic rerouting when you leave the route.
- **Own your data** — one archive file covers a country (or the planet);
  everything else Atlas stores is derived from it on your device.

## How it works

1. **Import** — pick a `.pmtiles` file; Atlas copies it to internal storage,
   fingerprints it, and builds the low-zoom place index for search.
2. **Render** — the style template is resolved against the active theme's
   palette and pointed at the local archive; tiles come straight off disk.
3. **Route** — on a route request, Atlas cuts the OMT vector tiles covering
   the origin/destination into BeeRouter's segment format (`.rd5`), then runs
   the stock routing engine over them. Segments are cached per 5° bucket;
   Settings offers a "prepare everything" pass for power users.
4. **Navigate** — fixes from the platform `LocationManager` are snapped to the
   route; progress, spoken maneuvers, and off-route reroutes update live.

### Data caveats (honest limits)

Routing from OpenMapTiles-derived vector data is coarser than routing from
raw OSM PBF: the schema carries no turn restrictions, no maxspeed/surface/access
tags, and no elevation, so profiles fall back to class-based defaults and
`ascend`/`descend` read zero. Turn restrictions are therefore not honored. A
routing-enriched Planetiler profile is the natural future upgrade.

## Building

```sh
./gradlew assembleDebug        # debug APK
./gradlew :app:assembleRelease # signed release APK (needs key.properties)
```

Release signing follows the usual `key.properties` scheme: a gitignored file
at the repo root with `storeFile`, `storePassword`, `releaseAlias`, and
`releasePassword` pointing at your upload keystore. Without it, release
builds come out unsigned. CI (`.github/workflows/build.yml`) builds a signed
AAB plus a universal APK on every push to `main` from the org's secrets.

`versionCode` is derived from `git rev-list --count HEAD`, so every commit
ships a higher version than the last.

### Test corpus

The heavy Melbourne differential-test fixtures
(`app/src/test/fixtures/melbourne.{pbf,pmtiles}`) are gitignored — too large
for git. Tests that need them skip gracefully when they're absent, so a fresh
clone builds and tests green. To run the full differential suite, generate
the pair from an OSM extract with Planetiler (bbox extract) and osmium.

## License

Atlas is [MIT licensed](LICENSE).

Third-party components retain their own licenses, documented in place:

- **BeeRouter** is vendored at
  `app/src/main/kotlin/com/danemadsen/atlas/beerouter/` (MPL-2.0, with its
  BRouter heritage) — local modifications are listed in its `VENDORING.md`.
- **MapLibre Native**, the **OpenMapTiles** schema, the **PMTiles** format,
  the OSM Liberty style, and the bundled fonts/sprites are used under their
  respective licenses; the in-app attribution screen carries the full list.
- Map data ultimately derives from **OpenStreetMap** (ODbL, © OpenStreetMap
  contributors).