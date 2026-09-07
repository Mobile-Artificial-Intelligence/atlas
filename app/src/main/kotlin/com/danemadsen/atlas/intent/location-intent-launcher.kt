package com.danemadsen.atlas.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * The outbound side of external map integration: hands the chosen point
 * to OTHER installed mapping apps through the standard Android surface —
 * an `ACTION_VIEW geo:` for "Open with…", the system Sharesheet for
 * "Share". Never names a specific app: whatever the device has installed
 * shows up in the chooser.
 *
 * Shared text is deliberately plain coordinates (plus a name when one is
 * known) — useful to a recipient without Atlas, with no URLs of any kind.
 */
object LocationIntentLauncher {

    /**
     * Opens the system chooser offering [latitude], [longitude] to every
     * installed app that handles `geo:` URIs. False (with nothing
     * launched) when no handler exists at all — the chooser itself covers
     * the "installed but disabled" middle ground with its own empty state.
     */
    fun openWith(context: Context, latitude: Double, longitude: Double): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude"))
        return launch(context, Intent.createChooser(intent, null))
    }

    /**
     * Shares the location as plain text through the Sharesheet:
     * the optional name on its own line, then `lat, lon` in decimal
     * degrees at ~0.1 m precision.
     */
    fun share(context: Context, name: String?, latitude: Double, longitude: Double): Boolean {
        val text = buildString {
            if (!name.isNullOrBlank()) {
                append(name)
                append('\n')
            }
            append(
                String.format(Locale.US, "%.6f, %.6f", latitude, longitude),
            )
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return launch(context, Intent.createChooser(intent, null))
    }

    private fun launch(context: Context, chooser: Intent): Boolean = try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        // No app on the device handles geo:/text shares at all.
        false
    }
}