package com.danemadsen.atlas.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.routing.formatDistance
import com.danemadsen.atlas.routing.formatDuration
import kotlin.math.roundToInt

/**
 * The bottom drawer during navigation: live remaining distance/ETA, the
 * recalculating notice, the mute toggle, and Stop. Terminal states (arrived,
 * re-route failure) render here too — navigation owns the drawer until it
 * ends, so the user always has exactly one way out.
 */
@Composable
fun NavigationPanel(
    navState: NavigationCoordinator.NavState,
    onStop: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Surface(
        // No inset on the Surface: it paints all the way to the physical
        // bottom edge (edge-to-edge), leaving no sliver of map under the
        // gesture bar — the CONTENT below clears the bar instead.
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            when (navState) {
                is NavigationCoordinator.NavState.Navigating -> {
                    val snapshot = navState.snapshot
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (snapshot != null) {
                                Text(
                                    formatDistance(snapshot.remainingMeters.roundToInt()),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    formatDuration(snapshot.remainingSeconds),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    "Waiting for a GPS fix…",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            if (navState.recalculating) {
                                Text(
                                    "Recalculating the route…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (!navState.ttsAvailable && snapshot != null) {
                                // The designed fallback: no TTS engine
                                // initialized, the banner carries everything.
                                Text(
                                    "Voice guidance unavailable — following the banner",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = onToggleMute) {
                            Text(if (navState.muted) "Unmute" else "Mute")
                        }
                        Button(onClick = onStop) { Text("Stop") }
                    }
                }

                is NavigationCoordinator.NavState.Arrived -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "You have arrived",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onStop) { Text("Done") }
                    }
                }

                is NavigationCoordinator.NavState.Failed -> {
                    Text(
                        navState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onStop) { Text("Close") }
                    }
                }

                NavigationCoordinator.NavState.Idle -> Unit
            }
        }
    }
}