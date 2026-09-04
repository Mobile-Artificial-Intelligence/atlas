package com.danemadsen.atlas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.danemadsen.atlas.ui.DebugCameraBus
import com.danemadsen.atlas.ui.map.MapScreen
import com.danemadsen.atlas.ui.theme.AtlasTheme

class AtlasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleCameraUri(intent?.data)
        setContent {
            AtlasTheme {
                // The manifest declares uiMode in configChanges so the map
                // can restyle live without an activity recreation — which
                // also means enableEdgeToEdge()'s one-shot status-bar icon
                // styling goes stale the moment the system theme flips
                // mid-session. Re-drive it from the same signal the map
                // style uses so the time/battery icons stay legible in
                // both themes.
                val dark_theme = isSystemInDarkTheme()
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark_theme
                    controller.isAppearanceLightNavigationBars = !dark_theme
                }
                MapScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCameraUri(intent.data)
    }

    /** `atlas://camera?lon=..&lat=..&zoom=..&bearing=..` — adb-driven
     *  camera control for acceptance tests (explicit intents only; no
     *  intent-filter). */
    private fun handleCameraUri(uri: Uri?) {
        if (uri?.host != "camera") return
        val lon = uri.getQueryParameter("lon")?.toDoubleOrNull() ?: return
        val lat = uri.getQueryParameter("lat")?.toDoubleOrNull() ?: return
        val zoom = uri.getQueryParameter("zoom")?.toDoubleOrNull() ?: return
        val bearing = uri.getQueryParameter("bearing")?.toDoubleOrNull() ?: 0.0
        DebugCameraBus.emit(DebugCameraBus.Request(lon, lat, zoom, bearing))
    }
}