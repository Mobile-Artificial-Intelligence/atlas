package com.danemadsen.atlas.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.Screen
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.danemadsen.atlas.nav.NavigationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Intent

/**
 * The Android Auto service — MAIN process (never :graph), consuming the
 * process-singleton bus directly: [NavigationCoordinator] is the same
 * object the phone UI's ViewModel reads, so no IPC.
 *
 * TODO(release): swap ALLOW_ALL_HOSTS_VALIDATOR for the known-host
 * allowlist (com.google.android.projection.gearhead + AAOS hosts) before
 * Play — allow-all lets any signed app project Atlas. Fine for sideload.
 */
class AtlasCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = AtlasCarSession()
}

/**
 * Collects [NavigationCoordinator.navState] on the main thread while the
 * host is connected (repeatOnLifecycle(STARTED)); the process keeps
 * navigating (the FGS owns that) with the car session dormant. The screen
 * is created in onCreateScreen — [Session.carContext] is only wired by
 * then, never at construction time.
 */
class AtlasCarSession : Session() {

    private var screen: AtlasCarScreen? = null

    override fun onCreateScreen(intent: Intent): Screen =
        screen ?: AtlasCarScreen(this).also { screen = it }

    init {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationCoordinator.navState.collect { state ->
                    screen?.onNavState(state)
                }
            }
        }
    }
}