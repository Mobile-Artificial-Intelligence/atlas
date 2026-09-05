package com.danemadsen.atlas.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.danemadsen.atlas.data.ArchiveInfo

/**
 * The Settings tab of the bottom navigation. Inline content (not a
 * dialog anymore — the tab bar stays reachable): the actions here are
 * consequential — replacing the archive, rebuilding routing data — and
 * deserve the whole screen with the map hidden underneath. Back and the
 * Map tab are the same way out; there is no close button to duplicate
 * them.
 */
@Composable
fun SettingsScreen(
    archive: ArchiveInfo,
    ttsMuted: Boolean,
    onToggleTtsMute: () -> Unit,
    overlayEnabled: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onReplaceArchive: (uri: android.net.Uri) -> Unit,
    onInstallRoutingData: (uri: android.net.Uri) -> Unit,
    onInstallSearchData: (uri: android.net.Uri) -> Unit,
    onPrepareAllRoutingData: () -> Unit,
    onRebuildRoutingData: () -> Unit,
    onRebuildSearchIndex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back is the gesture way back to the Map tab.
    BackHandler(onBack = onDismiss)

    // The rebuild wipes every prepared region; the destructive action
    // gets the same confirm step the destination picker would.
    var confirm_rebuild by remember { mutableStateOf(false) }

    // `.pmtiles` has no registered MIME type — the picker accepts all
    // files, exactly like the first-launch import.
    val archive_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onReplaceArchive(uri) }

    // Same story for the routing ZIP: no registered MIME type for a file
    // the user side-loads from a CI artifact download.
    val routing_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onInstallRoutingData(uri) }

    // And for the prebuilt search index's ZIP — same source, same picker.
    val search_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onInstallSearchData(uri) }

    // The overlay appop has no runtime dialog: enabling without the grant
    // opens the system screen scoped to Atlas, and returning auto-applies
    // a pending enable (see the LifecycleResumeEffect below). When the
    // appop is revoked mid-flight the pref deliberately stays ON — the
    // service-side canDrawOverlays guard keeps the window honest, and
    // re-granting restores behavior with no extra taps.
    val context = LocalContext.current
    var overlay_granted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var pending_overlay_enable by remember { mutableStateOf(false) }
    val overlay_permission_launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlay_granted = AndroidSettings.canDrawOverlays(context) }

    LifecycleResumeEffect(Unit) {
        val granted = AndroidSettings.canDrawOverlays(context)
        overlay_granted = granted
        // The user granted the appop while we were in the system screen:
        // complete the pending enable on their return.
        if (granted && pending_overlay_enable) {
            pending_overlay_enable = false
            onToggleOverlay(true)
        }
        onPauseOrDispose { }
    }

    /** Settings' overlay switch: three cases — direct, grant-first, off. */
    fun onOverlaySwitch(want: Boolean) {
        when {
            want && AndroidSettings.canDrawOverlays(context) -> onToggleOverlay(true)
            want -> {
                // Enabling without the appop: go get it. The switch stays
                // visually OFF (the pref is untouched) until the grant lands.
                pending_overlay_enable = true
                overlay_permission_launcher.launch(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            else -> onToggleOverlay(false)
        }
    }

    Surface(
        // pointerInput with an empty body is deliberate: Compose hit-testing
        // only dispatches along the topmost hit-testable node's path, and a
        // plain Surface is NOT hit-testable — without this, every touch on
        // the panel's blank areas falls through to the MapLibre view
        // underneath (a long-press on the panel would drop a destination
        // marker on the occluded map). The Settings tab must own its input.
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
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                    SettingsSectionLabel("Map data")
                    Text(
                        "${archive.fileName} · ${formatBytes(archive.sizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "zooms ${archive.minZoom}–${archive.maxZoom}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    TextButton(onClick = { archive_launcher.launch(arrayOf("*/*")) }) {
                        Text("Replace map archive")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSectionLabel("Routing data")
                    Text(
                        "Routing data can be prepared on this device, or installed " +
                            "from a prebuilt routing ZIP — the file published alongside " +
                            "your map archive. Installing it makes routing ready " +
                            "instantly: no background build. The ZIP must come from the " +
                            "same download as the archive; Atlas refuses a mismatched pair.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { routing_launcher.launch(arrayOf("*/*")) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Install routing data")
                    }
                    Text(
                        "Without a routing ZIP, routes are prepared per region on " +
                            "this device. \"Prepare all\" builds every region the " +
                            "archive covers — for a country-sized archive that is " +
                            "hours of background work.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = onPrepareAllRoutingData,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Prepare all routing data")
                    }
                    Text(
                        "Rebuild discards every prepared region and prepares your " +
                            "current area again. Use it after an app update changed the " +
                            "routing profile data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = { confirm_rebuild = true },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Rebuild routing data")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSectionLabel("Search")
                    Text(
                        "The search index can be built on this device, or loaded " +
                            "from a prebuilt search index — the file published " +
                            "alongside your map archive. Loading it makes search " +
                            "ready instantly: no minutes-long build. It must come " +
                            "from the same download as the archive; Atlas refuses " +
                            "a mismatched pair.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { search_launcher.launch(arrayOf("*/*")) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Load search index")
                    }
                    Text(
                        "Rebuilds the place index from the map archive.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    OutlinedButton(
                        onClick = onRebuildSearchIndex,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Rebuild search index")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSectionLabel("Voice guidance")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Spoken turn instructions", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "The turn banner always shows the maneuver.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = !ttsMuted, onCheckedChange = { onToggleTtsMute() })
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSectionLabel("Floating banner")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show next turn over other apps", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (overlay_granted) {
                                    "A small turn banner floats above other apps while navigating."
                                } else {
                                    "Needs the “Display over other apps” permission."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = overlayEnabled, onCheckedChange = ::onOverlaySwitch)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    var attribution_open by remember { mutableStateOf(false) }
                    val version = appVersion(LocalContext.current)
                    Text(
                        "Atlas $version — fully offline",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { attribution_open = true }) {
                        Text("Open-source credits")
                    }
                    AttributionDialog(
                        open = attribution_open,
                        onDismiss = { attribution_open = false },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

    if (confirm_rebuild) {
        AlertDialog(
            onDismissRequest = { confirm_rebuild = false },
            title = { Text("Rebuild routing data?") },
            text = {
                Text(
                    "Every prepared region is discarded and your current area " +
                        "is prepared again. A region takes about 30 minutes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm_rebuild = false
                    onRebuildRoutingData()
                }) { Text("Rebuild") }
            },
            dismissButton = {
                TextButton(onClick = { confirm_rebuild = false }) { Text("Cancel") }
            },
        )
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

/**
 * The credits for everything Atlas is built from. Data attribution lives
 * here rather than on the map, so the map stays uncluttered — the same
 * choice the attribution/logos toggle in the map chrome makes.
 */
@Composable
fun AttributionDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source credits") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AttributionBlock(
                    "Map rendering",
                    "MapLibre Native (BSD-2-Clause) — maplibre.org",
                )
                AttributionBlock(
                    "Map style",
                    "OSM Liberty by Maputnik (CC0) — sprites and glyphs bundled " +
                        "from the maputnik/osm-liberty and orangemug/font-glyphs repositories",
                )
                AttributionBlock(
                    "Map data",
                    "OpenMapTiles schema, tiles generated with Planetiler. " +
                        "Map data © OpenStreetMap contributors (ODbL).",
                )
                AttributionBlock(
                    "Address search",
                    "Address points from OpenAddresses (CC-BY) — merged into " +
                        "the country archives at build time. openaddresses.io",
                )
                AttributionBlock(
                    "Routing",
                    "BeeRouter (MPL-2.0) by Jan Gillich — a Kotlin fork of BRouter. " +
                        "BRouter by A. Menzel et al. Car, bike and foot profiles " +
                        "from the BRouter profile collection.",
                )
                AttributionBlock(
                    "Archive format",
                    "PMTiles by Brandon Liu (BSD-3-Clause) — a single-file " +
                        "tile archive with no server and no internet.",
                )
                AttributionBlock(
                    "Fonts",
                    "Roboto — bundled as glyph PBF ranges",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AttributionBlock(label: String, body: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(body, style = MaterialTheme.typography.bodySmall)
}

/** Human-readable bytes: the archive is GBs, the profile assets KBs. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun appVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")