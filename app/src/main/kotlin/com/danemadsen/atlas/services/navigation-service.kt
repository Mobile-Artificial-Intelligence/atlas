package com.danemadsen.atlas.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.danemadsen.atlas.location.LocationTracker
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.nav.NavigationProgress
import com.danemadsen.atlas.nav.SoundPlayer
import com.danemadsen.atlas.nav.TtsSpeaker
import com.danemadsen.atlas.nav.turnInstruction
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.RouterGateway
import com.danemadsen.atlas.routing.TurnCommand
import com.danemadsen.atlas.routing.formatDistance
import com.danemadsen.atlas.routing.formatDuration
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
 * It ends itself on arrival or when the coordinator's Stop lands; a
 * restart after process death finds no pending route and stops honestly
 * (a dead process cannot have kept driving).
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var rerouteJob: Job? = null
    private var tts: TtsSpeaker? = null
    private var sounds: SoundPlayer? = null

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

    /** True while the fix stream has been silent past the lost threshold. */
    @Volatile private var signalLost = false

    /** Wall clock of the latest consumed fix; 0 before the first one. */
    private val latest_fix_ms = AtomicLong(0)

    @Volatile private var currentRoute: RouteResult? = null

    @Volatile private var progressEngine: NavigationProgress? = null

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
                startForegroundWith(buildNotification(route))
                if (runJob?.isActive != true) run(route, session)
            }
            ACTION_STOP -> {
                val session = intent.getLongExtra(NavigationCoordinator.EXTRA_SESSION, 0L)
                if (session == 0L || session == runSession) {
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
        return START_NOT_STICKY
    }

    private fun endSession() {
        sessionOver = true
        runJob?.cancel()
        runJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        NavigationCoordinator.publishEnded(runSession)
        runSession = 0L
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
                // A GPS-fix watchdog: the fix loop is the only thing that
                // knows the stream went quiet, and the user needs to hear
                // it more than see it.
                launch { gpsWatchdog(cues) }
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
                        updateNotification(current, arrived = true)
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

                    if (step.events.recalculate && !recalculating) {
                        startReroute(session, fix.point, speaker, cues)
                    }

                    val now = System.currentTimeMillis()
                    if (now - last_notification_at > NOTIFICATION_UPDATE_MS) {
                        last_notification_at = now
                        updateNotification(current, arrived = false)
                    }
                }
            } finally {
                speaker.shutdown()
                cues.release()
            }
        }
    }

    /**
     * Speaks "Recalculating.", plays the missed-turn cue, and swaps the
     * route when the corridor comes back. Runs as its OWN job so the fix
     * collector keeps consuming — every state it could interleave with is
     * guarded by [sessionOver] and the session token.
     */
    private fun startReroute(session: Long, from: GeoPoint, speaker: TtsSpeaker, cues: SoundPlayer) {
        recalculating = true
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
        }
    }

    /** A terminal publish plus the service's own shutdown, token-checked. */
    private fun failSession(session: Long, message: String, route: RouteResult?) {
        sessionOver = true
        NavigationCoordinator.publishRerouteFailed(session, message, route)
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

    private fun notificationText(route: RouteResult, arrived: Boolean): String {
        val nav_state = NavigationCoordinator.navState.value
        val snapshot = (nav_state as? NavigationCoordinator.NavState.Navigating)?.snapshot
        return if (arrived || snapshot == null) {
            "Navigating to your destination"
        } else {
            val remaining = formatDistance(snapshot.remainingMeters.roundToInt())
            val eta = formatDuration(snapshot.remainingSeconds)
            val turn = snapshot.nextTurn
            val turn_text = when {
                turn == null -> ""
                turn.command == TurnCommand.ARRIVE -> " — arrive"
                else -> " — " + turnInstruction(turn.command, turn.streetName)
            }
            "$remaining • $eta$turn_text"
        }
    }

    private fun updateNotification(route: RouteResult, arrived: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notificationBuilder().setContentText(notificationText(route, arrived)).build(),
        )
    }

    private fun buildNotification(route: RouteResult): Notification =
        notificationBuilder()
            .setContentText("Navigating to your destination")
            .build()

    private fun notificationBuilder(): NotificationCompat.Builder {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Atlas navigation")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    override fun onDestroy() {
        tts?.shutdown()
        sounds?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.danemadsen.atlas.nav.START"
        const val ACTION_STOP = "com.danemadsen.atlas.nav.STOP"

        private const val CHANNEL_ID = "navigation"
        private const val CHANNEL_NAME = "Navigation"
        private const val NOTIFICATION_ID = 3
        private const val NOTIFICATION_UPDATE_MS = 5_000L

        /** No fix for this long → GPS_DISCONNECTED. */
        private const val GPS_SIGNAL_LOST_MS = 10_000L

        private const val GPS_SIGNAL_POLL_MS = 2_000L
    }
}