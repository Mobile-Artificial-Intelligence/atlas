package com.danemadsen.atlas.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val light_color_scheme = lightColorScheme()
private val dark_color_scheme = darkColorScheme()

/** App chrome follows the system color scheme and, on Android 12+, the Material
 *  You dynamic accent. */
@Composable
fun AtlasTheme(content: @Composable () -> Unit) {
    val dark_theme = isSystemInDarkTheme()
    val context = LocalContext.current
    val color_scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark_theme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark_theme) dark_color_scheme else light_color_scheme
    }
    MaterialTheme(colorScheme = color_scheme, content = content)
}