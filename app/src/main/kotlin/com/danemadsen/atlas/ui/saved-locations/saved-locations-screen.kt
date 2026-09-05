package com.danemadsen.atlas.ui.savedlocations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * The Saved tab of the bottom navigation: the Home/Work pins, the arbitrary
 * saved places, and the ways to create them. Inline content like Settings —
 * the whole screen with the map hidden underneath; Back and the Map tab are
 * the same way out.
 */
@Composable
fun SavedLocationsScreen(
    savedLocations: List<SavedLocation>,
    onRouteToSaved: (SavedLocation) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onClearSlot: (id: String) -> Unit,
    onBeginPick: (slot: SavedSlot?) -> Unit,
    onSaveMapCenter: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back is the gesture way back to the Map tab.
    BackHandler(onBack = onDismiss)

    // The action dialog's target: long-press or MoreVert on a row opens it.
    var actions_for by remember { mutableStateOf<SavedLocation?>(null) }
    // The rename dialog's target (opened from the action dialog).
    var rename_for by remember { mutableStateOf<SavedLocation?>(null) }

    Surface(
        // pointerInput with an empty body is deliberate (same as Settings):
        // a plain Surface is NOT hit-testable, so without this every touch
        // on blank areas falls through to the MapLibre view underneath — a
        // long-press on the occluded map would drop a destination marker.
        // The Saved tab must own its input.
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {},
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("Saved", style = MaterialTheme.typography.headlineSmall)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // Pinned slots, fixed order, each either filled or promptable.
                SettingsSectionLabel("Pinned")
                for (slot in SavedSlot.entries) {
                    val pinned = savedLocations.firstOrNull { it.slot == slot }
                    if (pinned != null) {
                        SavedLocationRow(
                            location = pinned,
                            displayName = pinned.name.ifBlank { slot.defaultLabel() },
                            onRoute = { onRouteToSaved(pinned) },
                            onOpenActions = { actions_for = pinned },
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${slot.defaultLabel()} not set", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Long-press the map to set it, or save from search.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onBeginPick(slot) }) { Text("Set from the map") }
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SettingsSectionLabel("Add")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    TextButton(onClick = onSaveMapCenter) { Text("Save map center") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onBeginPick(null) }) { Text("Pick on map") }
                }
                Text(
                    "Save the place you are looking at, or arm the map's long-press: " +
                        "the next long-press saves instead of routing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val general = savedLocations.filter { it.slot == null }
                if (general.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSectionLabel("Places")
                    // A plain Column (not LazyColumn): nested scrolling inside
                    // the verticalScroll parent must be avoided.
                    Column {
                        for (location in general) {
                            SavedLocationRow(
                                location = location,
                                displayName = location.name.ifBlank { DEFAULT_PIN_NAME },
                                onRoute = { onRouteToSaved(location) },
                                onOpenActions = { actions_for = location },
                            )
                        }
                    }
                }

                if (savedLocations.isEmpty()) {
                    Text(
                        "Pin Home and Work for one-tap routing, or save places from search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    actions_for?.let { location ->
        SavedActionsDialog(
            location = location,
            onRename = {
                actions_for = null
                rename_for = location
            },
            onClearSlot = {
                actions_for = null
                onClearSlot(location.id)
            },
            onDelete = {
                actions_for = null
                onDelete(location.id)
            },
            onDismiss = { actions_for = null },
        )
    }

    rename_for?.let { location ->
        RenameDialog(
            location = location,
            onConfirm = { name ->
                rename_for = null
                onRename(location.id, name)
            },
            onDismiss = { rename_for = null },
        )
    }
}

/** Rename / Clear slot / Delete — low-stakes data, delete is immediate. */
@Composable
private fun SavedActionsDialog(
    location: SavedLocation,
    onRename: () -> Unit,
    onClearSlot: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(location.name.ifBlank { DEFAULT_PIN_NAME }) },
        text = {
            Column {
                TextButton(onClick = onRename) { Text("Rename") }
                if (location.slot != null) {
                    TextButton(onClick = onClearSlot) { Text("Clear slot") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RenameDialog(
    location: SavedLocation,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(location.name.ifBlank { DEFAULT_PIN_NAME }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifEmpty { location.name }) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedLocationRow(
    location: SavedLocation,
    displayName: String,
    onRoute: () -> Unit,
    onOpenActions: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Tap routes; long-press opens the action dialog — with the
            // trailing MoreVert as the discoverable alternative.
            .combinedClickable(onClick = onRoute, onLongClick = onOpenActions)
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                "%.5f, %.5f".format(Locale.US, location.lon, location.lat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenActions) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
        }
    }
}

/**
 * Shown on the Map tab while a pick is armed, so the armed state — the
 * next long-press saves instead of routing — is always visible and
 * cancellable. Exported for MapScreen's bottom column.
 */
@Composable
fun SavedPickBanner(onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp),
        ) {
            Text(
                "Long-press the map to save this location",
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}