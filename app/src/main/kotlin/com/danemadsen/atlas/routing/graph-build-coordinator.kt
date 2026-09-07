package com.danemadsen.atlas.routing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.PermissionChecker
import com.danemadsen.atlas.data.RegionInfo
import com.danemadsen.atlas.data.RegionStore
import com.danemadsen.atlas.graph.GraphBuildManager
import com.danemadsen.atlas.search.SearchCoordinator
import com.danemadsen.atlas.services.GraphBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * UI-process glue for the graph build: extracts the build assets the APK
 * carries, reads the `:graph` service's status file, and starts/cancels the
 * service.
 *
 * Per the offline-build requirement, the location-triggered "prepare my
 * area" build runs ONLY when location permission has actually been granted
 * — with permission denied nothing is scheduled here (the on-demand flow
 * at route time covers it later).
 *
 * Atlas installs any number of map regions side by side (see
 * [RegionStore]); every coordinator entry point resolves WHICH region a
 * trigger is about — the region containing the current fix, or all of
 * them for the "prepare all" / search-index sweeps.
 */
object GraphBuildCoordinator {

    /** Single-flight lock for [ensureBuildAssets] — see its doc. */
    private val assets_mutex = Mutex()

    /** One snapshot of the `:graph` service's progress file. */
    data class BuildStatus(
        val running: Boolean,
        val bucket: String?,
        val built: Int,
        val total: Int,
        val error: String? = null,
        /** The current step within the bucket build, or null at bucket boundaries. */
        val label: String? = null,
        /** 0..1 through [label]'s step, or null while its size is unknown. */
        val fraction: Float? = null,
        /** What the run is building — [GraphBuildService.KIND_ROUTING] or [KIND_SEARCH]. */
        val kind: String = GraphBuildService.KIND_ROUTING,
        /** When the service last wrote the status (0 when the field is absent). */
        val timestampMs: Long = 0,
    ) {
        val done: Boolean get() = !running && error == null
    }

    /**
     * Copies `all.brf` and `lookups.dat` out of the APK assets the first
     * time — and whenever an app update ships new ones (a same-length,
     * different-content asset never trips the size check, so the app's
     * versionCode is the trigger). The service in the `:graph` process
     * reads them as plain files.
     *
     * NB: an app update that changes `lookups.dat` re-extracts it here but
     * does NOT invalidate `.rd5` buckets already built with the old lookup
     * table — `GraphBuildManager`'s state is keyed only on the archive
     * fingerprint. Until the build-state format grows an asset-version
     * field, the Settings "rebuild routing data" action (M9) is the
     * recovery path after such an update.
     */
    suspend fun ensureBuildAssets(context: Context): File = withContext(Dispatchers.IO) {
        // Single flight: route(), warmEngine(), and the import flow all
        // call this, and two concurrent re-extractions (routine after an
        // app update flips the version marker) would truncate-and-write
        // the same files while the other caller reads them — a torn
        // lookups.dat parses into a silently broken lookup table.
        assets_mutex.withLock {
            val profiles_dir = File(context.filesDir, "profiles").apply { mkdirs() }
            val version = appVersionCode(context).toString()
            val marker = File(profiles_dir, VERSION_MARKER)
            // The versionCode marker forces a re-extract on app update; the
            // per-file size check below stays as the repair path for a torn or
            // truncated copy.
            val stale = runCatching { marker.readText().trim() }.getOrNull() != version
            for (name in listOf(BUILD_PROFILE, LOOKUPS)) {
                val target = File(profiles_dir, name)
                context.assets.open("profiles/$name").use { input ->
                    val size = input.available().toLong()
                    if (stale || !target.isFile || target.length() != size) {
                        // Extract to a temp file and rename into place: the
                        // rename is atomic, so a reader in ANOTHER process
                        // (the :graph service) never sees a half-written
                        // copy, and a process death mid-extract leaves the
                        // previous intact file standing.
                        val tmp = File(profiles_dir, "$name.tmp")
                        tmp.outputStream().use { output -> input.copyTo(output) }
                        if (!tmp.renameTo(target)) {
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
            }
            // Updated only after both extractions succeeded.
            if (stale) marker.writeText(version)
            profiles_dir
        }
    }

    /**
     * Prepares the routing graph for the user's current area when (and only
     * when) location permission is granted. Only a fix INSIDE SOME
     * installed region's bbox can trigger a build — a passive provider can
     * hand back another app's fix from anywhere on Earth (the emulator's
     * default Mountain View fix did exactly that), and building a bucket no
     * region covers is pure waste. The bucket name is decided in the
     * service; the status file carries it to the UI.
     */
    suspend fun triggerLocalBuild(context: Context) {
        if (!hasLocationPermission(context)) return
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(context) }
        val location = currentLocation(context, regions) ?: return
        val region = RegionStore.regionForPoint(regions, location.longitude, location.latitude)
            ?: return
        val intent = serviceIntent(context, GraphBuildService.ACTION_BUILD_FOR_LOCATION)
            .putExtra(GraphBuildService.EXTRA_LON, location.longitude)
            .putExtra(GraphBuildService.EXTRA_LAT, location.latitude)
            .putExtra(GraphBuildService.EXTRA_REGION_ID, region.id)
        start(context, intent)
    }

    suspend fun startAll(context: Context) {
        start(context, serviceIntent(context, GraphBuildService.ACTION_BUILD_ALL))
    }

    /**
     * Starts the on-device search-index build, honoring the product order:
     * routing data first, search second. Called from the import flow and
     * the resume hook — the places where a brand-new region needs its
     * index.
     *
     * The decision tree, in order:
     * - EVERY region's index already complete → nothing to do. (The gate
     *   is per region, exactly as it was for the single archive.)
     * - A build is live (any kind) → send the intent anyway; the service
     *   queues it in its pending-intent slot and runs it after the current
     *   run finishes.
     * - No routing buckets prepared AND location permission granted →
     *   hold off: the location-triggered build is about to run and the
     *   service chains the search pass onto it itself. Starting search
     *   here would either interleave (two foreground runs) or pre-empt the
     *   queue with the lesser job.
     * - Otherwise (permission denied — no location build will ever trigger
     *   — or buckets already prepared) → start the search run now.
     *
     * The anchor (the last fix inside any region, else null) is computed
     * ONCE here and rides the intent: the `:graph` service orders its
     * per-region passes nearest-first around it and threads it into the
     * indexer, so nearby addresses become searchable first (WP5).
     */
    suspend fun triggerSearchIndex(context: Context, force: Boolean = false) {
        // The search-cancel tombstone: a cancelled pass must not restart
        // behind the user's back — unless this call is the user's own
        // explicit rebuild.
        if (!force && isSearchDismissed(context)) return
        if (force) setSearchDismissed(context, false)
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(context) }
        if (regions.all { SearchCoordinator.indexExists(context, it) }) return
        val anchor = currentLocationInRegions(context)
        val status = readStatus(context)
        if (status?.running == true) {
            start(context, anchorIntent(context, anchor))
            return
        }
        if (!hasLocationPermission(context) || hasPreparedBuckets(context, regions)) {
            start(context, anchorIntent(context, anchor))
        }
        // else: the location build will chain the search pass service-side.
    }

    /**
     * The search-index intent with the anchor extras applied: doubles when
     * a fix exists, nothing when it does not (the service distinguishes
     * "no anchor" by the extras' absence, not by a NaN sentinel).
     */
    private fun anchorIntent(context: Context, anchor: android.location.Location?): Intent =
        serviceIntent(context, GraphBuildService.ACTION_INDEX_SEARCH).apply {
            if (anchor != null) {
                putExtra(GraphBuildService.EXTRA_ANCHOR_LON, anchor.longitude)
                putExtra(GraphBuildService.EXTRA_ANCHOR_LAT, anchor.latitude)
            }
        }

    /** Whether ANY region has routing buckets already prepared. */
    private suspend fun hasPreparedBuckets(context: Context, regions: List<RegionInfo>): Boolean =
        withContext(Dispatchers.IO) {
            val assets_dir = ensureBuildAssets(context)
            regions.any { region ->
                GraphBuildManager(
                    archiveFile = RegionStore.archiveFile(RegionStore.mapDir(context), region.id),
                    segmentsDir = segmentsDir(context, region.id),
                    workRoot = File(context.cacheDir, "graph-work"),
                    assetsDir = assets_dir,
                ).builtBuckets().isNotEmpty()
            }
        }

    /**
     * A recent fix inside ANY installed region, for routing origins and
     * the search-index anchor — the same bounds-checked lookup the build
     * trigger uses. Null when location permission is denied (per the
     * offline product rule, routes do not calculate without it) or when no
     * fix is available yet.
     */
    suspend fun currentLocationInRegions(context: Context): android.location.Location? {
        if (!hasLocationPermission(context)) return null
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(context) }
        return currentLocation(context, regions)
    }

    /**
     * The Settings "rebuild routing data" action: deletes every prepared
     * bucket and the build-state record of them — for ALL regions, since
     * the flag is a profile-asset recovery path, not a region operation —
     * so the next build starts from nothing. (The fingerprint-keyed wipe
     * inside [com.danemadsen.atlas.graph.GraphBuildManager] only fires when
     * a region's ARCHIVE changed — this is the same wipe for when the
     * profile assets changed instead.) Callers must stop any live routing
     * first: a session mid-drive is reading those very files.
     */
    suspend fun wipeRoutingData(context: Context) = withContext(Dispatchers.IO) {
        File(File(context.filesDir, "graph"), "segments").deleteRecursively()
        statusFile(context).delete()
    }

    fun cancel(context: Context) {
        context.startService(serviceIntent(context, GraphBuildService.ACTION_CANCEL))
    }

    /** Deletes the status file — the banner's Dismiss on an interrupted or failed build. */
    fun clearStatus(context: Context) {
        statusFile(context).delete()
    }

    /**
     * Installs a user-supplied prebuilt routing-data ZIP (a set of `.rd5`
     * bucket segments) for the region [regionId] names — the caller
     * resolves the region from the ZIP's manifest fingerprint pairing — so
     * routing works immediately instead of after the ~30-minute-per-region
     * on-device build. Throws with a user-presentable message when the
     * file is not a usable Atlas routing bundle (or the region id names no
     * installed region) — the caller falls back to on-device preparation
     * and surfaces the failure.
     *
     * No build is started here; the buckets land in build-state as already
     * built, so the location-triggered and on-demand builds no-op for them.
     * The manifest's empty buckets count too: they are regions the archive
     * genuinely has no roads for, and marking them built is what stops the
     * location trigger from re-scanning ocean.
     */
    suspend fun installRoutingData(context: Context, regionId: String, zip: android.net.Uri): Int =
        withContext(Dispatchers.IO) {
            val region = RegionStore.load(context, regionId)
                ?: error("no installed map region matches this routing data — re-download the " +
                    "routing data and the map archive from the same build")
            val assets_dir = ensureBuildAssets(context)
            val manager = GraphBuildManager(
                archiveFile = RegionStore.archiveFile(RegionStore.mapDir(context), region.id),
                segmentsDir = segmentsDir(context, region.id),
                workRoot = File(context.cacheDir, "graph-work"),
                assetsDir = assets_dir,
            )
            val input = context.contentResolver.openInputStream(zip)
                ?: error("the routing data file could not be opened")
            try {
                val adoption = manager.adoptPrebuiltSegments(input)
                adoption.buckets.size + adoption.emptyBuckets.size
            } finally {
                input.close()
            }
        }

    /**
     * The Dismiss tombstone: without it, deleting the status file re-arms
     * the resume hook's automatic trigger, and the build the user just
     * dismissed silently restarts on their next return to the app. A new
     * region import clears it.
     */
    fun setBuildDismissed(context: Context, dismissed: Boolean) {
        val flag = File(File(context.filesDir, "graph"), DISMISSED_FLAG)
        if (dismissed) flag.writeText("dismissed") else flag.delete()
    }

    fun isBuildDismissed(context: Context): Boolean =
        File(File(context.filesDir, "graph"), DISMISSED_FLAG).isFile

    /**
     * The search run's cancel tombstone — see [GraphBuildService.SEARCH_DISMISSED_FLAG].
     * Gates the auto-trigger so a cancelled pass stays cancelled; [force]
     * callers (explicit rebuild) clear it.
     */
    fun isSearchDismissed(context: Context): Boolean =
        File(File(context.filesDir, "graph"), GraphBuildService.SEARCH_DISMISSED_FLAG).isFile

    fun setSearchDismissed(context: Context, dismissed: Boolean) {
        val flag = File(File(context.filesDir, "graph"), GraphBuildService.SEARCH_DISMISSED_FLAG)
        if (dismissed) flag.writeText("dismissed") else flag.delete()
    }

    /** The service's last written status, or null when it never ran. */
    fun readStatus(context: Context): BuildStatus? {
        val file = statusFile(context)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            BuildStatus(
                running = json.getBoolean("running"),
                bucket = json.optString("bucket").ifEmpty { null },
                built = json.optInt("built"),
                total = json.optInt("total"),
                error = json.optString("error").ifEmpty { null },
                label = json.optString("label").ifEmpty { null },
                // The service writes -1.0 for "no fraction"; a status file
                // from an older build omits the field entirely.
                fraction = json.optDouble("fraction", -1.0).takeIf { it >= 0.0 }?.toFloat(),
                kind = json.optString("kind").ifEmpty { GraphBuildService.KIND_ROUTING },
                timestampMs = json.optLong("ts"),
            )
        }.getOrNull()
    }

    /** Small file, but it sits on the UI's path at ~2 Hz — keep the disk read off the main thread. */
    suspend fun readStatusAsync(context: Context): BuildStatus? =
        withContext(Dispatchers.IO) { readStatus(context) }

    /** ~2 Hz UI polling budget: how stale a status read may be. */
    fun statusFile(context: Context): File =
        File(File(context.filesDir, "graph"), STATUS_FILE)

    private suspend fun start(context: Context, intent: Intent) {
        ensureBuildAssets(context)
        context.startForegroundService(intent)
    }

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, GraphBuildService::class.java).setAction(action)

    /** One region's segment dir — the same layout [RouterGateway] and the service use. */
    private fun segmentsDir(context: Context, regionId: String): File =
        File(File(File(context.filesDir, "graph"), "segments"), regionId)

    fun hasLocationPermission(context: Context): Boolean =
        PermissionChecker.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PermissionChecker.PERMISSION_GRANTED ||
            PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PermissionChecker.PERMISSION_GRANTED

    /**
     * Last known fix inside any installed region if one exists, else one
     * fresh fix (bounded wait). The app never had network, so providers
     * are GPS/passive only — but a passive fix can come from another app
     * and be anywhere at all, so every candidate is bounds-checked against
     * [RegionStore] and the most recent in-region fix wins. A fresh fix
     * outside every region yields null (no build) rather than a bucket no
     * region covers.
     */
    private suspend fun currentLocation(
        context: Context,
        regions: List<RegionInfo>,
    ): android.location.Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val candidates = ArrayList<android.location.Location>()
        for (provider in manager.getProviders(true)) {
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null &&
                System.currentTimeMillis() - last.time <= MAX_LAST_KNOWN_AGE_MS &&
                insideAnyRegion(regions, last.longitude, last.latitude)
            ) {
                candidates.add(last)
            }
        }
        candidates.maxByOrNull { it.time }?.let { return it }
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { freshFix(manager) }
            ?.takeIf { insideAnyRegion(regions, it.longitude, it.latitude) }
    }

    /**
     * The bbox containment rule for ONE region — kept for callers that
     * already hold a [RegionInfo] (and its tests). Safe against a region
     * straddling the antimeridian (west > east means the bbox is the
     * union of both sides). New code should prefer
     * [RegionStore.regionForPoint]/[insideAnyRegion], which resolve the
     * owning region directly.
     */
    fun insideArchive(lon: Double, lat: Double, region: RegionInfo): Boolean {
        if (lat < region.south || lat > region.north) return false
        // An archive straddling the antimeridian has west > east; then the
        // bbox is the union of both sides, not empty.
        val lon_in = if (region.west <= region.east) {
            lon >= region.west && lon <= region.east
        } else {
            lon >= region.west || lon <= region.east
        }
        return lon_in
    }

    /** Whether ANY installed region's bbox contains the point. */
    fun insideAnyRegion(regions: List<RegionInfo>, lon: Double, lat: Double): Boolean =
        regions.any { insideArchive(lon, lat, it) }

    private suspend fun freshFix(
        manager: LocationManager,
    ): android.location.Location? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var got = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (got) return
                got = true
                manager.removeUpdates(this)
                cont.resume(location)
            }
        }
        var launched = false
        for (provider in manager.getProviders(true)) {
            if (manager.isProviderEnabled(provider)) {
                launched = true
                manager.requestLocationUpdates(
                    provider, FRESH_FIX_MIN_MS, FRESH_FIX_MIN_M, listener, handler.looper,
                )
            }
        }
        if (!launched) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { handler.post { manager.removeUpdates(listener) } }
    }

    private fun appVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        // longVersionCode needs API 28; our minSdk is 26.
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private const val STATUS_FILE = "build-status.json"
    private const val DISMISSED_FLAG = "build-dismissed.flag"
    private const val BUILD_PROFILE = "all.brf"
    private const val LOOKUPS = "lookups.dat"
    private const val VERSION_MARKER = "profiles.version"
    private const val FRESH_FIX_TIMEOUT_MS = 30_000L
    private const val FRESH_FIX_MIN_MS = 1_000L
    private const val FRESH_FIX_MIN_M = 50f
    private const val MAX_LAST_KNOWN_AGE_MS = 7L * 24 * 60 * 60 * 1000
}