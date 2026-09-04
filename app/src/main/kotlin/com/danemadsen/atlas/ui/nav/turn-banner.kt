package com.danemadsen.atlas.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.nav.NavigationProgress
import com.danemadsen.atlas.nav.turnInstruction
import com.danemadsen.atlas.routing.TurnCommand
import kotlin.math.roundToInt

/**
 * The next-turn banner that replaces the search bar during navigation:
 * maneuver icon, the live distance to it, and the instruction with the
 * street it leads onto. Google-Maps-shaped: the distance is what glances
 * read first, the instruction second.
 */
@Composable
fun TurnBanner(
    snapshot: NavigationProgress.Snapshot?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        if (snapshot == null) {
            Text(
                "Waiting for a GPS fix…",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            return@Surface
        }
        val turn = snapshot.nextTurn
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (turn != null) {
                TurnIcon(
                    command = turn.command,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        distanceLabel(snapshot.distanceToNextTurnMeters),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        turnInstruction(turn.command, turn.streetName),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                // Past the last turn: everything left is arrival.
                TurnIcon(
                    command = TurnCommand.ARRIVE,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        distanceLabel(snapshot.remainingMeters),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Continue to your destination",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** The banner's live distance: whole meters below a kilometer, then km. */
private fun distanceLabel(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m"
    else String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)