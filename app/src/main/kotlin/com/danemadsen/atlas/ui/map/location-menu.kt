package com.danemadsen.atlas.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.ui.savedlocations.SavedSlot
import com.danemadsen.atlas.ui.savedlocations.SavedSlot.HOME
import com.danemadsen.atlas.ui.savedlocations.SavedSlot.WORK
import java.util.Locale

/**
 * The map's long-press menu: what the user can do with a chosen point.
 * Route is the primary action; Home/Work saves land in their slots (the
 * one place slots are set from, per the saved tab's redesign); Save
 * location appends a plain pin.
 */
@Composable
fun LocationMenuPanel(
    point: GeoPoint?,
    onDismiss: () -> Unit,
    onRoute: (GeoPoint) -> Unit,
    onSetSlot: (SavedSlot) -> Unit,
    onSave: () -> Unit,
) {
    point ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // The chosen point, as coordinates — the only identity the
            // menu has until it is saved and named. Cancel is the way
            // out: the menu sits in the bottom stack, so a tap on the
            // map behind it does not reach a dismissal handler.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%.5f, %.5f".format(Locale.US, point.lon, point.lat),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            HorizontalDivider()
            MenuRow("Route here", Icons.Filled.Place) {
                onRoute(point)
            }
            MenuRow("Set as Home", Icons.Filled.Home) {
                onSetSlot(HOME)
            }
            MenuRow("Set as Work", Icons.Filled.Work) {
                onSetSlot(WORK)
            }
            MenuRow("Save location", Icons.Filled.Place) {
                onSave()
            }
        }
    }
}

/**
 * One menu row: icon + label, tap to act, tap-away dismisses the panel.
 */
@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
