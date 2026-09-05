package com.danemadsen.atlas.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.danemadsen.atlas.location.LocationTracker
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.nav.NavigationProgress
import com.danemadsen.atlas.nav.SoundPlayer
import com.danemadsen.atlas.nav.TtsSpeaker
import com.danemadsen.atlas.nav.metersBetween
import com.danemadsen.atlas.nav.navigationStatusText
import com.danemadsen.atlas.nav.routeProgressFraction
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.RouterGateway
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The navigation runtime: a `location`-type foreground service in the
 * MAIN process (unlike the `:graph` builder, navigation's UI-facing
 * state must survive in-process, and its heap footprint is tiny). It
 * owns the GPS collection loop, the [NavigationProgress] engine, the
 * [TtsSpeaker], the [SoundPlayer] cues, and the off-route re-route,
 * publishing everything through [NavigationCoordinator].
 *
 * The re-route deliberately does NOT run inside the fix collector: a
 * corridor build can take minutes, and a blocked collector drops fixes
 * (the newest one matters — it is the position). It runs as its own
 * job; the collector keeps consuming fixes against the old route and
 * every publish it makes is session-token-checked, so the states can
 * never interleave wrongly even across threads.
 *
 * It ends itself on arrival or when the coordinator's Stop lands;
 * START_NOT_STICKY plus the in-process pendingRoute handoff means a
 * restart after process death finds no pending route and stops honestly
 * (a dead process cannot have kept driving).
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var rerouteJob: Job? = null
    private var tts: TtsSpeaker? = null
    private var sounds: SoundPlayer? = null

    /** The floating turn banner's controller; null until first needed. */
    private var overlay: NavOverlayController? = null

    /**
     * The session's partial wake lock — acquired per-session in [run]
     * (never on the stale/no-GPS paths), re-armed by [gpsWatchdog] each
     * tick, released in [endSession]/[onDestroy].
     */
    private lateinit var wakeLock: PowerManager.WakeLock

    /**
     * The session this service instance is driving — matched against the
     * token on every intent and publish. Stale intents (a Stop queued
     * before a newer Start, a Start whose session was stopped before
     * delivery) must never touch the live one.
     */
    @Volatile private var runSession = 0L

    /**
     * Set the moment the session is over (arrival, re-route failure,
     * user Stop). The fix collector and the re-route job check it around
     * every publish: `return@collect` alone only abandons one emission,
     * the collector keeps receiving fixes, and cooperative cancellation
     * lets an in-flight handler run to completion.
     */
    @Volatile private var sessionOver = false

    /** True while a re-route job is in flight; blocks re-triggering. */
    @Volatile private var recalculating = false

    /**
     * When the last re-route STARTED, and from where. A re-route from a
     * position the user hasn't left produces the same route again — and
     * GPS drift at a standstill (indoors, a parking spot off the road) can
     * read >40 m off forever, which used to re-fire the missed-turn cue
     * every third fix. The next re-route waits until the fix has moved
     * [REROUTE_MIN_DISPLACEMENT_METERS] from the last origin AND
     * [REROUTE_COOLDOWN_MS] has passed; the first re-route is always live.
     */
    @Volatile private var last_reroute_at_ms = 0L
    @Volatile private var last_reroute_origin: GeoPoint? = null

    /** True while the fix stream has been silent past the lost threshold. */
    @Volatile private var signalLost = false

    /** Wall clock of the latest consumed fix; 0 before the first one. */
    private val latest_fix_ms = AtomicLong(0)

    @Volatile private var currentRoute: RouteResult? = null

    @Volatile private var progressEngine: NavigationProgress? = null

    override fun onCreate() {
        super.onCreate()
        // Non-refcounted: acquire/release sites are unpaired by design
        // (watchdog re-arms), so counting would only hide a leak.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        // Channel creation hoisted out of the builder: it used to recreate
        // the channel on every 5 s notify — a wasteful no-op at fix rate
        // for a whole session.
        createChannel()
        createDoneChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val session = intent.getLongExtra(NavigationCoordinator.EXTRA_SESSION, 0L)
                if (session != NavigationCoordinator.sessionId) {
                    // Stale start: its session was stopped (or superseded)
                    // before this intent was delivered. It must not steal
                    // the pending route a newer start may have armed.
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                val route = NavigationCoordinator.takePendingRoute()
                if (route == null) {
                    // A START_STICKY restart or a racing Stop: nothing to
                    // drive. Do not call startForeground with a live
                    // notification for a route that does not exist.
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                sessionOver = false
                runSession = session
                // A previous session's transient ended-notification must
                // not survive the new session taking the shade over.
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_DONE_ID)
                // The coordinator seeded the session's mute from the saved
                // preference; the first notification must honor it.
                val nav_state = NavigationCoordinator.navState.value as? NavigationCoordinator.NavState.Navigating
                startForegroundWith(
                    renderNotification(
                        route = route,
                        snapshot = null,
                        arrived = false,
                        muted = nav_state?.muted ?: false,
                        recalculating = false,
                    ),
                )
                // The banner rides the session start: show it only when
                // the pref (handed through start()) asked for it, and only
                // if the appop is still granted.
                if (NavigationCoordinator.overlayRequested.value) {
                    overlay = overlay ?: NavOverlayController(this)
                    overlay?.show()
                }
                if (runJob?.isActive != true) run(route, session)
            }
            ACTION_TOGGLE_MUTE -> {
                // Only meaningful mid-session: the shade action only exists
                // while the ongoing notification does, toggleMute no-ops on
                // non-Navigating, and re-posting the ongoing notification
                // with no foreground state behind it would be a lie.
                if (runJob?.isActive == true) {
                    // One mute path for every surface (panel, shade action,
                    // car): the coordinator flips the live session and
                    // persists the preference.
                    NavigationCoordinator.toggleMute(this)
                    // Flip the action's own label immediately — the next 5 s
                    // tick would otherwise leave the old verb in the shade.
                    currentRoute?.let { updateNotification(it, snapshot = null, arrived = false) }
                }
            }
            ACTION_STOP -> {
                val session = intent.getLongExtra(NavigationCoordinator.EXTRA_SESSION, 0L)
                if (session == 0L && runJob?.isActive != true && NavigationCoordinator.hasArmedStart()) {
                    // A leftover session-0 stop (the shade action after the
                    // session already ended itself) racing a newer start:
                    // the START intent is next in the queue and must find
                    // the process alive with its armed state intact —
                    // endSession() would publish Ended over it.
                } else if (session == 0L || session == runSession) {
                    // 0 = the session already ended itself (arrival or
                    // re-route failure retired the token) or stopping
                    // from Idle: stop whatever is left.
                    endSession()
                } else if (runJob?.isActive != true && !NavigationCoordinator.hasArmedStart()) {
                    // A stale stop for a dead session while nothing runs —
                    // but never stop the service with a newer start armed:
                    // its START intent is next in the queue and must find
                    // the process alive.
                    stopSelf()
                }
            }
        }
        // START_NOT_STICKY is a decision, not a default. After a process
        // death the system does NOT redeliver the START intent, and even
        // if it restarted the service, NavigationCoordinator.pendingRoute
        // lived in the dead heap — takePendingRoute() returns null and the
        // stopSelfResult above runs. The honest state is "nothing driving":
        // AtlasViewModel.init re-routes from the persisted destination and
        // re-offers the trip as a PREVIEW (never a resumed navigation), so
        // no intent redelivery can resurrect a session the process already
        // lost. Sticky restart would only manufacture a zombie service with
        // no route to announce.
        return START_NOT_STICKY
    }

    private fun endSession() {
        sessionOver = true
        if (wakeLock.isHeld) wakeLock.release()
        runJob?.cancel()
        runJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        NavigationCoordinator.publishEnded(runSession)
        runSession = 0L
        overlay?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun run(route: RouteResult, session: Long) {
        val speaker = TtsSpeaker(this)
        tts = speaker
        val cues = SoundPlayer(this)
        sounds = cues
        currentRoute = route
        progressEngine = NavigationProgress(route)
        recalculating = false
        last_reroute_at_ms = 0L
        last_reroute_origin = null
        signalLost = false
        latest_fix_ms.set(0)

        runJob = scope.launch {
            try {
                val has_provider = getSystemService(LocationManager::class.java)
                    ?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true
                if (!has_provider) {
                    failSession(
                        session,
                        "this device has no GPS provider",
                        route,
                    )
                    return@launch
                }
                // The screen is usually off in a pocket or mount — the
                // exact posture this app exists for. Doze and OEM managers
                // can duty-cycle GPS_PROVIDER callbacks even behind a
                // location FGS; the progress engine, off-route detection
                // and the watchdog all assume ~1 Hz fixes, and their
                // failure mode is silent. Cost is bounded to the session
                // (see endSession/onDestroy).
                wakeLock.acquire(NAV_WAKE_LOCK_SLICE_MS)
                // A GPS-fix watchdog: the fix loop is the only thing that
                // knows the stream went quiet, and the user needs to hear
                // it more than see it.
                launch { gpsWatchdog(cues) }
                // Mid-navigation toggle: the Settings switch (or a later
                // coordinator write) drives the banner live, exactly like
                // the mute. Construct lazily too — a session started with
                // the pref off must still be able to enable mid-trip. The
                // controller's attach() re-guards the appop every time, so
                // a revoked permission just degrades to "no overlay".
                // show/hide are idempotent.
                launch {
                    NavigationCoordinator.overlayRequested.collect { enabled ->
                        val controller = overlay ?: NavOverlayController(this@NavigationService)
                            .also { overlay = it }
                        if (enabled) controller.show() else controller.hide()
                    }
                }
                var last_notification_at = 0L
                // conflate: while a fix is being processed the stream can
                // produce more; only the newest matters (it is the
                // position, not an event).
                LocationTracker.fixes(this@NavigationService).conflate().collect { fix ->
                    if (sessionOver) return@collect

                    if (signalLost) {
                        signalLost = false
                        cues.play(SoundPlayer.Sound.GPS_CONNECTED)
                    }
                    latest_fix_ms.set(System.currentTimeMillis())

                    val engine = progressEngine ?: return@collect
                    val current = currentRoute ?: return@collect
                    val step = engine.update(fix.point, fix.bearing)
                    for (announcement in step.events.announcements) {
                        speaker.speak(announcement)
                    }
                    for (turn in step.events.turnsConsumed) {
                        cues.play(SoundPlayer.Sound.TURN_NOW)
                    }

                    if (step.events.arrived) {
                        sessionOver = true
                        NavigationCoordinator.publishArrived(session, current)
                        // The ongoing notification dies with the service;
                        // this transient one is what the shade still shows
                        // afterwards.
                        notifyNavEnded("Arrived at your destination")
                        stopSelf()
                        return@collect
                    }

                    if (sessionOver) return@collect
                    val coordinator_state =
                        NavigationCoordinator.navState.value as? NavigationCoordinator.NavState.Navigating
                    val muted = coordinator_state?.muted ?: false
                    speaker.muted = muted
                    NavigationCoordinator.publishProgress(
                        session = session,
                        result = current,
                        snapshot = step.snapshot,
                        muted = muted,
                        ttsAvailable = speaker.available,
                        recalculating = recalculating,
                    )
                    // The banner renders exactly what was published —
                    // reading the coordinator back keeps the overlay, the
                    // notification, and the banner one-render-stale
                    // consistently.
                    overlay?.update(NavigationCoordinator.navState.value)

                    if (step.events.recalculate && !recalculating && mayReroute(fix.point)) {
                        startReroute(session, fix.point, speaker, cues)
                    }

                    val now = System.currentTimeMillis()
                    if (now - last_notification_at > NOTIFICATION_UPDATE_MS) {
                        last_notification_at = now
                        // The explicit step.snapshot, not the coordinator's
                        // published copy — the publish may be one fix stale
                        // and the shade should match the banner exactly.
                        updateNotification(current, step.snapshot, arrived = false)
                    }
                }
            } finally {
                speaker.shutdown()
                cues.release()
            }
        }
    }

    /**
     * True when a new re-route is worth firing from [fix]: never while one
     * is in flight ([recalculating]), and after one, only once the user has
     * actually left its origin — re-routing the same spot can only produce
     * the same route again, and stationary GPS drift must not loop the
     * missed-turn cue. See [last_reroute_origin].
     */
    private fun mayReroute(fix: GeoPoint): Boolean {
        val origin = last_reroute_origin ?: return true
        val moved = metersBetween(origin, fix)
        if (moved < REROUTE_MIN_DISPLACEMENT_METERS) return false
        // elapsedRealtime, not the wall clock: this gate is the only thing
        // standing between a stationary GPS cloud and the missed-turn loop,
        // and an NTP correction or manual time change must not be able to
        // open (or hold) it.
        return SystemClock.elapsedRealtime() - last_reroute_at_ms >= REROUTE_COOLDOWN_MS
    }

    /**
     * Speaks "Recalculating.", plays the missed-turn cue, and swaps the
     * route when the corridor comes back. Runs as its OWN job so the fix
     * collector keeps consuming — every state it could interleave with is
     * guarded by [sessionOver] and the session token.
     */
    private fun startReroute(session: Long, from: GeoPoint, speaker: TtsSpeaker, cues: SoundPlayer) {
        recalculating = true
        last_reroute_at_ms = SystemClock.elapsedRealtime()
        last_reroute_origin = from
        // The one log the service emits: a user-visible cue (missed-turn
        // sound, "Recalculating.") with nothing in logcat was undiagnosable
        // when the cue looped at a standstill. Counts are how the reroute
        // gate is verified — one fire per real departure, not one per fix.
        android.util.Log.i(
            "NavigationService",
            "off-route reroute from (${from.lon}, ${from.lat}), session=$session",
        )
        speaker.speak("Recalculating.")
        cues.play(SoundPlayer.Sound.TURN_MISSED)
        val previous_route = currentRoute
        rerouteJob = scope.launch {
            val destination = previous_route?.destination ?: return@launch
            val rerouted = try {
                RouterGateway.route(
                    context = this@NavigationService,
                    profile = previous_route.profile,
                    origin = from,
                    destination = destination,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (sessionOver || !isActive) return@launch
            if (rerouted == null) {
                // A failure is terminal for the session — the old route's
                // progress is stale.
                failSession(
                    session,
                    "the route could not be recalculated — navigation ended",
                    previous_route,
                )
                return@launch
            }
            currentRoute = rerouted
            progressEngine = NavigationProgress(rerouted)
            recalculating = false
            // Refresh the shade right away: it was showing "Recalculating
            // route…" and the next fix could be seconds away.
            updateNotification(rerouted, snapshot = null, arrived = false)
        }
    }

    /** A terminal publish plus the service's own shutdown, token-checked. */
    private fun failSession(session: Long, message: String, route: RouteResult?) {
        sessionOver = true
        NavigationCoordinator.publishRerouteFailed(session, message, route)
        // The ongoing notification dies with the service; leave the reason
        // behind in the shade on the transient channel instead.
        notifyNavEnded("Navigation ended", message)
        stopSelf()
    }

    /**
     * Fires the GPS_DISCONNECTED cue once the stream has been silent
     * past the lost threshold; the collector clears the flag (and plays
     * GPS_CONNECTED) on the next fix that lands.
     */
    private suspend fun gpsWatchdog(cues: SoundPlayer) {
        while (true) {
            delay(GPS_SIGNAL_POLL_MS)
            // Re-arm the wake lock each tick: re-acquiring while held
            // re-arms the system timeout (the GraphBuildService heartbeat
            // trick), and this loop runs whether or not fixes are arriving
            // — so a signal outage (tunnel) can never let the slice expire
            // and hand the fix recovery to doze, where it might never wake
            // up to re-arm itself. Non-refcounted, so this is safe.
            wakeLock.acquire(NAV_WAKE_LOCK_SLICE_MS)
            val last = latest_fix_ms.get()
            if (last == 0L) continue // no first fix yet — the banner says so
            if (!signalLost && System.currentTimeMillis() - last > GPS_SIGNAL_LOST_MS) {
                signalLost = true
                cues.play(SoundPlayer.Sound.GPS_DISCONNECTED)
            }
        }
    }

    // ---- notification plumbing ----

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(route: RouteResult, snapshot: NavigationProgress.Snapshot?, arrived: Boolean) {
        val nav_state = NavigationCoordinator.navState.value as? NavigationCoordinator.NavState.Navigating
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            renderNotification(
                route = route,
                snapshot = snapshot ?: nav_state?.snapshot,
                arrived = arrived,
                muted = nav_state?.muted ?: false,
                recalculating = recalculating,
            ),
        )
    }

    /**
     * The one renderer for the ongoing notification — build and every 5 s
     * update go through it, so the shade can never drift from the banner:
     * status line from [navigationStatusText], progress bar from
     * [routeProgressFraction] (indeterminate until the first fix), and the
     * Mute/Stop actions whose labels mirror the live state.
     */
    private fun renderNotification(
        route: RouteResult,
        snapshot: NavigationProgress.Snapshot?,
        arrived: Boolean,
        muted: Boolean,
        recalculating: Boolean,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Atlas navigation")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentText(navigationStatusText(snapshot, arrived, recalculating))
        contentIntent()?.let { builder.setContentIntent(it) }
        val fraction = routeProgressFraction(route, snapshot)
        if (fraction != null) {
            builder.setProgress(100, (fraction * 100).roundToInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        builder.addAction(0, if (muted) "Unmute" else "Mute", serviceAction(ACTION_TOGGLE_MUTE, REQ_MUTE))
        builder.addAction(0, "Stop", serviceAction(ACTION_STOP, REQ_STOP))
        return builder.build()
    }

    /**
     * PendingIntent.getService caches per filterEquals — which IGNORES
     * extras — so a minted action PendingIntent is reused for every later
     * intent that matches its action. Every action therefore carries
     * session 0L, ALWAYS: minting with the live session's token would
     * freeze that token into the cache, and every tap for the rest of the
     * app's life would carry a stale session the service must drop.
     */
    private fun serviceAction(action: String, request_code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            request_code,
            Intent(this, NavigationService::class.java)
                .setAction(action)
                .putExtra(NavigationCoordinator.EXTRA_SESSION, 0L),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, REQ_CONTENT, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The transient "navigation ended" notification — arrival or a
     * re-route failure posts the reason here before stopping, because the
     * ongoing notification is removed with the foreground state.
     */
    private fun notifyNavEnded(title: String, message: String? = null) {
        val builder = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setAutoCancel(true)
        message?.let { builder.setContentText(it) }
        contentIntent()?.let { builder.setContentIntent(it) }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_DONE_ID, builder.build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun createDoneChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(DONE_CHANNEL_ID, DONE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        sounds?.release()
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        overlay?.destroy()
        // Belt and braces: the foreground notification normally goes with
        // the service, but any out-of-order destroy path must never leave
        // the ongoing one behind. The transient ended notification
        // (NOTIFICATION_DONE_ID) is deliberately NOT cancelled here.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.danemadsen.atlas.nav.START"
        const val ACTION_STOP = "com.danemadsen.atlas.nav.STOP"
        const val ACTION_TOGGLE_MUTE = "com.danemadsen.atlas.nav.TOGGLE_MUTE"

        private const val CHANNEL_ID = "navigation"
        private const val CHANNEL_NAME = "Navigation"
        private const val DONE_CHANNEL_ID = "navigation_events"
        private const val DONE_CHANNEL_NAME = "Navigation events"
        private const val NOTIFICATION_ID = 3

        /** The transient arrival/failure notification's ID — see [notifyNavEnded]. */
        private const val NOTIFICATION_DONE_ID = 5

        private const val REQ_CONTENT = 1
        private const val REQ_MUTE = 2
        private const val REQ_STOP = 3

        private const val NOTIFICATION_UPDATE_MS = 5_000L

        /** No fix for this long → GPS_DISCONNECTED. */
        private const val GPS_SIGNAL_LOST_MS = 10_000L

        private const val GPS_SIGNAL_POLL_MS = 2_000L

        /**
         * A follow-up re-route needs this much fix displacement from the
         * last re-route's origin — see [mayReroute]. 25 m is beyond jitter
         * at a standstill but a few seconds of real driving.
         */
        private const val REROUTE_MIN_DISPLACEMENT_METERS = 25.0

        /** And at least this much time since the last re-route started. */
        private const val REROUTE_COOLDOWN_MS = 10_000L

        /** The session wake lock's tag — matched in `adb shell dumpsys power`. */
        private const val WAKE_LOCK_TAG = "atlas:navigation"

        /**
         * The slice is re-armed by gpsWatchdog every GPS_SIGNAL_POLL_MS, so
         * a session longer than the slice never sees it expire — the slice
         * only bounds the lock if the service dies mid-session.
         */
        private const val NAV_WAKE_LOCK_SLICE_MS = 10L * 60 * 1000
    }
}