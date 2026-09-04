package com.danemadsen.atlas.ui.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.formatDistance
import com.danemadsen.atlas.routing.formatDuration
import com.danemadsen.atlas.ui.RouteUiState

/**
 * The bottom drawer for a route request: the preparing state (which may
 * hide a multi-minute first-build of the region's bucket), then the
 * calculated route's summary with the profile picker. Long-press the map
 * to set a destination; Close drops the route from map and drawer.
 *
 * The Start button arrives with M8's navigation mode — the drawer shows
 * only what works today.
 */
@Composable
fun RoutePreviewPanel(
    routeState: RouteUiState,
    onProfileSelected: (RouteProfile) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (routeState is RouteUiState.Idle) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            when (routeState) {
                RouteUiState.Idle -> Unit
                is RouteUiState.Preparing -> {
                    // A Close affordance is not optional here: Preparing
                    // can legitimately hold for many minutes (a first
                    // region build, or queued behind one), and without it
                    // the only escape is long-pressing a new destination.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Distinct from the build banner's own title —
                            // the two stack on screen while a first route
                            // grows the graph.
                            Text(
                                "Preparing route",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            routeState.bucket?.let { bucket ->
                                Text(
                                    "building $bucket — first route in a region takes minutes",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
                is RouteUiState.Previewing -> {
                    val result = routeState.result
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                formatDistance(result.distanceMeters),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                formatDuration(result.durationSeconds),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onStart) { Text("Start") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        RouteProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = profile == result.profile,
                                onClick = { onProfileSelected(profile) },
                                label = { Text(profile.label) },
                            )
                        }
                    }
                }
                is RouteUiState.Failed -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            routeState.message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}