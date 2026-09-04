package com.danemadsen.atlas.ui.onboarding

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.ui.AtlasUiState

/**
 * First-launch flow: a popover asking for the PMTiles map archive, with the
 * prebuilt routing-data ZIP recommended alongside it (`.pmtiles` and the
 * routing ZIP have no registered MIME types, so both pickers accept all
 * files), an import progress dialog while the copy + routing install run,
 * and an error dialog with retry.
 *
 * The archive is picked first; the Import button appears once it is
 * selected, so the recommended routing-data row is seen before the choice
 * to skip it — supplying it is the difference between routing-ready-at-
 * once and a ~30-minute-per-region on-device preparation.
 */
@Composable
fun ImportArchiveFlow(
    state: AtlasUiState,
    onImport: (archive: android.net.Uri, routingData: android.net.Uri?) -> Unit,
    onRetry: () -> Unit,
) {
    // Picked-but-not-yet-imported files: the dialog is a two-step choice,
    // not a one-tap import, so the routing-data row can be considered
    // before anything is copied.
    var picked_archive by remember { mutableStateOf<android.net.Uri?>(null) }
    var picked_routing by remember { mutableStateOf<android.net.Uri?>(null) }

    val context = LocalContext.current
    val archive_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) picked_archive = uri }
    val routing_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) picked_routing = uri }

    when (state) {
        is AtlasUiState.NeedsArchive -> AlertDialog(
            onDismissRequest = { /* supplying map data is the only way forward */ },
            title = { Text("Load map data") },
            text = {
                Column {
                    val archive = picked_archive
                    if (archive == null) {
                        Text(
                            "Atlas is fully offline — it has no internet access. " +
                                "Choose a PMTiles map archive (generated with Planetiler) to " +
                                "use as your map. It will be copied into the app.",
                        )
                    } else {
                        Text("Map archive: ${displayName(context, archive)}")
                        Text(
                            "Routing data (recommended): a ZIP of prebuilt Atlas routing " +
                                "segments. Without it, Atlas prepares routing on this device " +
                                "in the background — about 30 minutes per region.",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val routing = picked_routing
                    if (routing != null) {
                        Text(
                            "✓ ${displayName(context, routing)}",
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(
                        onClick = { routing_launcher.launch(arrayOf("*/*")) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(if (routing == null) "Add routing data" else "Choose different routing data")
                    }
                }
            },
            confirmButton = {
                val archive = picked_archive
                if (archive == null) {
                    TextButton(onClick = { archive_launcher.launch(arrayOf("*/*")) }) {
                        Text("Choose map archive")
                    }
                } else {
                    TextButton(onClick = { onImport(archive, picked_routing) }) {
                        Text("Import")
                    }
                }
            },
            dismissButton = {
                // Only meaningful once an archive is picked; the initial
                // dialog has nothing to go back to.
                if (picked_archive != null) {
                    TextButton(onClick = {
                        picked_archive = null
                        picked_routing = null
                    }) { Text("Back") }
                }
            },
        )

        is AtlasUiState.Importing -> AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("Importing map data") },
            text = {
                Column {
                    Text(state.stage, Modifier.padding(bottom = 12.dp))
                    val progress = state.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
        )

        is AtlasUiState.ImportFailed -> {
            // "Try another file" must mean it: without this the remembered
            // picks re-seed the dialog and the confirm button becomes
            // "Import" — one tap re-runs the identical failing import.
            LaunchedEffect(state) {
                picked_archive = null
                picked_routing = null
            }
            AlertDialog(
            onDismissRequest = { },
            title = { Text("Import failed") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = onRetry) { Text("Try another file") }
            },
        )
        }

        is AtlasUiState.MapReady -> Unit
    }
}

/** The picker's label for a chosen file, falling back to its URI tail. */
private fun displayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val name_index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (name_index >= 0 && cursor.moveToFirst()) cursor.getString(name_index) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "chosen file"