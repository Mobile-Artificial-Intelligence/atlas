package com.danemadsen.atlas.ui.graph

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.danemadsen.atlas.routing.GraphBuildCoordinator
import com.danemadsen.atlas.ui.AtlasUiState
import com.danemadsen.atlas.ui.RouteUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The location-triggered graph preparation surface. Shown once a map is
 * ready:
 *
 * - asks for location permission once (bundled with the notification
 *   permission the build's foreground service wants);
 * - with permission granted, immediately prepares the routing graph for
 *   the user's area — with it denied, nothing is ever scheduled (the
 *   explicit product rule);
 * - a resume with permission granted but no status file retries the
 *   trigger — the first attempt may have produced no build because no
 *   fresh fix was available yet, and a permission granted later in system
 *   Settings lands here on return;
 * - while the `:graph` service runs, a bottom banner tracks bucket
 *   progress (first builds of a metro region take minutes — surfacing
 *   that is the honest-UX requirement, not a nice-to-have).
 *
 * While the route drawer is in [RouteUiState.Preparing], the banner is
 * suppressed: the route's own "Preparing route" panel names the very
 * bucket this banner would describe, and the two stacked panels report
 * the same build twice with contradictory progress bars. The banner
 * returns as soon as the route leaves Preparing (including via its Close
 * button — the still-running build then gets this banner with its Cancel).
 * Only the banner is suppressed; the permission/trigger logic always runs.
 */
@Composable
fun GraphPrepFlow(state: AtlasUiState, routeState: RouteUiState) {
    if (state !is AtlasUiState.MapReady) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked_permission by rememberSaveable { mutableStateOf(false) }
    // Set by the permission-dialog callback below: its ActivityResult
    // lands before the resume it precedes, so the resume hook must skip
    // exactly that one resume — it fired the same build itself.
    var skip_next_resume by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val located = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Notification denial only silences the progress notification; the
        // build itself runs regardless.
        if (located) {
            // The ActivityResult is delivered BEFORE the resume that
            // follows it, and the resume hook below has no status file to
            // check yet (the service has not written anything) — without
            // this flag it would queue a second build of the same bucket
            // in the service's pending slot.
            skip_next_resume = true
            scope.launch { GraphBuildCoordinator.triggerLocalBuild(context) }
        }
    }

    LaunchedEffect(state.archive) {
        // A build the user explicitly dismissed must not restart behind
        // their back; the tombstone is only cleared by a new archive
        // import.
        if (GraphBuildCoordinator.isBuildDismissed(context)) return@LaunchedEffect
        if (GraphBuildCoordinator.hasLocationPermission(context)) {
            GraphBuildCoordinator.triggerLocalBuild(context)
        } else if (!asked_permission) {
            asked_permission = true
            val wanted = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(wanted.toTypedArray())
        }
    }

    // The archive-keyed effect above only fires on archive changes: without
    // this resume hook, a permission granted later in system Settings (or a
    // first trigger that found no fix) would never retry. Runs when the
    // status file says nothing is in flight, so it is idempotent — except
    // the FIRST resume: the archive-keyed effect fires for that same
    // composition, and two triggerLocalBuild calls would queue a redundant
    // second build in the service's pending-intent slot (minutes of wasted
    // :graph CPU), so the first resume is skipped.
    // Plain remember, NOT rememberSaveable: a saveable flag resets to
    // false on every activity recreation, which would re-arm the skip on
    // a composition that already used it — the duplicate-build guard
    // would only ever work for the very first composition.
    var first_resume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        when {
            first_resume -> first_resume = false
            skip_next_resume -> skip_next_resume = false
            GraphBuildCoordinator.hasLocationPermission(context) &&
                !GraphBuildCoordinator.isBuildDismissed(context) -> {
                scope.launch {
                    if (GraphBuildCoordinator.readStatusAsync(context) == null) {
                        GraphBuildCoordinator.triggerLocalBuild(context)
                    }
                }
            }
        }
        onPauseOrDispose { }
    }

    if (routeState !is RouteUiState.Preparing) {
        BuildStatusBanner()
    }
}

/** Polls the `:graph` service's status file while it is alive. */
@Composable
private fun BuildStatusBanner() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<GraphBuildCoordinator.BuildStatus?>(null) }

    // The wall-clock sample for the staleness check. A dead :graph process
    // freezes the status file, so consecutive reads return a structurally
    // EQUAL BuildStatus — and structurally-equal writes to `status` do not
    // invalidate this composable, so a staleness condition that only
    // becomes true AFTER the process dies would otherwise never be
    // re-evaluated while visible. This tick changes on every poll and is
    // what keeps the interrupted branch reachable.
    var now_ms by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            status = GraphBuildCoordinator.readStatusAsync(context)
            now_ms = System.currentTimeMillis()
            delay(POLL_MS)
        }
    }

    val running = status?.running == true
    // A running status older than the staleness budget means the :graph
    // process died mid-build (its designed failure mode): show it as an
    // interrupted build, in the error style, with a way out.
    val interrupted = running && now_ms > 0L && now_ms - (status?.timestampMs ?: 0L) > STALE_MS
    val error = status?.error
    if (!running && error == null) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (error != null || interrupted) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    when {
                        interrupted -> {
                            Text("Routing data preparation stopped", style = MaterialTheme.typography.titleSmall)
                            Text("The build process was interrupted.", style = MaterialTheme.typography.bodySmall)
                        }
                        error != null -> {
                            Text("Routing data preparation failed", style = MaterialTheme.typography.titleSmall)
                            Text(error, style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            val s = status
                            Text("Preparing routing data", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (s != null && s.total > 0) {
                                    "${s.bucket ?: ""} (${s.built + 1}/${s.total})"
                                } else {
                                    "reading the map archive…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                when {
                    running && !interrupted -> {
                        TextButton(onClick = { GraphBuildCoordinator.cancel(context) }) {
                            Text("Cancel")
                        }
                    }
                    else -> {
                        TextButton(onClick = {
                            // The tombstone stops the resume hook from
                            // auto-restarting this build; clearing the
                            // status file alone would re-arm it.
                            GraphBuildCoordinator.setBuildDismissed(context, true)
                            GraphBuildCoordinator.clearStatus(context)
                            status = null
                        }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
            if (running && !interrupted) {
                val s = status
                LinearProgressIndicator(
                    progress = {
                        if (s != null && s.total > 0) (s.built + 1f) / s.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

private const val POLL_MS = 2_000L
private const val STALE_MS = 90_000L