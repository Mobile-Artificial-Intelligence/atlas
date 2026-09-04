package com.danemadsen.atlas.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danemadsen.atlas.data.ArchiveStore
import com.danemadsen.atlas.graph.GraphBuildManager
import com.danemadsen.atlas.search.AddressEntity
import com.danemadsen.atlas.search.PlaceDatabase
import com.danemadsen.atlas.search.PlaceEntity
import com.danemadsen.atlas.search.SearchCoordinator
import com.danemadsen.atlas.search.SearchIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service in the dedicated `:graph` process that runs the
 * PMTiles -> `.rd5` bucket builds. The process is separate so a build's
 * heap footprint (a full metro bucket holds millions of nodes in memory
 * during the cutter phase) can never OOM the UI/navigation process — if
 * the builder dies, only the build dies.
 *
 * Progress crosses the process boundary as a small JSON file the UI polls
 * (`filesDir/graph/build-status.json`); `GraphBuildManager`'s own state
 * (`segmentsDir/build-state.json`) is the durable record of what is built.
 *
 * The status file carries a `ts` on every write: the UI treats a
 * `running=true` status older than its staleness budget as a build whose
 * process died mid-run (this service's designed failure mode), so a dead
 * process can never leave a frozen banner behind.
 */
class GraphBuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * All mutations of [runJob] happen on the main thread: onStartCommand
     * directly, and the pending-intent re-run via [main_handler]. The run
     * coroutine only nulls it from its finally — reading it from another
     * dispatcher would race the main thread's check-then-act and could
     * launch two concurrent manager runs.
     */
    @Volatile private var runJob: Job? = null

    /** The run loop is serialized onto the main thread via this handler. */
    private val main_handler = Handler(Looper.getMainLooper())
    private var managerRef: GraphBuildManager? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    /**
     * A build intent that arrived while a run was active (the user panned
     * into the adjacent bucket mid-build). Remembered, not dropped, and
     * consumed once when the active run completes.
     */
    @Volatile private var pendingIntent: Intent? = null

    /** Set before the timeout teardown so the cancellation path does not overwrite the timeout status. */
    @Volatile private var timedOut = false

    /**
     * Set by ACTION_CANCEL while a run is live: when that run ends, its
     * finally must drop any queued follow-on intent too — otherwise the
     * build the user just cancelled resurrects under them a minute later.
     */
    @Volatile private var cancelRequested = false

    /** The snapshot behind the current status file, for the heartbeat's ts refresh. */
    @Volatile private var lastSnapshot: BuildSnapshot? = null

    /** Unique tmp suffix per status write: concurrent writers must not share a tmp file. */
    private val statusTmpSeq = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BUILD_FOR_LOCATION -> {
                startForegroundWith(buildNotification())
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                if (lon.isNaN() || lat.isNaN()) {
                    // A malformed request must never touch the status file
                    // while a run is live — a terminal write here would end
                    // the live build's banner mid-run.
                    if (runJob?.isActive == true) {
                        Log.w(TAG, "bad location extras while a run is active; ignored: $intent")
                    } else {
                        fail("bad location extras in $intent")
                    }
                } else {
                    run(intent)
                }
            }
            ACTION_BUILD_ALL -> {
                startForegroundWith(buildNotification())
                run(intent)
            }
            ACTION_CANCEL -> {
                if (runJob?.isActive == true) {
                    cancelRequested = true
                    manager().cancel()
                } else {
                    // No run is active (typically a stale banner after
                    // process death): publish a clean terminal status
                    // instead of arming the manager's cancel flag, which
                    // would silently swallow the next build.
                    reportStatus(running = false, bucket = null, built = 0, total = 0, error = null)
                    stopSelfResult(startId)
                }
            }
            else -> {
                if (intent == null) {
                    // START_STICKY restart with no intent: the process died
                    // while this service existed. Only a status that still
                    // claims a live run is untrustworthy — replace it with
                    // an honest terminal state before stopping (a boot-time
                    // restart self-heals the same way). A terminal status
                    // from a completed build is left untouched.
                    if (statusClaimsRunning()) {
                        reportStatus(running = false, bucket = null, built = 0, total = 0, error = "interrupted")
                    }
                }
                stopSelfResult(startId)
            }
        }
        return START_STICKY
    }

    /**
     * Runs one manager call to completion, holding the wake lock, then —
     * unless another build request queued up meanwhile — stops.
     */
    private fun run(intent: Intent) {
        // A second intent while a run is in flight is remembered (latest
        // wins), not dropped: the status file already reflects the live run,
        // and the queued one is consumed on completion.
        if (runJob?.isActive == true) {
            pendingIntent = intent
            return
        }
        if (!scope.isActive) {
            // Teardown already happened (onTimeout/onDestroy cancels the
            // scope): a follow-on build is impossible here — launching into
            // a cancelled scope never runs its body, and the wake-lock
            // acquire below would leak. Drop the request; the terminal
            // status the teardown wrote is the last word.
            return
        }
        timedOut = false
        wakeLock.acquire(WAKE_LOCK_SLICE_MS)
        runJob = scope.launch {
            // Heartbeat: the old single 6h wake lock could expire mid-build
            // for a long buildAll, so the lock is held in 10-minute slices
            // re-armed here — re-acquiring re-arms the system's timeout even
            // while held, and recovers a slice the system already released.
            // The heartbeat also re-stamps the status file's ts so the UI's
            // staleness check keeps working during multi-minute buckets.
            //
            // It runs on its own scheduled thread, NOT a Default-dispatcher
            // coroutine: the measured on-device metro build showed the
            // linker's GC-heavy phases starving the dispatcher for tens of
            // minutes at a time (ts re-stamped only every ~15 min despite
            // the 30s interval), which the UI's 90s staleness budget would
            // read as a dead process. A dedicated thread keeps the beacon
            // honest no matter how saturated the compute pool is.
            val heartbeat = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "atlas-graph-heartbeat").apply { isDaemon = true }
            }
            heartbeat.scheduleAtFixedRate({
                wakeLock.acquire(WAKE_LOCK_SLICE_MS)
                lastSnapshot?.takeIf { it.running }?.let { writeStatus(it) }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
            var terminalError: String? = null
            try {
                dispatch(intent)
            } catch (e: CancellationException) {
                // A cancelled/aborted run is not a failure — no error in the
                // terminal status. Only coroutine cancellation takes this
                // path (kotlinx/kotlin CancellationException alias
                // java.util.concurrent.CancellationException on the JVM);
                // genuine errors fall through to Throwable below.
                if (timedOut) terminalError = "build timed out"
            } catch (t: Throwable) {
                // Throwable, not Exception: OutOfMemoryError is a designed
                // possibility here (the :graph heap is deliberately large),
                // and it must surface in the status file, not kill the
                // process with a running=true banner left behind.
                terminalError = "build failed: ${t.message}"
            } finally {
                // Stop the heartbeat BEFORE the terminal write — shutdownNow
                // cancels pending ticks, and awaitTermination lets an
                // in-flight tick finish (its body — wakeLock acquire + status
                // write — has no suspension points, so it returns promptly);
                // without the wait, an unconsumed tick could otherwise land
                // a fresh running=true AFTER the terminal write, or
                // re-acquire the wake lock after the release below. The
                // blocking wait must run under NonCancellable: this finally
                // may itself be executing due to cancellation, and
                // suspending in a cancelled coroutine would throw.
                withContext(NonCancellable) {
                    heartbeat.shutdownNow()
                    heartbeat.awaitTermination(5, TimeUnit.SECONDS)
                }
                reportStatus(running = false, bucket = null, built = 0, total = 0, error = terminalError)
                // The pending-intent snapshot-and-clear must run on the
                // main thread, like every other pendingIntent access:
                // doing it here on a Default worker races a concurrent
                // onStartCommand's store (the read could happen before it
                // and the null-after it, silently dropping a queued
                // build). Posting the handoff also serializes it against
                // a new onStartCommand that may have started a run
                // directly in the gap between this coroutine's end and
                // the post's execution.
                main_handler.post {
                    val pending = if (cancelRequested) null else pendingIntent
                    pendingIntent = null
                    cancelRequested = false
                    if (pending != null) {
                        run(pending)
                    } else if (runJob?.isActive != true) {
                        // A run that started in the gap owns the wake
                        // lock and the service now — do not release or
                        // stop under it.
                        if (wakeLock.isHeld) wakeLock.release()
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun dispatch(intent: Intent) {
        // The search deep pass rides the build's own tile scan: the scan
        // already decompresses every tile the archive holds for the bucket,
        // so extracting `place`/`poi` rows on the way through costs one
        // extra decode per tile — not a second archive read.
        val deep_pass = openDeepPass()
        try {
            when (intent.action) {
                ACTION_BUILD_ALL -> manager().buildAll(deep_pass?.sink, ::reportProgress)
                else -> {
                    val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                    val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                    if (lon.isNaN() || lat.isNaN()) throw IllegalArgumentException("bad location extras in $intent")
                    manager().ensureBucketsFor(lon, lat, deep_pass?.sink, ::reportProgress)
                }
            }
        } finally {
            // Also on failure/cancellation: an unclosed channel would hang
            // the drain and an unclosed DB would leak its handle for the
            // process's lifetime.
            deep_pass?.finish()
        }
    }

    /**
     * The deep pass for this run, or null when search has nothing to write
     * into yet (no index DB — a fresh install whose cheap pass has not
     * run, or an archive whose indexes were wiped). When the cheap pass is
     * still running concurrently, a deep row can win a place key first and
     * the cheap row is then IGNOREd on the unique index — one slightly
     * different representative point for that place, not a correctness
     * issue.
     */
    private fun openDeepPass(): DeepPass? {
        val archive = ArchiveStore.load(this) ?: return null
        if (!SearchCoordinator.indexExists(this, archive)) return null
        val indexer = SearchIndexer(this, SearchCoordinator.databaseFor(this, archive))
        val db = indexer.open()
        val place_channel = Channel<PlaceEntity>(Channel.UNLIMITED)
        val address_channel = Channel<AddressEntity>(Channel.UNLIMITED)
        // The sink runs inline in the build thread's scan visitor (it must
        // not block); the DB writes happen here, off the build thread. Places
        // and addresses drain to their own tables — addresses are the bulk
        // (a bucket scan re-offers them constantly) and their FTS shadow
        // syncs per insert via Room's content-entity triggers, so there is
        // no rebuild here: the place_fts rebuild is a small-table compaction
        // run only when this pass offered place rows at all.
        val drain = scope.launch(Dispatchers.IO) {
            val place_batch = ArrayList<PlaceEntity>(SearchIndexer.BATCH_ROWS)
            var place_rows = 0
            for (entity in place_channel) {
                place_batch.add(entity)
                if (place_batch.size >= SearchIndexer.BATCH_ROWS) {
                    db.placeDao().insertBatch(place_batch)
                    place_rows += place_batch.size
                    place_batch.clear()
                }
            }
            if (place_batch.isNotEmpty()) {
                db.placeDao().insertBatch(place_batch)
                place_rows += place_batch.size
            }
            if (place_rows > 0) db.placeDao().rebuildFts()
            val address_batch = ArrayList<AddressEntity>(SearchIndexer.ADDRESS_BATCH_ROWS)
            for (entity in address_channel) {
                address_batch.add(entity)
                if (address_batch.size >= SearchIndexer.ADDRESS_BATCH_ROWS) {
                    db.addressDao().insertAll(address_batch)
                    address_batch.clear()
                }
            }
            if (address_batch.isNotEmpty()) db.addressDao().insertAll(address_batch)
        }
        return DeepPass(
            db = db,
            placeChannel = place_channel,
            addressChannel = address_channel,
            drain = drain,
            sink = { zoom, x, y, bytes ->
                val candidates = SearchIndexer.candidatesFromTile(zoom, x, y, bytes)
                for (place in candidates.places) place_channel.trySend(place)
                for (address in candidates.addresses) address_channel.trySend(address)
            },
        )
    }

    /** One run's deep pass: the sink the scan calls, drained and closed by [finish]. */
    private class DeepPass(
        private val db: PlaceDatabase,
        private val placeChannel: Channel<PlaceEntity>,
        private val addressChannel: Channel<AddressEntity>,
        private val drain: Job,
        val sink: (zoom: Int, x: Int, y: Int, bytes: ByteArray) -> Unit,
    ) {
        /**
         * Drains what the scan enqueued and closes the DB. NonCancellable:
         * this runs from dispatch's finally, which may itself be executing
         * because the run was cancelled.
         */
        suspend fun finish() {
            withContext(NonCancellable) {
                placeChannel.close()
                addressChannel.close()
                drain.join()
                db.close()
            }
        }
    }

    /** Only for a failure with no run to attach it to; the status is the whole story. */
    private fun fail(message: String) {
        reportStatus(running = false, bucket = null, built = 0, total = 0, error = message)
        stopSelf()
    }

    /**
     * One manager for the service's lifetime — `ACTION_CANCEL` must reach
     * the same instance that is running, since cancellation is an in-memory
     * flag. Paths are process-independent (same filesDir/cacheDir).
     */
    private fun manager(): GraphBuildManager {
        managerRef?.let { return it }
        val segments = File(File(filesDir, "graph"), "segments").apply { mkdirs() }
        val work = File(cacheDir, "graph-work").apply { mkdirs() }
        return GraphBuildManager(
            archiveFile = File(File(filesDir, "map"), "atlas.pmtiles"),
            segmentsDir = segments,
            workRoot = work,
            assetsDir = File(filesDir, "profiles"),
        ).also { managerRef = it }
    }

    // ---- status + notification plumbing ----

    private fun reportProgress(progress: GraphBuildManager.Progress) {
        writeStatus(BuildSnapshot(
            running = true,
            bucket = progress.bucket,
            built = progress.built,
            total = progress.total,
            error = null,
        ))
        updateNotification(progress)
    }

    private fun reportStatus(
        running: Boolean,
        bucket: String?,
        built: Int,
        total: Int,
        error: String?,
    ) {
        writeStatus(BuildSnapshot(running, bucket, built, total, error))
    }

    private data class BuildSnapshot(
        val running: Boolean,
        val bucket: String?,
        val built: Int,
        val total: Int,
        val error: String?,
    )

    /**
     * Atomic-ish: small file, written whole via a unique tmp + rename.
     * The tmp name must be unique per write — the run coroutine and the
     * heartbeat write concurrently, and a shared tmp name could publish a
     * torn file.
     */
    private fun writeStatus(snapshot: BuildSnapshot) {
        lastSnapshot = snapshot
        runCatching {
            val dir = File(filesDir, "graph").apply { mkdirs() }
            val tmp = File(dir, "$STATUS_FILE.tmp.${statusTmpSeq.incrementAndGet()}")
            tmp.writeText(
                JSONObject()
                    .put("running", snapshot.running)
                    .put("bucket", snapshot.bucket ?: "")
                    .put("built", snapshot.built)
                    .put("total", snapshot.total)
                    .put("error", snapshot.error ?: "")
                    .put("ts", System.currentTimeMillis())
                    .toString(),
            )
            if (!tmp.renameTo(File(dir, STATUS_FILE))) {
                File(dir, STATUS_FILE).delete()
                tmp.renameTo(File(dir, STATUS_FILE))
            }
        }.onFailure {
            // A silent write failure reads downstream as a dead build (the
            // route's ensureBucket and the banner both poll this file) —
            // it must at least be diagnosable in logcat.
            Log.w(TAG, "status write failed", it)
        }
    }

    private fun statusClaimsRunning(): Boolean {
        val file = File(File(filesDir, "graph"), STATUS_FILE)
        if (!file.isFile) return false
        return runCatching { JSONObject(file.readText()).optBoolean("running") }.getOrDefault(false)
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * targetSdk 36: a dataSync foreground service gets a hard time budget
     * on API 35+. End honestly — cancelled run, terminal status, released
     * wake lock — instead of being killed mid-build with a running banner
     * left behind.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        manager().cancel()
        scope.cancel()
        reportStatus(running = false, bucket = null, built = 0, total = 0, error = "build timed out")
        if (wakeLock.isHeld) wakeLock.release()
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Preparing routing data")
            .setContentText("Reading the map archive…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: GraphBuildManager.Progress) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing routing data")
                .setContentText("${progress.bucket} (${progress.built + 1}/${progress.total})")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_BUILD_FOR_LOCATION = "com.danemadsen.atlas.graph.BUILD_FOR_LOCATION"
        const val ACTION_BUILD_ALL = "com.danemadsen.atlas.graph.BUILD_ALL"
        const val ACTION_CANCEL = "com.danemadsen.atlas.graph.CANCEL"
        const val EXTRA_LON = "lon"
        const val EXTRA_LAT = "lat"

        private const val TAG = "GraphBuildService"
        private const val CHANNEL_ID = "graph_build"
        private const val CHANNEL_NAME = "Routing data preparation"
        private const val NOTIFICATION_ID = 2
        private const val STATUS_FILE = "build-status.json"
        private const val WAKE_LOCK_TAG = "atlas:graph-build"
        private const val WAKE_LOCK_SLICE_MS = 10L * 60 * 1000 // renewed by the heartbeat
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}