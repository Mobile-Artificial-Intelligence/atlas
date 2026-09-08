package com.danemadsen.atlas.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.routing.RoutePlan
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.RouteStop

/** Compact directions card over the map; the itinerary scrolls independently. */
@Composable
fun DirectionsPanel(
    plan: RoutePlan,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onReverse: () -> Unit,
    onProfile: (RouteProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close directions")
                }
                Text("Directions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onReverse) {
                    Icon(Icons.Default.SwapVert, if (plan.stops.size == 2) "Swap start and destination" else "Reverse stop order")
                }
            }
            LazyColumn(Modifier.heightIn(max = 224.dp)) {
                itemsIndexed(plan.stops, key = { _, stop -> stop.id }) { index, stop ->
                    DirectionStopRow(
                        stop = stop,
                        index = index,
                        count = plan.stops.size,
                        onEdit = { onEdit(stop.id) },
                        onRemove = { onRemove(stop.id) },
                        onMove = { onMove(stop.id, it) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onAdd, enabled = plan.canAddStop) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (plan.canAddStop) "Add stop" else "9 stops maximum")
                }
                if (plan.stops.size > 2) Text(
                    "Hold ≡ to reorder",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RouteProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = plan.profile == profile,
                        onClick = { onProfile(profile) },
                        label = { Text(profile.label) },
                        leadingIcon = {
                            Icon(when (profile) {
                                RouteProfile.CAR -> Icons.Default.DirectionsCar
                                RouteProfile.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
                                RouteProfile.FOOT -> Icons.AutoMirrored.Filled.DirectionsWalk
                            }, null, Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionStopRow(
    stop: RouteStop,
    index: Int,
    count: Int,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val role = when (index) { 0 -> "Starting point"; count - 1 -> "Destination"; else -> "Stop $index" }
    val latestIndex by rememberUpdatedState(index)
    val latestMove by rememberUpdatedState(onMove)
    val rowHeight = with(LocalDensity.current) { 64.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp)
            .background(if (dragging) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 4.dp)
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move $role up") { onMove(index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction("Move $role down") { onMove(index + 1); true })
                    if (count > 2) add(CustomAccessibilityAction("Remove $role") { onRemove(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (index == 0) Icon(Icons.Default.RadioButtonUnchecked, null, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
            else Text(('A' + index - 1).toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Surface(
            onClick = onEdit,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stop.label.ifEmpty { if (index == 0) "Choose starting point" else "Choose destination" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (stop.isFilled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (count > 2) IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove $role", Modifier.size(20.dp)) }
        Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.pointerInput(stop.id, count, rowHeight) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = true; distance = 0f },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        distance += amount.y
                        if (kotlin.math.abs(distance) >= rowHeight) {
                            val direction = if (distance > 0) 1 else -1
                            val target = (latestIndex + direction).coerceIn(0, count - 1)
                            if (target != latestIndex) latestMove(target)
                            distance = 0f
                        }
                    }
                },
            ) { Icon(Icons.Default.DragHandle, "Reorder $role", Modifier.size(20.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = { menu = false; onMove(index - 1) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = index < count - 1, onClick = { menu = false; onMove(index + 1) })
            }
        }
    }
}
