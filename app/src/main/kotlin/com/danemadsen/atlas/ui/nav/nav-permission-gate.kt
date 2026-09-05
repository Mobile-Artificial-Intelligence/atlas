package com.danemadsen.atlas.ui.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.danemadsen.atlas.nav.NavigationCoordinator

/**
 * The navigation-start POST_NOTIFICATIONS ask. The Start button press is
 * the moment of clearest intent, and the system dialog is the whole UI —
 * no custom rationale dialog.
 *
 * Navigation proceeds regardless of the grant (a denial only silences the
 * progress notification — the graph-prep precedent: the build runs
 * regardless). Ask at most once per install, keyed off the shared prefs:
 * a user who denied once never gets asked again on later starts.
 *
 * A service cannot host the dialog, so this must live in composition —
 * which rules out the NavigationService and NavigationCoordinator
 * surfaces. Wired at the one call site (map-screen's RoutePreviewPanel
 * onStart seam), not inside startNavigation(): AtlasViewModel is not a
 * composition owner and cannot hold an ActivityResultLauncher.
 */
@Composable
fun rememberNavNotificationAsker(): () -> Unit {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(NavigationCoordinator.PREFS_NAME, Context.MODE_PRIVATE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Empty: a grant or denial never blocks or unblocks navigation.
    }
    return remember(prefs, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (
                Build.VERSION.SDK_INT >= 33 &&
                !granted &&
                !prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)
            ) {
                prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

private const val KEY_NOTIFICATIONS_ASKED = "notifications.asked"