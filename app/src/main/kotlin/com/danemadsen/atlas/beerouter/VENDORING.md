# Integrated: BeeRouter

Upstream: https://codeberg.org/jgillich/beerouter
Snapshotted commit: `940b9727dc76bed2185e0828866a22ff15c46c2e` (2026-04-30)
License: MPL-2.0 (see LICENSE; BRouter attribution in ATTRIBUTION)

Vendored (not a Maven dependency) because the user asked us to bundle the
source, and because the upstream project at v0.0.4 has no `androidTarget()` —
so the code cannot be consumed from Android without local patching anyway.
Per the user's request the code now lives DIRECTLY in the app's source tree
(was `vendor/beerouter/*` Gradle modules) under
`app/src/main/kotlin/com/danemadsen/atlas/beerouter/`, repackaged
`dev.skynomads.beerouter.*` → `com.danemadsen.atlas.beerouter.*`.

## What lives where now

- `beerouter/{codec,expressions,geo,map,router,util}/` — upstream
  `core/src/commonMain` (the KMP routing engine, single-source-set collapse:
  `commonMain` + the Android `actual` merged; no KMP, no expect/actual).
- `beerouter/map/generator/` — upstream `generator/src/main` (the OSM-PBF→rd5
  data pipeline; plain `java.io`-based Kotlin, compiles unchanged on Android).
- `beerouter/profiles2/` — routing profiles + `lookups.dat` (provenance copy;
  the four the app uses are bundled in `app/src/main/assets/profiles/`).
- `beerouter/LICENSE`, `beerouter/ATTRIBUTION`, this file.
- NOT carried over: `core/src/jvmMain` (5 desktop-only files —
  `Rd5DiffManager/Tool/Validator`, `MapAccessFileSystem`, `ProfileComparator`;
  nothing in commonMain references them), `core/src/nativeMain`,
  `generator/src/test-legacy` (upstream parity tests pulling BRouter from
  jitpack). Unit tests from `core/src/jvmTest` + `generator/src/test` moved to
  `app/src/test/kotlin/.../beerouter/`, their resources to
  `app/src/test/resources/`.

## Local patches (re-apply after a refresh)

1. **Packaging**: all sources repackaged `dev.skynomads.beerouter` →
   `com.danemadsen.atlas.beerouter` (imports and package declarations).
2. `beerouter/map/open-random-access.kt` — the platform `openRandomAccess`
   declared as a plain `internal fun` next to `MapSource.kt` (the KMP
   expect/actual pair was collapsed in the move; the implementation is the
   upstream jvmMain one, `java.io.RandomAccessFile`).
3. `beerouter/map/generator/WayLinker.kt` — added an optional
   `disableSlave: Boolean = false` parameter to `process()` (marked
   `LOCAL PATCH (Atlas)` in the source). Upstream always runs the linker's
   master+slave threads concurrently; a 5° metro bucket's node map (~3M nodes)
   does not fit twice in Android's largeHeap (512-576 MB), so Atlas passes
   `disableSlave = true` and the master links every way file sequentially.
   Output is unchanged: each `.wt5` file is processed independently, and with
   no slave alive `currentSlaveSize` stays 0 so the master never declines
   a file.
4. `beerouter/map/generator/WayLinker.kt` — added a dev-only
   instrumentation hook (`companion object { var onPhase }` + private
   `probe()` calls at the linker's phase boundaries). Null in production;
   the dev `graphBuildCli` harness registers a callback to print the
   forced-GC live set per phase, which is how the on-device OOM was
   localized (node objects + their `Position` objects dominate the peak;
   the per-segment `OsmLinkP` cost is tiny because the self-link trick
   reuses the node object for ~2.6M of ~3M segments).
5. `beerouter/map/generator/OsmNodeP.kt`, `OsmNodePT.kt`, `WayLinker.kt`,
   `DPFilter.kt` — the node's `Position` object replaced with inline
   `longitude`/`latitude`/`altitude` scalars (`idFromPos` computes
   `Position.computeId(lon, lat)` on demand — it is a pure function, so
   semantics are identical). Measured: ~65 MB less live set on a 3M-node
   bucket, letting the metro build fit a 512 MB heap (previously OOMed at
   576 MB). Restriction positions (`viaPosition`/`fromPosition`/
   `toPosition`) construct a transient `Position` from the scalars —
   restrictions are rare and absent in the PMTiles pipeline.
   Verified output-neutral: the rebuilt `E140_S40.rd5` from the full
   australia.pmtiles is byte-identical to the pre-patch build except the
   8-byte `creationTimeStamp`.
6. `beerouter/codec/MicroCache.kt`, `MicroCache2.kt` — added an
   allocation-free `isInternal(longitude: Int, latitude: Int)` overload
   used by the patched `writeNodeData2` hot path (millions of checks);
   the `Position`-taking original is unchanged for the runtime.
7. `beerouter/map/generator/WayCutter.kt`, `NodeFilter.kt`,
   `WayCutter5.kt` — dual storage modes for the nid-keyed structures
   (`LOCAL PATCH (Atlas)` notes in the sources). The original scatter maps
   (`MutableLongIntMap` / the node bitmap) remain the default; a caller
   whose upstream scan assigns DENSE node ids — Atlas's `PmtilesCutter`
   numbers them 1..nodeCount, all assigned before any emission — calls
   `WayCutter.beginDenseIndex(count)` / `NodeFilter.beginDenseMarks(count)`
   at scan end, and sets `WayCutter5.expectedNodeCount` before `process()`;
   the maps then become an `IntArray`/`ByteArray`. Reads/writes answer
   identically for nids inside 0..count (dense mode fails loudly outside
   it, where a scatter map would silently answer -1 / lose a write).
   Motivation: a 4.6M-node bucket's scatter maps cost ~250 MB of
   long-arrays the device heap does not have; the arrays are ~20 MB.
   Verified output-neutral: the rebuilt `E140_S40.rd5` from the melbourne
   fixture is byte-identical to the pre-patch build except the 8-byte
   `creationTimeStamp`.
8. `beerouter/map/OsmNode.kt` (6-arg `addLink`) — a reverse body record for
   an EXTERNAL target now attaches its description bitmap to the link
   instance when the instance has none (`LOCAL PATCH (Atlas)` in the
   source). The writer already stores the description on reverse records
   for external targets (upstream `writeNodeData2`), but stock dropped it
   at merge time — so a link instance born from the reverse record (a
   fresh proxy when a 5°-bucket border tile re-weaves after eviction, or
   an instance re-created after `OsmLink.clear()` nulled its description)
   stayed desc-less forever. Routing through it took the beeline branch:
   zero-cost border crossings and, on kinematic profiles, the
   "Required value was null." crash (see patch 9). Internal reverse
   records are written desc-less and remain no-ops. Refinement: because
   geometry is only ever written by forward records, a reverse-attached
   description must not block the later forward record from completing
   the same instance — the merge condition accepts instances flagged
   `descriptionFromReverseRecord` (see `OsmLink`), the flag is cleared
   when the forward record fills the link, and `OsmLink.clear()` resets
   it. Without this, reverse-before-forward parsing forked a duplicate,
   geometry-less phantom instance that the router could prefer as a
   corner-cutting straight line (lost shape points/elevation, skipped
   mid-link nogo checks).
9. `beerouter/router/OsmPath.kt` (`addAddionalPenalty` beeline branch) —
   the beeline branch now sets `originPosition = sourceNode.position`
   (`LOCAL PATCH (Atlas)` in the source). A beeline section runs straight
   from source to target, so the position before its end — what
   `originPosition` means everywhere else in the section loop — is the
   source node. Stock left it null; `KinematicPrePath.initPrePath`
   requires it non-null for EVERY candidate link on kinematic profiles
   (car-vario), so expanding any desc-less-link path crashed the engine.
   Regression tests: `map/OsmNodeTest.kt` (merge-rule cases) and
   `router/border-beeline-regression-test.kt`; end-to-end proof: the
   Melbourne→Gippsland two-bucket repro (E140_S40 + E145_S40,
   `graphRouteCli -Pfrom=144.9631,-37.8142 -Pto=145.86,-38.14`) routes
   cleanly since the patch (97.5 km, 48 voice hints).

## Refresh procedure

1. Re-copy `core/src/commonMain`, `generator/src/main`, `misc/profiles2/`,
   `LICENSE`, `ATTRIBUTION` from a fresh upstream clone.
2. Repackage `dev.skynomads.beerouter` → `com.danemadsen.atlas.beerouter`.
3. Re-apply the patches above (the sources carry `LOCAL PATCH` markers).
4. Re-run `./gradlew :app:testDebugUnitTest :app:assembleDebug`.