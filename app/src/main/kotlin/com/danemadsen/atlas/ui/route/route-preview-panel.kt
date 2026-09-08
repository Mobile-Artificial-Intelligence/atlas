package com.danemadsen.atlas.ui.route

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.routing.formatDistance
import com.danemadsen.atlas.routing.formatDuration
import com.danemadsen.atlas.ui.RouteUiState

/** The route total stays at the bottom while the itinerary is edited above the map. */
@Composable
fun RoutePreviewPanel(
    routeState: RouteUiState,
    canNavigate: Boolean,
    stopCount: Int,
    onStart: () -> Unit,
    onUseMyLocation: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            when (routeState) {
                RouteUiState.Idle -> Text(
                    "Choose your starting point and all stops to see directions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is RouteUiState.Preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Finding your route…", style = MaterialTheme.typography.titleMedium)
                            if (routeState.bucket != null) Text(
                                "Preparing offline roads. The first route in an area can take a few minutes.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                is RouteUiState.Previewing -> {
                    val result = routeState.result
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDuration(result.durationSeconds), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                formatDistance(result.distanceMeters) + when {
                                    stopCount == 1 -> " · 1 stop"
                                    stopCount > 1 -> " · $stopCount stops"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canNavigate) Button(onClick = onStart) {
                            Icon(Icons.Default.Navigation, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Start")
                        }
                    }
                    if (!canNavigate) {
                        Text("Route preview from your chosen starting point.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = onUseMyLocation, contentPadding = PaddingValues(0.dp)) { Text("Use my location to navigate") }
                    }
                }
                is RouteUiState.Failed -> {
                    Text(routeState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }
    }
}
