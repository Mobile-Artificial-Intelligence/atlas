package com.danemadsen.atlas.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's three-tab bottom navigation: the map is the product, the saved
 * destinations (Home/Work pins + arbitrary places) are the middle tab, and
 * settings is the last. A gear crammed into the search bar was easy to
 * fat-finger next to the mic and invisible as "a place you can go"; tabs
 * make all three reachable with a thumb at the bottom edge.
 *
 * Navigation mode hides the bar entirely (the turn banner and nav panel
 * own the screen), and so does onboarding (the import flow is modal).
 */

/** The bar itself, above the gesture-bar inset. M3's default is a taller 80 dp. */
internal val TAB_BAR_HEIGHT = 64.dp

@Composable
fun MainTabBar(
    activeTab: Tab,
    onOpenMap: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The container paints the gesture-bar inset too (Material 3 applies
    // windowInsets INSIDE its height), so the inset is added on top: the
    // items keep the full 64 dp and nothing clips on any nav mode.
    val nav_inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .height(nav_inset + TAB_BAR_HEIGHT),
        windowInsets = androidx.compose.material3.NavigationBarDefaults.windowInsets,
    ) {
        NavigationBarItem(
            selected = activeTab == Tab.MAP,
            onClick = onOpenMap,
            icon = { Icon(Icons.Filled.Map, contentDescription = null) },
            label = { Text("Map") },
        )
        NavigationBarItem(
            selected = activeTab == Tab.SAVED,
            onClick = onOpenSaved,
            icon = { Icon(Icons.Filled.Star, contentDescription = null) },
            label = { Text("Saved") },
        )
        NavigationBarItem(
            selected = activeTab == Tab.SETTINGS,
            onClick = onOpenSettings,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}