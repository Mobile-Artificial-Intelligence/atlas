package com.danemadsen.atlas.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import java.util.Locale

/**
 * The outbound side of external map integration: hands the chosen point
 * to the rest of the device through the system Sharesheet — whatever the
 * device has installed (other map apps included) shows up in the chooser.
 *
 * Shared text is deliberately plain coordinates (plus a name when one is
 * known) — useful to a recipient without Atlas, with no URLs of any kind.
 */
object LocationIntentLauncher {

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
        // The caller is the application context, not an Activity — without
        // the new-task flag startActivity throws AndroidRuntimeException,
        // which took down the menu the tap came from.
        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        // No app on the device handles text shares at all.
        false
    }
}