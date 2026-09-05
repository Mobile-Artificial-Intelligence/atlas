package com.danemadsen.atlas.nav

import android.content.Context
import android.content.Intent
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.services.NavigationService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app-process bus between the navigation runtime and the UI. The
 * [NavigationService] owns the fix loop (it is what Android keeps alive
 * behind the foreground notification), while the ViewModel reads
 * [navState] and the map renders the line from [NavState.Navigating]'s
 * current route — a re-route replaces the route there, so the map and
 * the drawer never diverge about which route is being driven.
 *
 * Every publish carries a SESSION TOKEN: start() mints one, stop() and
 * the terminal publishes (arrival, re-route failure) retire it, and any
 * publish with a stale token is dropped. Without it, an in-flight fix
 * handler or a queued stale Stop could publish over a newer session's
 * state (or over the terminal state it just helped produce) — the fix
 * loop runs on a worker thread, so "this handler already ended" can
 * never be checked atomically any other way.
 */
object NavigationCoordinator {

    sealed interface NavState {
        data object Idle : NavState

        /**
         * @param result the route currently being driven — the re-route
         * path replaces it wholesale.
         * @param snapshot the latest per-fix progress (null before the
         * first fix lands).
         */
        data class Navigating(
            val result: RouteResult,
            val snapshot: NavigationProgress.Snapshot?,
            val muted: Boolean,
            val ttsAvailable: Boolean,
            val recalculating: Boolean,
        ) : NavState

        /** Route progress exhausted within [NavigationProgress.ARRIVAL_METERS]. */
        data class Arrived(val result: RouteResult) : NavState

        /**
         * A re-route failed (the road network genuinely cannot continue):
         * navigation is over, the old route stays on the map, and the
         * panel offers Close. [result] carries that route so the map can
         * re-render it after a theme restyle rebuilds every layer.
         */
        data class Failed(val message: String, val result: RouteResult? = null) : NavState
    }

    private val _navState = MutableStateFlow<NavState>(NavState.Idle)
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    /**
     * The route to navigate, handed to the service before its start
     * intent — an in-process handoff, exactly like the intent itself:
     * a service restart after process death finds this null and stops,
     * which is the honest state (a dead process cannot keep driving).
     */
    @Volatile private var pendingRoute: RouteResult? = null

    /** True while [start] has armed a route the service has not consumed. */
    @Volatile private var startArmed = false

    private val session_counter = AtomicLong(0)

    /** The token of the live session; 0 when no session is active. */
    @Volatile private var active_session = 0L

    /** The intent extra every start/stop intent carries. */
    const val EXTRA_SESSION = "atlas.nav.session"

    val sessionId: Long get() = active_session

    /** True when a [start] is armed that the service has not consumed yet. */
    fun hasArmedStart(): Boolean = startArmed

    /**
     * Whether the floating turn banner should be shown for the live
     * session. A parallel StateFlow next to the mute plumbing, and for the
     * same reason: the service stays persistence-free — the UI owns the
     * pref, the coordinator is the bus the live change rides.
     */
    private val _overlayRequested = MutableStateFlow(false)
    val overlayRequested: StateFlow<Boolean> = _overlayRequested.asStateFlow()

    fun setOverlayRequested(enabled: Boolean) {
        _overlayRequested.value = enabled
    }

    fun start(context: Context, route: RouteResult, muted: Boolean = false, overlayEnabled: Boolean = false) {
        if (_navState.value is NavState.Navigating || _navState.value is NavState.Arrived) return
        pendingRoute = route
        startArmed = true
        val session = session_counter.incrementAndGet()
        active_session = session
        _overlayRequested.value = overlayEnabled
        _navState.value = NavState.Navigating(
            result = route,
            snapshot = null,
            // The persisted Settings mute seeds the session — voice
            // guidance must honor it before the first fix can speak.
            muted = muted,
            ttsAvailable = true,
            recalculating = false,
        )
        context.startForegroundService(
            Intent(context, NavigationService::class.java)
                .setAction(NavigationService.ACTION_START)
                .putExtra(EXTRA_SESSION, session),
        )
    }

    /**
     * User Stop / Close: ends navigation and the service, returns to Idle.
     * The stop intent carries the session being stopped; a session that
     * already ended itself (arrival, re-route failure) has already
     * retired its token, and 0 travels with the intent — the service
     * treats "stop whatever remains of that" the same either way.
     */
    fun stop(context: Context) {
        pendingRoute = null
        startArmed = false
        val stopping = active_session
        active_session = 0L
        _navState.value = NavState.Idle
        context.startService(
            Intent(context, NavigationService::class.java)
                .setAction(NavigationService.ACTION_STOP)
                .putExtra(EXTRA_SESSION, stopping),
        )
    }

    /**
     * One mute path for three callers (phone panel, shade action, Android
     * Auto action): flips the live session's mute and persists it, so the
     * state the UI mirrors, what the speaker reads, and the NEXT session's
     * seed all agree — a shade mute survives process death like a panel
     * mute always did.
     */
    fun toggleMute(context: Context) {
        val current = _navState.value as? NavState.Navigating ?: return
        val muted = !current.muted
        _navState.value = current.copy(muted = muted)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TTS_MUTED, muted).apply()
    }

    /** The SharedPreferences file all Atlas settings live in. */
    const val PREFS_NAME = "atlas-settings"

    /** The voice-guidance mute's key, shared by every writer. */
    const val KEY_TTS_MUTED = "tts.muted"

    /** The service's consume-once start payload; null for a stale restart. */
    fun takePendingRoute(): RouteResult? {
        if (!startArmed) return null
        startArmed = false
        return pendingRoute
    }

    /**
     * Non-service consumers (the Android Auto screen) closing a terminal
     * state the service already retired: arrival and re-route failure
     * reset the token themselves, so no token check and no service intent
     * here — purely a state flip. No-op for non-terminal states.
     */
    fun clearTerminalState() {
        if (_navState.value is NavState.Arrived || _navState.value is NavState.Failed) {
            _navState.value = NavState.Idle
        }
    }

    // ---- service-side publishers (single writer: the fix loop) ----
    // Every publish is token-checked: a publish from a session that has
    // been stopped, superseded, or already terminated lands on nothing.

    fun publishProgress(
        session: Long,
        result: RouteResult,
        snapshot: NavigationProgress.Snapshot,
        muted: Boolean,
        ttsAvailable: Boolean,
        recalculating: Boolean,
    ) {
        if (session != active_session) return
        _navState.value = NavState.Navigating(result, snapshot, muted, ttsAvailable, recalculating)
    }

    fun publishArrived(session: Long, result: RouteResult) {
        if (session != active_session) return
        active_session = 0L
        _navState.value = NavState.Arrived(result)
    }

    fun publishRerouteFailed(session: Long, message: String, result: RouteResult? = null) {
        if (session != active_session) return
        active_session = 0L
        _navState.value = NavState.Failed(message, result)
    }

    /**
     * The service's stop path. [session] 0 ("no token — nothing else to
     * match") is honored: it is what arrives when stopping leftovers of a
     * self-terminated session, or when stopping from Idle.
     */
    fun publishEnded(session: Long) {
        if (session != 0L && session != active_session) return
        active_session = 0L
        _navState.value = NavState.Idle
    }
}