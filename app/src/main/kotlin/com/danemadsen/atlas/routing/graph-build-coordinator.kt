package com.danemadsen.atlas.routing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.PermissionChecker
import com.danemadsen.atlas.data.ArchiveInfo
import com.danemadsen.atlas.data.ArchiveStore
import com.danemadsen.atlas.graph.GraphBuildManager
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
     * when) location permission is granted. Only a fix INSIDE the loaded
     * archive's bbox can trigger a build — a passive provider can hand back
     * another app's fix from anywhere on Earth (the emulator's default
     * Mountain View fix did exactly that), and building a bucket the archive
     * doesn't cover is pure waste. The bucket name is decided in the service;
     * the status file carries it to the UI.
     */
    suspend fun triggerLocalBuild(context: Context) {
        if (!hasLocationPermission(context)) return
        val info = ArchiveStore.load(context) ?: return
        val location = currentLocation(context, info) ?: return
        val intent = serviceIntent(context, GraphBuildService.ACTION_BUILD_FOR_LOCATION)
            .putExtra(GraphBuildService.EXTRA_LON, location.longitude)
            .putExtra(GraphBuildService.EXTRA_LAT, location.latitude)
        start(context, intent)
    }

    suspend fun startAll(context: Context) {
        start(context, serviceIntent(context, GraphBuildService.ACTION_BUILD_ALL))
    }

    /**
     * A recent fix inside the archive, for routing origins — the same
     * bounds-checked lookup the build trigger uses. Null when location
     * permission is denied (per the offline product rule, routes do not
     * calculate without it) or when no fix is available yet.
     */
    suspend fun currentLocationInArchive(context: Context): android.location.Location? {
        if (!hasLocationPermission(context)) return null
        val info = ArchiveStore.load(context) ?: return null
        return currentLocation(context, info)
    }

    /**
     * The Settings "rebuild routing data" action: deletes every prepared
     * bucket and the build-state record of them, so the next build
     * starts from nothing. (The fingerprint-keyed wipe inside
     * [com.danemadsen.atlas.graph.GraphBuildManager] only fires when the
     * ARCHIVE changed — this is the same wipe for when the profile
     * assets changed instead.) Callers must stop any live routing
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
     * bucket segments) for the freshly imported archive, so routing works
     * immediately instead of after the ~30-minute-per-region on-device
     * build. Throws with a user-presentable message when the file is not a
     * usable Atlas routing bundle — the caller falls back to on-device
     * preparation and surfaces the failure.
     *
     * No build is started here; the buckets land in build-state as already
     * built, so the location-triggered and on-demand builds no-op for them.
     */
    suspend fun installRoutingData(context: Context, zip: android.net.Uri): Int =
        withContext(Dispatchers.IO) {
            val assets_dir = ensureBuildAssets(context)
            val manager = GraphBuildManager(
                archiveFile = ArchiveStore.archiveFile(context),
                segmentsDir = File(File(context.filesDir, "graph"), "segments"),
                workRoot = File(context.cacheDir, "graph-work"),
                assetsDir = assets_dir,
            )
            val input = context.contentResolver.openInputStream(zip)
                ?: error("the routing data file could not be opened")
            try {
                manager.adoptPrebuiltSegments(input).buckets.size
            } finally {
                input.close()
            }
        }

    /**
     * The Dismiss tombstone: without it, deleting the status file re-arms
     * the resume hook's automatic trigger, and the build the user just
     * dismissed silently restarts on their next return to the app. A new
     * archive import clears it.
     */
    fun setBuildDismissed(context: Context, dismissed: Boolean) {
        val flag = File(File(context.filesDir, "graph"), DISMISSED_FLAG)
        if (dismissed) flag.writeText("dismissed") else flag.delete()
    }

    fun isBuildDismissed(context: Context): Boolean =
        File(File(context.filesDir, "graph"), DISMISSED_FLAG).isFile

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
     * Last known fix inside the archive if one exists, else one fresh fix
     * (bounded wait). The app never had network, so providers are
     * GPS/passive only — but a passive fix can come from another app and be
     * anywhere at all, so every candidate is bounds-checked against
     * [ArchiveStore] and the most recent in-archive fix wins. A fresh fix
     * outside the archive yields null (no build) rather than a bucket the
     * archive doesn't cover.
     */
    private suspend fun currentLocation(
        context: Context,
        bounds: ArchiveInfo,
    ): android.location.Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val candidates = ArrayList<android.location.Location>()
        for (provider in manager.getProviders(true)) {
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null &&
                System.currentTimeMillis() - last.time <= MAX_LAST_KNOWN_AGE_MS &&
                insideArchive(last.longitude, last.latitude, bounds)
            ) {
                candidates.add(last)
            }
        }
        candidates.maxByOrNull { it.time }?.let { return it }
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { freshFix(manager) }
            ?.takeIf { insideArchive(it.longitude, it.latitude, bounds) }
    }

    fun insideArchive(lon: Double, lat: Double, bounds: ArchiveInfo): Boolean {
        if (lat < bounds.south || lat > bounds.north) return false
        // An archive straddling the antimeridian has west > east; then the
        // bbox is the union of both sides, not empty.
        val lon_in = if (bounds.west <= bounds.east) {
            lon >= bounds.west && lon <= bounds.east
        } else {
            lon >= bounds.west || lon <= bounds.east
        }
        return lon_in
    }

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