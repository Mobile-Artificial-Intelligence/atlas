package com.danemadsen.atlas.ui.onboarding

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.danemadsen.atlas.ui.AtlasUiState
import com.danemadsen.atlas.ui.ImportStage

/**
 * First-launch flow: pick the files Atlas runs from, then watch them land.
 *
 * Three files come from the same download (`.pmtiles`, the routing ZIP and
 * the search index have no registered MIME types, so every picker accepts
 * all files): the map archive is what Atlas is; the routing and search files
 * are the prebuilt counterparts of background builds that otherwise take
 * ~30 minutes per region and minutes-to-hours respectively on this device.
 * Each is its own row — seen, considered, and only then skipped.
 *
 * The import itself renders as a stage checklist: what is done, what is
 * running, what is next — a several-GB copy otherwise looks frozen behind
 * a bare spinner.
 */
@Composable
fun ImportArchiveFlow(
    state: AtlasUiState,
    onImport: (archive: android.net.Uri, routingData: android.net.Uri?, searchData: android.net.Uri?) -> Unit,
    onRetry: () -> Unit,
) {
    // Picked-but-not-yet-imported files: the dialog is a staged choice,
    // not a one-tap import, so the optional rows can be considered before
    // anything is copied. The picks survive into the Importing checklist —
    // it needs them to know which stages the user actually asked for.
    var picked_archive by remember { mutableStateOf<android.net.Uri?>(null) }
    var picked_routing by remember { mutableStateOf<android.net.Uri?>(null) }
    var picked_search by remember { mutableStateOf<android.net.Uri?>(null) }

    val context = LocalContext.current
    val archive_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) picked_archive = uri }
    val routing_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) picked_routing = uri }
    val search_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) picked_search = uri }

    when (state) {
        is AtlasUiState.NeedsArchive -> AlertDialog(
            onDismissRequest = { /* supplying map data is the only way forward */ },
            title = { Text("Load map data") },
            text = {
                Column {
                    Text(
                        "Atlas is fully offline — it has no internet access. " +
                            "Bring the files it needs from the same download:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    DataFileRow(
                        icon = Icons.Outlined.Map,
                        title = "Map archive",
                        tag = "required",
                        helper = "Your country's PMTiles map. Copied into the app.",
                        pickedName = picked_archive?.let { displayName(context, it) },
                        onPick = { archive_launcher.launch(arrayOf("*/*")) },
                        pickLabel = "Choose map archive",
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DataFileRow(
                        icon = Icons.Outlined.AltRoute,
                        title = "Routing data",
                        tag = "recommended",
                        helper = "Without it, routing prepares on this device — " +
                            "about 30 minutes per region.",
                        pickedName = picked_routing?.let { displayName(context, it) },
                        onPick = { routing_launcher.launch(arrayOf("*/*")) },
                        pickLabel = if (picked_routing == null) "Add routing data" else "Choose different file",
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    DataFileRow(
                        icon = Icons.Outlined.ManageSearch,
                        title = "Search index",
                        tag = "recommended",
                        helper = "Without it, search builds on this device after the import.",
                        pickedName = picked_search?.let { displayName(context, it) },
                        onPick = { search_launcher.launch(arrayOf("*/*")) },
                        pickLabel = if (picked_search == null) "Add search index" else "Choose different file",
                    )
                }
            },
            confirmButton = {
                val archive = picked_archive
                TextButton(
                    onClick = { if (archive != null) onImport(archive, picked_routing, picked_search) },
                    enabled = archive != null,
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                // Only meaningful once a file is picked; the initial dialog
                // has nothing to go back to.
                if (picked_archive != null || picked_routing != null || picked_search != null) {
                    TextButton(onClick = {
                        picked_archive = null
                        picked_routing = null
                        picked_search = null
                    }) { Text("Clear") }
                }
            },
        )

        is AtlasUiState.Importing -> AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("Importing map data") },
            text = {
                Column {
                    // The stage list is what the user actually asked for:
                    // a skipped optional file is not a stage that can run.
                    val steps = buildList {
                        add(ImportStage.COPY_ARCHIVE to "Copying map archive")
                        if (picked_routing != null) add(ImportStage.INSTALL_ROUTING to "Installing routing data")
                        if (picked_search != null) add(ImportStage.INSTALL_SEARCH to "Installing search index")
                    }
                    steps.forEachIndexed { index, (stage, label) ->
                        val running = stage == state.stage
                        ImportStageRow(
                            label = label,
                            done = stage < state.stage,
                            running = running,
                            last = index == steps.lastIndex,
                        )
                        if (running && stage == ImportStage.COPY_ARCHIVE && state.progress != null) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, top = 2.dp, bottom = 4.dp),
                            )
                        }
                    }
                    if (steps.size == 1 && state.progress == null) {
                        // A bare copy with no percentage yet (the file size
                        // query can stall) must not look frozen. Guarded on
                        // progress==null, or it stacks under the determinate
                        // bar the moment the percentage starts arriving.
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 32.dp, top = 2.dp))
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
                picked_search = null
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

        is AtlasUiState.MapReady -> {
            // A successful import clears the picks. They otherwise survive
            // into the NEXT import (Settings' "Replace map archive" opens
            // this dialog's Importing checklist) and would render phantom
            // "Installing routing data"/"Installing search index" stages for
            // files that import is not going to install.
            LaunchedEffect(state) {
                picked_archive = null
                picked_routing = null
                picked_search = null
            }
        }
    }
}

/**
 * One pickable file: a tinted icon disc, what it is, whether it is chosen
 * yet, and the way to choose it. The disc gives the row list a shape the
 * plain-text version lacked — three rows of prose read as a paragraph;
 * three of these read as a checklist.
 */
@Composable
private fun DataFileRow(
    icon: ImageVector,
    title: String,
    tag: String,
    helper: String,
    pickedName: String?,
    onPick: () -> Unit,
    pickLabel: String,
) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        " · $tag",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pickedName != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 52.dp)) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    pickedName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        TextButton(onClick = onPick, modifier = Modifier.padding(start = 40.dp)) {
            Text(pickLabel)
        }
    }
}

/**
 * One line of the import checklist: ✓ done, spinner running, hollow circle
 * pending. The whole point of the list is that a multi-GB copy reads as
 * "in progress, with these still to come", not as a hang.
 */
@Composable
private fun ImportStageRow(label: String, done: Boolean, running: Boolean, last: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        when {
            done -> Icon(
                Icons.Outlined.Check,
                contentDescription = "done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            running -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp).semantics { contentDescription = "in progress" },
            )
            else -> Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .semantics { contentDescription = "waiting" },
            )
        }
        Text(
            label,
            style = if (running) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            color = when {
                done -> MaterialTheme.colorScheme.primary
                running -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 12.dp),
        )
    }
    if (!last) {
        // A thin connector makes the rows one list, not three siblings.
        HorizontalDivider(
            modifier = Modifier
                .padding(start = 32.dp, top = 8.dp)
                .height(8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
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