package com.danemadsen.atlas.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.danemadsen.atlas.data.RegionInfo
import com.danemadsen.atlas.data.RegionStore
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * The live run's region id — what a routing `ACTION_CANCEL`/timeout
     * must cancel (the manager instance whose in-memory cancel flag the
     * build checks). Null for search runs and between manager calls;
     * written only from [dispatch] on the run coroutine.
     */
    @Volatile private var currentRegionId: String? = null

    /** The per-region manager cache — see [managerFor]. */
    private val managers = java.util.concurrent.ConcurrentHashMap<String, GraphBuildManager>()
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
     * The active run's kind ("routing" or "search"), set at [run] start and
     * read by ACTION_CANCEL (only routing has a manager to cancel) and by
     * the finally's kind-aware announcements.
     */
    @Volatile private var currentKind = KIND_ROUTING

    /**
     * True once this run actually built something: a run whose buckets were
     * all already built (the routine no-op case) must not announce itself.
     */
    @Volatile private var sawBuildWork = false

    /**
     * Set by ACTION_CANCEL while a run is live: when that run ends, its
     * finally must drop any queued follow-on intent too — otherwise the
     * build the user just cancelled resurrects under them a minute later.
     */
    @Volatile private var cancelRequested = false

    /** The snapshot behind the current status file, for the heartbeat's ts refresh. */
    @Volatile private var lastSnapshot: BuildSnapshot? = null

    /**
     * Serializes status writes (the run coroutine, the heartbeat thread, and
     * terminal writes) AND makes the heartbeat's read of [lastSnapshot]
     * atomic with its re-write — without it a tick racing a fresher
     * snapshot's write could stamp the file with an older one.
     */
    private val statusLock = Any()

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
                startForegroundWith(buildNotification(KIND_ROUTING))
                val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val region_id = intent.getStringExtra(EXTRA_REGION_ID)
                if (lon.isNaN() || lat.isNaN() || region_id == null) {
                    // A malformed request must never touch the status file
                    // while a run is live — a terminal write here would end
                    // the live build's banner mid-run.
                    if (runJob?.isActive == true) {
                        Log.w(TAG, "bad location/region extras while a run is active; ignored: $intent")
                    } else {
                        fail("bad location/region extras in $intent")
                    }
                } else {
                    run(intent)
                }
            }
            ACTION_BUILD_ALL -> {
                startForegroundWith(buildNotification(KIND_ROUTING))
                run(intent)
            }
            ACTION_INDEX_SEARCH -> {
                startForegroundWith(buildNotification(KIND_SEARCH))
                run(intent)
            }
            ACTION_CANCEL -> {
                if (runJob?.isActive == true) {
                    cancelRequested = true
                    // Only a routing run has a manager to cancel; arming the
                    // manager's flag with no manager run live would silently
                    // swallow the NEXT routing build. The cancel must reach
                    // the manager of the region the run is currently in.
                    if (currentKind == KIND_ROUTING) currentRegionId?.let { managerFor(it).cancel() }
                } else {
                    // No run is late (typically a stale banner after process
                    // death): publish a clean terminal status instead of
                    // arming the manager's cancel flag, which would silently
                    // swallow the next build.
                    reportStatus(running = false, bucket = null, built = 0, total = 0, error = null, kind = currentKind)
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
        // run() is only reached with runJob inactive (onStartCommand's active
        // branch queues into pendingIntent above), and ACTION_CANCEL only
        // arms cancelRequested while a run IS active — so any value here is
        // stale from a run whose finally already consumed it. Clearing it
        // keeps a cancel that targeted run N from also killing run N+1.
        timedOut = false
        sawBuildWork = false
        cancelRequested = false
        // The run's kind decides the status file's `kind` field, the
        // notification's title and the done-notification's wording. Set
        // BEFORE the first status write so the very first snapshot already
        // names the run.
        currentKind = if (intent.action == ACTION_INDEX_SEARCH) KIND_SEARCH else KIND_ROUTING
        wakeLock.acquire(WAKE_LOCK_SLICE_MS)
        // The first status write happens HERE, synchronously, not at the
        // run coroutine's first progress tick: between intent delivery and
        // that tick (cold :graph process start, archive open, bucket
        // enumeration) the status file still holds the PREVIOUS run's
        // terminal state, and a concurrent reader — installRoutingData's
        // stop-the-build handshake, router-gateway's bucket poll — would
        // conclude nothing is running and race the very build it is trying
        // to avoid. bucket=null renders as the banner's neutral "reading
        // the map archive…". A tiny main-thread file write, once per run.
        reportStatus(running = true, bucket = null, built = 0, total = 0, error = null, kind = currentKind)
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
                // Inside the lock so the re-stamp cannot interleave with a
                // newer snapshot's write and regress the file (writeStatus
                // is reentrant on this same lock).
                synchronized(statusLock) {
                    lastSnapshot?.takeIf { it.running }?.let { writeStatus(it) }
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
            var terminalError: String? = null
            // Set only when dispatch() returned normally — an aborted run
            // (onDestroy's scope.cancel, a user cancel) must not announce
            // "ready", and neither must one still queued behind a handoff.
            var completed_normally = false
            try {
                dispatch(intent)
                completed_normally = true
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
                // Snapshot this run's own state BEFORE the handoff post:
                // the post runs later on the main thread, and by then the
                // service-wide flags can belong to a different run (a build
                // started in the gap, or the follow-on's first ticks) —
                // reading them inside the post would attribute that run's
                // outcome to this one. At capture time this coroutine is
                // still live, so a concurrent intent is guaranteed to land
                // in pendingIntent (onStartCommand sees runJob active), not
                // start a run the capture could miss.
                val saw_work = sawBuildWork
                val was_cancelled = cancelRequested
                if (was_cancelled && currentKind == KIND_SEARCH) {
                    // The user's cancel of the search pass is the last word
                    // on the CHAIN too: without this tombstone, the next
                    // no-op routing trigger (the UI's resume hook re-fires
                    // it on every tab switch) would chain the index build
                    // straight back. Cleared by an explicit rebuild or a
                    // new archive import — deliberate requests.
                    runCatching {
                        File(File(filesDir, "graph"), SEARCH_DISMISSED_FLAG).writeText("dismissed")
                    }
                }
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
                    val pending = if (was_cancelled) null else pendingIntent
                    pendingIntent = null
                    if (pending != null) {
                        // A queued build takes over: the success handoff
                        // belongs to ITS terminal state, not this run's.
                        // But a FAILED run still announces itself — the
                        // follow-on may succeed without covering this
                        // run's buckets, and its status writes would
                        // overwrite this error before anyone sees it.
                        if (terminalError != null) {
                            notifyDone(failureTitle(currentKind), terminalError)
                        }
                        run(pending)
                    } else {
                        // The run's end is only now final (no follow-on):
                        // announce it. Failure beats success; a cancelled
                        // run says nothing (the user just asked for it);
                        // a no-op run (everything already built) never
                        // announces. Posted under NOTIFICATION_DONE_ID so it
                        // survives stopSelf() removing the foreground one.
                        if (terminalError != null) {
                            notifyDone(failureTitle(currentKind), terminalError)
                        } else if (!was_cancelled && completed_normally && saw_work) {
                            notifyDone(readyTitle(currentKind), readyBody(currentKind))
                        }
                        if (runJob?.isActive != true) {
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
    }

    private suspend fun dispatch(intent: Intent) {
        // The search deep pass rides the build's own tile scan: the scan
        // already decompresses every tile the archive holds for the bucket,
        // so extracting `place`/`poi` rows on the way through costs one
        // extra decode per tile — not a second archive read.
        when (intent.action) {
            ACTION_INDEX_SEARCH -> {
                val anchor = anchorFrom(intent)
                runSearchPass(anchor)
            }
            else -> {
                when (intent.action) {
                    ACTION_BUILD_ALL -> buildAllRegions()
                    else -> {
                        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
                        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                        if (lon.isNaN() || lat.isNaN()) throw IllegalArgumentException("bad location extras in $intent")
                        // A missing id is rejected in onStartCommand; an id
                        // naming no installed region fails the run honestly
                        // (the status file is the caller's only surface).
                        val region_id = intent.getStringExtra(EXTRA_REGION_ID)
                            ?: throw IllegalStateException("no region id in $intent")
                        val region = withContext(Dispatchers.IO) {
                            RegionStore.load(this@GraphBuildService, region_id)
                        } ?: throw IllegalStateException("unknown region id '$region_id' in $intent")
                        val deep_pass = openDeepPass(region)
                        try {
                            currentRegionId = region.id
                            managerFor(region.id).ensureBucketsFor(lon, lat, deep_pass?.sink, ::reportProgress)
                        } finally {
                            // Also on failure/cancellation: an unclosed channel would hang
                            // the drain and an unclosed DB would leak its handle for the
                            // process's lifetime.
                            deep_pass?.finish()
                        }
                    }
                }
            }
        }
        // After a routing build, the search pass follows when ANY region
        // still lacks its index — the service-side serialization of the
        // user's "generate the search index after the routing data" order.
        // A cancelled run drops the chain (the user's cancel is the last
        // word); the next app start re-triggers via init's check.
        chainSearchIndex()
    }

    /** The search anchor carried on the intent, or null when the extras are absent. */
    private fun anchorFrom(intent: Intent): Pair<Double, Double>? {
        val lon = intent.getDoubleExtra(EXTRA_ANCHOR_LON, Double.NaN)
        val lat = intent.getDoubleExtra(EXTRA_ANCHOR_LAT, Double.NaN)
        return if (lon.isNaN() || lat.isNaN()) null else lon to lat
    }

    /**
     * "Prepare all" across every installed region: each region is built
     * through its own manager (its own archive + segment dir), in import
     * order, with the build progress ACCUMULATED across regions so the
     * banner's built/total never resets mid-run.
     */
    private suspend fun buildAllRegions() {
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(this@GraphBuildService) }
        if (regions.isEmpty()) return
        var built_offset = 0
        var total_offset = 0
        for (region in regions) {
            currentCoroutineContext().ensureActive()
            val deep_pass = openDeepPass(region)
            try {
                currentRegionId = region.id
                var region_total = 0
                managerFor(region.id).buildAll(deep_pass?.sink) { progress ->
                    // A region's own total is only known once its build
                    // enumerates the buckets; fold each region's counts on
                    // top of the finished regions' so the banner's built and
                    // total only grow across the run.
                    region_total = progress.total
                    reportProgress(
                        progress.copy(
                            bucket = "${region.displayName}: ${progress.bucket}",
                            built = built_offset + progress.built,
                            total = total_offset + progress.total,
                        ),
                    )
                }
                built_offset += region_total
                total_offset += region_total
            } finally {
                deep_pass?.finish()
            }
        }
    }

    /**
     * Queues the follow-on search-index run when the index is missing and
     * nothing is queued yet. Runs on the main thread (the handler post)
     * like every pendingIntent access.
     */
    private fun chainSearchIndex() {
        if (currentKind != KIND_ROUTING) return
        if (File(File(filesDir, "graph"), SEARCH_DISMISSED_FLAG).isFile) return
        // Chain when ANY region still lacks its complete index — the chain
        // is the search side's "everything is indexed" safety net, so one
        // finished region must not mask a sibling that needs it.
        val regions = RegionStore.loadAll(this)
        if (regions.none { !SearchCoordinator.indexExists(this, it) }) return
        main_handler.post {
            if (runJob?.isActive == true && pendingIntent == null) {
                pendingIntent = Intent(this, GraphBuildService::class.java)
                    .setAction(ACTION_INDEX_SEARCH)
            }
        }
    }

    /**
     * The background search-index pass: the full index build (places then,
     * when the archive carries the address layer, the z14 sweep) through
     * [SearchCoordinator.buildCheapIndex], with the service's progress
     * surface and its flag-based cancel. Completes by writing the
     * completion marker; a cancel leaves a partial DB that the next run
     * resumes.
     *
     * Multi-region: every installed region is indexed — ordered
     * nearest-first around [anchor] (when one exists) so the places near
     * the user become searchable before the far side of the world — and
     * each region's completion marker gates it out of the next run. The
     * anchor is threaded into the indexer so the sweep itself is
     * chunk-ordered nearest-first, and overall progress is composed from
     * the per-region fractions: `(regionIndex + regionFraction) /
     * regionCount`.
     */
    private suspend fun runSearchPass(anchor: Pair<Double, Double>?) {
        val regions = withContext(Dispatchers.IO) { RegionStore.loadAll(this@GraphBuildService) }
        if (regions.isEmpty()) return
        // Nearest-first: squared equirectangular distance from the anchor
        // to the region center — the same normalization RegionStore uses
        // for primaryRegion, so a dateline-straddling anchor stays sane.
        // Without an anchor, import order (nearest-first to nothing is
        // meaningless and import order matches the UI's region list).
        val ordered = if (anchor == null) {
            regions
        } else {
            regions.sortedBy { region ->
                var dlon = (region.centerLon - anchor.first) % 360.0
                if (dlon > 180.0) dlon -= 360.0
                if (dlon < -180.0) dlon += 360.0
                val dlat = region.centerLat - anchor.second
                dlon * dlon + dlat * dlat
            }
        }
        val region_count = ordered.size
        for ((region_index, region) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (SearchCoordinator.indexExists(this, region)) continue
            SearchCoordinator.buildCheapIndex(
                this,
                region,
                anchor,
                onProgress = { label, fraction ->
                    // Compose the overall fraction: the finished regions'
                    // full slices plus this region's own 0..1 progress. A
                    // null per-region fraction (an indeterminate step) stays
                    // null overall rather than regressing the bar.
                    val overall = fraction?.let { f ->
                        (region_index + f) / region_count
                    }
                    reportSearchProgress("${region.displayName}: $label", overall)
                },
                isCancelled = { cancelRequested },
            )
        }
    }

    /**
     * The deep pass for [region]'s run, or null when search has nothing to
     * write into yet (no index DB for the region — a fresh install whose
     * cheap pass has not run, or an archive whose indexes were wiped).
     * When the cheap pass is still running concurrently, a deep row can
     * win a place key first and the cheap row is then IGNOREd on the
     * unique index — one slightly different representative point for that
     * place, not a correctness issue.
     */
    private fun openDeepPass(region: RegionInfo): DeepPass? {
        if (!SearchCoordinator.indexExists(this, region)) return null
        val db = SearchCoordinator.openDatabase(
            this,
            SearchCoordinator.databaseFileFor(this, region),
        )
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
     * One manager PER REGION, cached by region id for the service's
     * lifetime — `ACTION_CANCEL` must reach the same instance that is
     * running, since cancellation is an in-memory flag (the live run's
     * region is tracked in [currentRegionId]). Paths are
     * process-independent (same filesDir/cacheDir): the archive is the
     * region's own `map/<id>/map.pmtiles` and the segments live in
     * `graph/segments/<id>/`, so two regions never share state.
     */
    private fun managerFor(regionId: String): GraphBuildManager {
        managers[regionId]?.let { return it }
        val segments = File(File(File(filesDir, "graph"), "segments"), regionId).apply { mkdirs() }
        val work = File(cacheDir, "graph-work").apply { mkdirs() }
        return GraphBuildManager(
            archiveFile = RegionStore.archiveFile(RegionStore.mapDir(this), regionId),
            segmentsDir = segments,
            workRoot = work,
            assetsDir = File(filesDir, "profiles"),
        ).also { managers[regionId] = it }
    }

    // ---- status + notification plumbing ----

    private fun reportProgress(progress: GraphBuildManager.Progress) {
        if (progress.building) sawBuildWork = true
        writeStatus(BuildSnapshot(
            running = true,
            bucket = progress.bucket,
            built = progress.built,
            total = progress.total,
            error = null,
            label = progress.label,
            fraction = progress.fraction,
            kind = currentKind,
        ))
        updateNotification(progress)
    }

    /** The search pass's progress tick: status file + notification. */
    private fun reportSearchProgress(label: String, fraction: Float?) {
        sawBuildWork = true
        writeStatus(BuildSnapshot(
            running = true,
            bucket = null,
            built = 0,
            total = 0,
            error = null,
            label = label,
            fraction = fraction,
            kind = KIND_SEARCH,
        ))
        updateSearchNotification(label, fraction)
    }

    private fun reportStatus(
        running: Boolean,
        bucket: String?,
        built: Int,
        total: Int,
        error: String?,
        kind: String = currentKind,
    ) {
        writeStatus(BuildSnapshot(running, bucket, built, total, error, kind = kind))
    }

    private data class BuildSnapshot(
        val running: Boolean,
        val bucket: String?,
        val built: Int,
        val total: Int,
        val error: String?,
        /** "routing" or "search" — which background job this status describes. */
        val kind: String = KIND_ROUTING,
        /** The current step within the bucket build, or null at boundaries. */
        val label: String? = null,
        /** 0..1 through [label]'s step, or null while its size is unknown. */
        val fraction: Float? = null,
    )

    /**
     * Atomic-ish: small file, written whole via a unique tmp + rename.
     * The tmp name must be unique per write — the run coroutine and the
     * heartbeat write concurrently, and a shared tmp name could publish a
     * torn file. [statusLock] orders the writers so a heartbeat re-stamp
     * can never regress the file to an older snapshot.
     */
    private fun writeStatus(snapshot: BuildSnapshot) {
        synchronized(statusLock) {
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
                        .put("label", snapshot.label ?: "")
                        .put("fraction", snapshot.fraction?.toDouble() ?: -1.0)
                        .put("kind", snapshot.kind)
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
     *
     * The system dispatches the one-argument overload on API 35 and the
     * two-argument one on API 36+; both funnel into the same teardown.
     * (Graph-build state needs no extra persistence here: the manager's
     * segmentsDir/build-state.json records every completed bucket as it
     * completes, so a timed-out build resumes cleanly from what it had
     * finished and a partial bucket is rebuilt from scratch — never
     * half-installed.)
     */
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        timedOut = true
        // Only the routing run owns a build the manager can cancel; a
        // search run's cancel flag is read directly in the sweep.
        if (currentKind == KIND_ROUTING) currentRegionId?.let { managerFor(it).cancel() }
        if (currentKind == KIND_SEARCH) cancelRequested = true
        scope.cancel()
        reportStatus(running = false, bucket = null, built = 0, total = 0, error = "build timed out")
        if (wakeLock.isHeld) wakeLock.release()
        stopSelf()
    }

    /** The done-notification title for a run of [kind] that failed. */
    private fun failureTitle(kind: String): String =
        if (kind == KIND_SEARCH) "Search index preparation failed" else "Routing data preparation failed"

    /** The done-notification title for a run of [kind] that succeeded. */
    private fun readyTitle(kind: String): String =
        if (kind == KIND_SEARCH) "Search index ready" else "Routing data ready"

    /** The done-notification body for a run of [kind] that succeeded. */
    private fun readyBody(kind: String): String =
        if (kind == KIND_SEARCH) {
            "Search is now available across the whole map."
        } else {
            "Routing in your prepared area is available."
        }

    private fun buildNotification(kind: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (kind == KIND_SEARCH) "Preparing search index" else "Preparing routing data")
            .setContentText("Reading the map archive…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: GraphBuildManager.Progress) {
        // Called from the build's hot loop (reportProgress fires per
        // phase tick): a notification-manager throw here would kill the
        // run over cosmetics. Best effort, logged.
        runCatching {
            // built + 1 = "the bucket now being built" — but only while one
            // actually is; and never past total (the final tick lands after
            // the last bucket's completion).
            val shown = if (progress.building) {
                minOf(progress.built + 1, progress.total)
            } else {
                progress.built
            }
            val text = when {
                progress.label != null -> buildString {
                    append(progress.bucket).append(" — ").append(progress.label)
                    progress.fraction?.let {
                        append(" (").append((it * 100).toInt()).append("%)")
                    }
                }
                else -> "${progress.bucket} ($shown/${progress.total})"
            }
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing routing data")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            // A determinate bar needs a real fraction; the label-only phases
            // (and the pre-scan gap) stay honest with an indeterminate one.
            if (progress.fraction != null) {
                builder.setProgress(100, (progress.fraction * 100).toInt().coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "progress notification update failed", it) }
    }

    /**
     * The search pass's ongoing notification — same channel as routing's,
     * the title the user's order requirement names ("search index"), a
     * determinate bar whenever the sweep reports a real fraction.
     */
    private fun updateSearchNotification(label: String, fraction: Float?) {
        runCatching {
            val text = if (fraction != null) {
                "$label (${(fraction * 100).toInt()}%)"
            } else {
                label
            }
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing search index")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (fraction != null) {
                builder.setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "search progress notification update failed", it) }
    }

    /**
     * The one-shot end-of-build notification. Separate ID and channel from
     * the ongoing progress one: it must survive stopSelf() (which removes
     * the foreground notification) and it is meant to be seen, not tracked.
     * AutoCancel + the launcher's pending intent so a tap opens the app.
     */
    private fun notifyDone(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(DONE_CHANNEL_ID, DONE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        manager.notify(
            NOTIFICATION_DONE_ID,
            NotificationCompat.Builder(this, DONE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
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
        const val ACTION_INDEX_SEARCH = "com.danemadsen.atlas.graph.INDEX_SEARCH"

        /** The status-file/announcement `kind` values: what a run is building. */
        const val KIND_ROUTING = "routing"
        const val KIND_SEARCH = "search"

        /**
         * The search run's cancel tombstone (in `filesDir/graph/`): set
         * service-side when a search run is cancelled, checked by the
         * chain and by [com.danemadsen.atlas.routing.GraphBuildCoordinator]'s
         * auto-trigger — otherwise the next no-op routing trigger would
         * silently re-chain the build the user just cancelled. Cleared by
         * an explicit rebuild or a new archive import.
         */
        const val SEARCH_DISMISSED_FLAG = "search-dismissed.flag"
        const val EXTRA_LON = "lon"
        const val EXTRA_LAT = "lat"

        /**
         * The region a `BUILD_FOR_LOCATION` run must build against — the
         * caller (RouterGateway's on-demand flow, the coordinator's
         * location trigger) resolves the owning region first; the service
         * never guesses.
         */
        const val EXTRA_REGION_ID = "regionId"

        /**
         * The search-index anchor (WP5): the last fix inside any installed
         * region, computed once in the coordinator and threaded through to
         * [SearchCoordinator.buildCheapIndex] so per-region sweeps run
         * nearest-first. Absent extras mean "no anchor".
         */
        const val EXTRA_ANCHOR_LON = "anchorLon"
        const val EXTRA_ANCHOR_LAT = "anchorLat"

        private const val TAG = "GraphBuildService"
        private const val CHANNEL_ID = "graph_build"
        private const val CHANNEL_NAME = "Routing data preparation"
        private const val NOTIFICATION_ID = 2

        // The end-of-build notification: its own channel (the ongoing one is
        // IMPORTANCE_LOW; "it finished" is meant to be noticed) and its own
        // ID, so it outlives stopSelf() dropping the foreground notification.
        // Notification IDs are per-APP, not per-process: NavigationService
        // owns 3 in the main process, so this must not.
        private const val DONE_CHANNEL_ID = "graph_build_done"
        private const val DONE_CHANNEL_NAME = "Routing data ready"
        private const val NOTIFICATION_DONE_ID = 4
        private const val STATUS_FILE = "build-status.json"
        private const val WAKE_LOCK_TAG = "atlas:graph-build"
        private const val WAKE_LOCK_SLICE_MS = 10L * 60 * 1000 // renewed by the heartbeat
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}