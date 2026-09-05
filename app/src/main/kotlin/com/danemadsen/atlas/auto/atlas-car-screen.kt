package com.danemadsen.atlas.auto

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.nav.NavigationCoordinator.NavState
import com.danemadsen.atlas.routing.formatDistance
import java.time.ZonedDateTime
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * ONE stateless Screen whose onGetTemplate() is a pure projection of
 * NavState — no push/pop stack: navState is the single source of truth
 * and flips fast (reroute swaps the route wholesale, arrival, failure);
 * a screen stack would need constant reconciliation with the host
 * back-stack, a template switch needs none, and host rebinds/process
 * death are trivially correct. The screen never owns state it cannot
 * verify (mirrors the token discipline).
 *
 * car-app 1.7.0 has no step display on NavigationTemplate — turn-by-turn
 * flows through [NavigationManager.updateTrip] instead ([buildTrip]);
 * the template only carries the destination estimate and a status line.
 */
class AtlasCarScreen(private val session: androidx.car.app.Session) : Screen(session.carContext) {

    private val navigation_manager =
        carContext.getCarService(CarContext.NAVIGATION_SERVICE) as NavigationManager

    /** Host callbacks dispatched on main — the collector thread. */
    private val main_executor = Executor { action ->
        Handler(Looper.getMainLooper()).post(action)
    }

    @Volatile private var state: NavState = NavState.Idle
    @Volatile private var trip_started = false

    init {
        // The host can cancel navigation from the car UI (e.g. its own
        // dismiss affordance) — honor it like the phone's Stop action.
        navigation_manager.setNavigationManagerCallback(main_executor,
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    NavigationCoordinator.stop(carContext)
                }
            },
        )
    }

    /** The session's collector calls this on every publish. */
    fun onNavState(next: NavState) {
        state = next
        pushTrip(next)
        invalidate()
    }

    private fun pushTrip(s: NavState) {
        when (s) {
            is NavState.Navigating -> {
                if (!trip_started) {
                    trip_started = true
                    navigation_manager.navigationStarted()
                }
                navigation_manager.updateTrip(buildTrip(s.snapshot, ZonedDateTime.now()))
            }
            else -> if (trip_started) {
                trip_started = false
                navigation_manager.navigationEnded()
            }
        }
    }

    override fun onGetTemplate(): Template = when (val s = state) {
        is NavState.Navigating -> navigationTemplate(s)
        is NavState.Arrived -> terminalTemplate("You have arrived.", "Navigation ended.")
        is NavState.Failed -> terminalTemplate("Navigation ended", s.message)
        NavState.Idle -> MessageTemplate.Builder(
            "Plan a route in Atlas on your phone — when you start " +
                "navigation it takes over this display.",
        )
            .setTitle("Atlas")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun navigationTemplate(s: NavState.Navigating): Template {
        val snapshot = s.snapshot
        // car-app 1.7.0: NavigationInfo is MessageInfo only — headline the
        // same distance line the in-app banner shows.
        val nav_info = when {
            snapshot == null -> MessageInfo.Builder("Waiting for GPS")
                .setText("Searching for a position fix…")
                .build()
            s.recalculating -> MessageInfo.Builder("Recalculating…").build()
            else -> MessageInfo.Builder(
                formatDistance(snapshot.distanceToNextTurnMeters.roundToInt()),
            ).apply { snapshot.nextTurn?.streetName?.let { setText(it) } }.build()
        }
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Stop")
                    .setOnClickListener { NavigationCoordinator.stop(carContext) }
                    .build(),
            )
        if (snapshot != null) {
            actions.addAction(
                Action.Builder().setTitle(if (s.muted) "Unmute" else "Mute")
                    .setOnClickListener { NavigationCoordinator.toggleMute(carContext) }
                    .build(),
            )
        }
        val template = NavigationTemplate.Builder()
            .setNavigationInfo(nav_info)
            .setActionStrip(actions.build())
        snapshot?.let { template.setDestinationTravelEstimate(destinationEstimate(it)) }
        return template.build()
    }

    private fun terminalTemplate(title: String, message: String): Template =
        MessageTemplate.Builder(message)
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .addAction(
                Action.Builder().setTitle("Close")
                    .setOnClickListener { NavigationCoordinator.clearTerminalState() }
                    .build(),
            )
            .build()
}