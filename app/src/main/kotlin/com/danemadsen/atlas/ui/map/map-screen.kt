package com.danemadsen.atlas.ui.map

import android.content.Context
import android.os.Build
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danemadsen.atlas.data.ArchiveInfo
import com.danemadsen.atlas.data.ArchiveStore
import com.danemadsen.atlas.location.LocationPresence
import com.danemadsen.atlas.location.LocationPresenceTracker
import com.danemadsen.atlas.mapstyle.StyleBuilder
import com.danemadsen.atlas.mapstyle.Themes
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.GraphBuildCoordinator
import com.danemadsen.atlas.routing.LocationPuck
import com.danemadsen.atlas.routing.RouteRenderer
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.ui.AtlasUiState
import com.danemadsen.atlas.ui.CameraSnapshot
import com.danemadsen.atlas.ui.DebugCameraBus
import com.danemadsen.atlas.ui.MainTabBar
import com.danemadsen.atlas.ui.RouteUiState
import com.danemadsen.atlas.ui.TAB_BAR_HEIGHT
import com.danemadsen.atlas.ui.graph.GraphPrepFlow
import com.danemadsen.atlas.ui.nav.NavigationPanel
import com.danemadsen.atlas.ui.nav.TurnBanner
import com.danemadsen.atlas.ui.onboarding.ImportArchiveFlow
import com.danemadsen.atlas.ui.rememberAtlasViewModel
import com.danemadsen.atlas.ui.route.RoutePreviewPanel
import com.danemadsen.atlas.ui.search.SearchBar
import com.danemadsen.atlas.ui.search.SearchResultsPanel
import com.danemadsen.atlas.ui.settings.SettingsScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * The single screen: a full-bleed MapLibre map once an archive is imported,
 * with the first-launch import popover layered on top until then, the
 * route drawer at the bottom, and long-press as the destination picker.
 */
@Composable
fun MapScreen() {
    val view_model = rememberAtlasViewModel()
    val state by view_model.state.collectAsStateWithLifecycle()
    val route_state by view_model.routeState.collectAsStateWithLifecycle()
    val search_state by view_model.searchState.collectAsStateWithLifecycle()
    val nav_state by view_model.navState.collectAsStateWithLifecycle()
    val selected_place by view_model.selectedPlace.collectAsStateWithLifecycle()
    var search_query by remember { mutableStateOf("") }
    val navigating = nav_state is NavigationCoordinator.NavState.Navigating

    Box(modifier = Modifier.fillMaxSize()) {
        val archive = (state as? AtlasUiState.MapReady)?.archive
        if (archive != null) {
            AtlasMap(
                archive = archive,
                routeState = route_state,
                navState = nav_state,
                selectedPlace = selected_place,
                savedCamera = view_model.savedCamera,
                onPlaceShown = view_model::onPlaceShown,
                onLongPress = view_model::requestRoute,
                onCameraSettled = view_model::onCameraSettled,
            )
        }
        if (archive != null) {
            // Edge-to-edge puts raw map labels behind the transparent
            // status bar, and with nothing behind them the system clock
            // collides head-on with street labels (measured: "5:31"
            // straight over "Victoria Street", in both themes). A
            // surface-tinted gradient fading out below the bar keeps
            // the time and icons legible while barely dimming the map.
            // No pointerInput: a plain Box lets gestures fall through
            // to the map underneath.
            val density = LocalDensity.current
            val scrim_height = with(density) {
                (WindowInsets.statusBars.getTop(this) + STATUS_SCRIM_FADE.toPx()).toDp()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(scrim_height)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                            ),
                        ),
                    ),
            )
        }
        ImportArchiveFlow(
            state = state,
            onImport = { archive, routing_data, search_data ->
                view_model.importArchive(archive, routing_data, search_data)
            },
            onRetry = view_model::dismissError,
        )
        val settings_open by view_model.settingsOpen.collectAsStateWithLifecycle()
        val tts_muted by view_model.ttsMuted.collectAsStateWithLifecycle()
        if (archive != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Edge-to-edge: the bar must clear the status bar,
                    // and the results panel below the map must clear it too.
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp),
            ) {
                if (navigating) {
                    // The banner replaces the search bar while driving:
                    // what the user needs at a glance is the next maneuver,
                    // not a search field.
                    TurnBanner(snapshot = (nav_state as NavigationCoordinator.NavState.Navigating).snapshot)
                } else if (!settings_open) {
                    // The Settings tab owns the whole screen — a search
                    // field floating over the map behind it would be an
                    // interactive hole in an opaque panel.
                    SearchBar(
                        query = search_query,
                        searchState = search_state,
                        onQueryChange = { query ->
                            search_query = query
                            view_model.onSearchQueryChange(query)
                        },
                    )
                }
            }
        }
        if (archive != null) {
            if (nav_state is NavigationCoordinator.NavState.Idle) {
                // The bottom chrome is a tab layout, not an overlay: the
                // Settings tab fills everything above the tab bar (the
                // weighted child expands this Column to full height), the
                // Map tab stacks its panels there instead. The tab bar
                // clears the system navigation bar itself
                // (NavigationBarDefaults.windowInsets), so unlike the
                // panels it needs no outer navigationBarsPadding.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    if (settings_open) {
                        SettingsScreen(
                            archive = archive,
                            ttsMuted = tts_muted,
                            onToggleTtsMute = view_model::toggleMute,
                            onDismiss = view_model::closeSettings,
                            onReplaceArchive = { uri -> view_model.importArchive(uri) },
                            onInstallRoutingData = view_model::installRoutingData,
                            onInstallSearchData = view_model::installSearchData,
                            onPrepareAllRoutingData = view_model::prepareAllRoutingData,
                            onRebuildRoutingData = view_model::rebuildRoutingData,
                            onRebuildSearchIndex = view_model::rebuildSearchIndex,
                            modifier = Modifier.weight(1f),
                        )
                        // A routing build can also be running (or fail)
                        // while the user sits on this tab — the banner and
                        // its Cancel button must not unmount with the Map
                        // tab, or a 30-minute job has no visible surface.
                        GraphPrepFlow(state, route_state)
                    } else {
                        // Navigation owns the drawer from Start until it
                        // ends (arrived, failed, or Stop) — exactly one
                        // way out, never a route preview stacked under a
                        // live session.
                        Column {
                            RoutePreviewPanel(
                                routeState = route_state,
                                onProfileSelected = view_model::selectProfile,
                                onStart = view_model::startNavigation,
                                onRetry = view_model::reRoute,
                                onDismiss = view_model::dismissRoute,
                            )
                            SearchResultsPanel(
                                searchState = search_state,
                                onSelectPlace = view_model::selectPlace,
                                onRouteToPlace = view_model::routeToPlace,
                            )
                            GraphPrepFlow(state, route_state)
                        }
                    }
                    MainTabBar(
                        settingsOpen = settings_open,
                        onOpenMap = view_model::closeSettings,
                        onOpenSettings = view_model::openSettings,
                    )
                }
            } else {
                // No inset on the Box: the panel's Surface must reach the
                // physical bottom edge (a map sliver under the gesture bar
                // looks like a rendering gap). NavigationPanel pads its
                // own content clear of the bar. (No tab bar here:
                // navigation owns the whole screen until it ends.)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    NavigationPanel(
                        navState = nav_state,
                        onStop = view_model::stopNavigation,
                        onToggleMute = view_model::toggleMute,
                    )
                }
            }
        }
    }
}

/** The offline MapLibre map, restyled whenever the system theme flips. */
@Composable
fun AtlasMap(
    archive: ArchiveInfo,
    routeState: RouteUiState,
    navState: NavigationCoordinator.NavState,
    selectedPlace: PlaceHit?,
    savedCamera: CameraSnapshot?,
    onPlaceShown: () -> Unit,
    onLongPress: (GeoPoint) -> Unit,
    onCameraSettled: (CameraSnapshot, from_user_move: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle_owner = LocalLifecycleOwner.current
    val map_view = remember { MapView(context) }
    var map_libre by remember { mutableStateOf<MapLibreMap?>(null) }
    // The loaded style instance: a theme restyle replaces it wholesale, and
    // the route layers live inside it — the render effect re-runs whenever
    // this changes so the route survives (or is re-cleared) a restyle.
    var loaded_style by remember { mutableStateOf<Style?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { map_view },
    )

    // MapView owns its own Android lifecycle callbacks.
    DisposableEffect(map_view, lifecycle_owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> map_view.onCreate(null)
                Lifecycle.Event.ON_START -> map_view.onStart()
                Lifecycle.Event.ON_STOP -> map_view.onStop()
                Lifecycle.Event.ON_DESTROY -> map_view.onDestroy()
                else -> Unit
            }
        }
        lifecycle_owner.lifecycle.addObserver(observer)
        onDispose { lifecycle_owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(map_view) {
        map_view.getMapAsync { map -> map_libre = map }
    }

    // The stock compass position (top-right) sits in the search bar's
    // corner; park it bottom-left instead, above the tab bar — the bar
    // paints over the map, so the compass must clear its full height
    // (gesture-bar inset included), not just the safe area. The left inset
    // uses safeDrawing for display cutouts, but the bottom deliberately
    // uses navigationBars + the bar height: safeDrawing also tracks the
    // IME, which would bounce the compass up with the keyboard. The
    // MapLibre attribution and logo go away entirely — data credits live
    // on the attribution screen, not on the map.
    val density = LocalDensity.current
    val layout_direction = LocalLayoutDirection.current
    val safe_left_px = WindowInsets.safeDrawing.getLeft(density, layout_direction)
    val tab_bar_px = WindowInsets.navigationBars.getBottom(density) +
        with(density) { TAB_BAR_HEIGHT.roundToPx() }
    LaunchedEffect(map_libre, safe_left_px, tab_bar_px) {
        val map = map_libre ?: return@LaunchedEffect
        val margin_px = with(density) { 8.dp.roundToPx() }
        map.uiSettings.apply {
            isAttributionEnabled = false
            isLogoEnabled = false
            compassGravity = Gravity.BOTTOM or Gravity.START
            setCompassMargins(safe_left_px + margin_px, 0, 0, tab_bar_px + margin_px)
        }
    }

    // adb-driven camera control: atlas://camera?lon=..&lat=..&zoom=..
    // A deep link must also win against the FIRST style load's
    // fit-bounds: the replay lands while the style is still loading, so
    // without this flag the style-loaded callback would fit the whole
    // archive over the deep-linked camera a second later.
    var deep_link_camera_applied by remember { mutableStateOf(false) }
    // True while a camera move Atlas itself initiated (the preview fit, a
    // drawer fly-to, this restore, the adb deep link) is in flight — such
    // a settle reports the map's framing, not a view the user chose, so
    // persistence must not adopt it. Consumed by the next camera-idle.
    var programmatic_camera by remember { mutableStateOf(false) }
    LaunchedEffect(map_libre) {
        val map = map_libre ?: return@LaunchedEffect
        DebugCameraBus.requests.collect { request ->
            deep_link_camera_applied = true
            programmatic_camera = true
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(request.lat, request.lon))
                        .zoom(request.zoom)
                        .bearing(request.bearing)
                        .build(),
                ),
            )
            // The replayed request has now been applied: drop it, or the
            // NEXT map composition (an archive replace recreates the map
            // wholesale) would receive this stale camera and suppress the
            // new archive's fit with deep_link_camera_applied.
            DebugCameraBus.consumeReplay()
        }
    }

    // Long-press is the destination picker (search-based destinations
    // arrive with M7).
    LaunchedEffect(map_libre) {
        val map = map_libre ?: return@LaunchedEffect
        map.addOnMapLongClickListener(MapLibreMap.OnMapLongClickListener { latLng ->
            onLongPress(GeoPoint(latLng.longitude, latLng.latitude))
            true
        })
    }

    // The search ranker scores by distance from where the user is
    // looking, and process-death restore wants the whole position — so
    // the map reports every settled camera, not just its center. The
    // flag sorts settles Atlas caused from the user's own gestures.
    LaunchedEffect(map_libre) {
        val map = map_libre ?: return@LaunchedEffect
        map.addOnCameraIdleListener {
            val position = map.cameraPosition
            val target = position.target ?: return@addOnCameraIdleListener
            val from_user = !programmatic_camera
            programmatic_camera = false
            onCameraSettled(
                CameraSnapshot(
                    lon = target.longitude,
                    lat = target.latitude,
                    zoom = position.zoom,
                    bearing = position.bearing,
                ),
                from_user,
            )
        }
    }

    // A drawer-selected place flies the camera there and the selection
    // clears immediately — the same place tapped twice must fly twice.
    // Atlas's framing of the place, not the user's chosen view.
    LaunchedEffect(map_libre, selectedPlace) {
        val map = map_libre ?: return@LaunchedEffect
        val place = selectedPlace ?: return@LaunchedEffect
        programmatic_camera = true
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(place.lat, place.lon))
                    .zoom(SELECTED_PLACE_ZOOM)
                    .build(),
            ),
        )
        onPlaceShown()
    }

    // The map's brand family (motorways, major roads, casings, transit text)
    // sings in the wallpaper accent — the same one the chrome uses. Only on
    // Android 12+, where dynamic color exists; below that the Compose
    // fallback's baseline purple would repaint the map with a color the user
    // never picked, so the stock OSM Liberty / dark palettes stand.
    val material_accent_argb: Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MaterialTheme.colorScheme.primary.toArgb()
        } else {
            null
        }
    // The route's own color: straight from the palette, whatever its source.
    val accent_argb = MaterialTheme.colorScheme.primary.toArgb()

    // The user-location puck's input: only collected while the FINE
    // location permission holds (denied → no stream, no dot — the
    // standing rule; FINE specifically because the GPS provider refuses
    // COARSE-only listeners). Re-checked on every resume so a grant made
    // mid-session (the onboarding dialog or system Settings) starts the
    // stream without an app restart.
    var has_location_permission by remember {
        mutableStateOf(LocationPresenceTracker.hasFineLocationPermission(context))
    }
    // The loss timer does not survive backgrounding (collection pauses
    // with the lifecycle), so resume re-judges the last fix by its age: a
    // fix older than the loss threshold degrades to Lost instead of
    // showing a pulsing blue dot at a minutes-old position.
    var resume_wallclock_ms by remember { mutableStateOf(0L) }
    DisposableEffect(lifecycle_owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                has_location_permission =
                    LocationPresenceTracker.hasFineLocationPermission(context)
                resume_wallclock_ms = System.currentTimeMillis()
            }
        }
        lifecycle_owner.lifecycle.addObserver(observer)
        onDispose { lifecycle_owner.lifecycle.removeObserver(observer) }
    }
    // Composition disposal stops the echo animator: nothing else ever
    // would, and an INFINITE ValueAnimator parked on a destroyed style
    // outlives the map that asked for it.
    DisposableEffect(lifecycle_owner) {
        onDispose { LocationPuck.stopPulse() }
    }
    val presence_flow = remember(has_location_permission, context) {
        if (has_location_permission) {
            LocationPresenceTracker.observe(context).distinctUntilChanged()
        } else {
            flowOf<LocationPresence?>(null)
        }
    }
    val raw_presence by presence_flow.collectAsStateWithLifecycle(initialValue = null)
    val location_presence: LocationPresence? =
        if (raw_presence is LocationPresence.Active &&
            resume_wallclock_ms - (raw_presence as LocationPresence.Active).at_ms >
            LocationPresenceTracker.SIGNAL_LOST_MS
        ) {
            LocationPresence.Lost((raw_presence as LocationPresence.Active).point)
        } else {
            raw_presence
        }

    val dark_theme = isSystemInDarkTheme()
    // The route's casing halo comes from the MAP theme, not the Compose
    // chrome palette: colorScheme.surface sits within a few percent of the
    // map's ground color in both themes, and an invisible halo defeats its
    // whole purpose (separating the accent line from the roads under it).
    // The same theme object feeds the style build so the accent retint
    // (a wallpaper change mid-session) rebuilds the style in step.
    val map_theme =
        (if (dark_theme) Themes.DARK else Themes.LIGHT).withMaterialAccent(material_accent_argb)
    val casing_argb = themeColorArgb(map_theme.colors.getValue("background"))

    LaunchedEffect(map_libre, map_theme, archive) {
        val map = map_libre ?: return@LaunchedEffect
        val theme = map_theme
        val style_json = StyleBuilder.buildStyleJson(
            templateJson = loadStyleTemplate(context),
            theme = theme,
            source = StyleBuilder.SourceInfo(
                archivePath = ArchiveStore.archiveFile(context).absolutePath,
            ),
        )
        // setStyle() detaches the previous Style synchronously (its
        // validateState then throws IllegalStateException on ANY access),
        // and the async load leaves a window with no valid style: null
        // loaded_style for that window so the route effect no-ops instead
        // of crashing on the dying instance.
        val previous_camera = loaded_style?.let { map.cameraPosition }
        loaded_style = null
        // NB: setStyle(String) always treats its argument as a *URI*
        // (Style.Builder.fromUri) — a raw JSON string must go through fromJson.
        map.setStyle(Style.Builder().fromJson(style_json)) { style ->
            loaded_style = style
            // A theme restyle is not a camera reset: only the very first
            // style load fits the archive. Reuse the camera the user had
            // (route rendering re-animates to the route bounds on top of
            // this, if a route is showing). A deep link applied while THIS
            // style was loading wins over both branches — the collector's
            // moveCamera already placed it, and restoring the pre-link
            // camera would silently discard it.
            if (!deep_link_camera_applied) {
                if (previous_camera != null) {
                    programmatic_camera = true
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(previous_camera))
                } else {
                    // Process death: reopen where the user left off, not
                    // at the whole-archive fit — but only when the saved
                    // camera is inside THIS archive (a replaced archive
                    // clears it, and the bounds check is the second
                    // line of defense).
                    val saved = savedCamera?.takeIf {
                        GraphBuildCoordinator.insideArchive(it.lon, it.lat, archive)
                    }
                    if (saved != null) {
                        programmatic_camera = true
                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(saved.lat, saved.lon))
                                    .zoom(saved.zoom)
                                    .bearing(saved.bearing)
                                    .build(),
                            ),
                        )
                    } else {
                        programmatic_camera = true
                        fitCameraToArchive(map, archive)
                    }
                }
            }
        }
    }

    // Route rendering: keyed on the style instance too, so a theme restyle
    // (which rebuilds every layer from the style JSON) re-arms the layers.
    // While a navigation session is live this effect stands down — the nav
    // effect below owns the route line (a re-route must repaint it, and
    // this branch would fit the STALE preview bounds over the follow
    // camera after a restyle). Keyed on the session boundary (not every
    // snapshot) so ending a session hands the preview route back here.
    val nav_active = navState != NavigationCoordinator.NavState.Idle
    LaunchedEffect(loaded_style, routeState, nav_active) {
        val style = loaded_style ?: return@LaunchedEffect
        val map = map_libre ?: return@LaunchedEffect
        if (nav_active) return@LaunchedEffect
        when (val rs = routeState) {
            is RouteUiState.Previewing -> {
                RouteRenderer.showRoute(style, rs.result, accent_argb, casing_argb)
                if (rs.result.points.isNotEmpty()) {
                    val bounds = LatLngBounds.Builder()
                    rs.result.points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
                    // A route preview must own the camera even while a place
                    // fly-to (or the previous preview's animation) is still
                    // in flight: MapLibre can swallow a queued camera update
                    // behind an active animator, leaving the preview
                    // off-camera. Cancel first so the fit-bounds animator is
                    // the only one running.
                    //
                    // The fit goes through getCameraForLatLngBounds, not a
                    // raw newLatLngBounds: a degenerate route (destination
                    // ~origin) fits to an area of meters, and the raw fit
                    // runs to the map's zoom ceiling (z=25.5 measured) —
                    // far past where tile overzoom still draws anything.
                    // Capping a few levels above the tile max keeps tiny
                    // routes framed on a live map.
                    val before = map.cameraPosition
                    // The fit is Atlas framing the route, not the user
                    // choosing a view: the settle it lands on must not
                    // overwrite the browsing camera restore keeps.
                    programmatic_camera = true
                    map.cancelTransitions()
                    val fitted = map.getCameraForLatLngBounds(
                        bounds.build(),
                        intArrayOf(
                            ROUTE_BOUNDS_PADDING_PX,
                            ROUTE_BOUNDS_PADDING_PX,
                            ROUTE_BOUNDS_PADDING_PX,
                            ROUTE_BOUNDS_PADDING_PX,
                        ),
                    )
                    if (fitted == null) {
                        // Degenerate bounds MapLibre won't frame: the raw
                        // fit is the best that can be done with them.
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds.build(), ROUTE_BOUNDS_PADDING_PX)
                        )
                    } else {
                        val capped = if (fitted.zoom > archive.maxZoom + PREVIEW_MAX_OVERZOOM) {
                            CameraPosition.Builder(fitted)
                                .zoom(archive.maxZoom + PREVIEW_MAX_OVERZOOM)
                                .build()
                        } else {
                            fitted
                        }
                        map.animateCamera(CameraUpdateFactory.newCameraPosition(capped))
                    }
                    // Diagnostic: animateCamera returns Unit in this SDK,
                    // so the settled camera itself is the only truthful
                    // signal of whether the fit applied.
                    map_view.postDelayed({
                        val after = map.cameraPosition
                        System.out.println(
                            "route preview: fit-bounds z=${before.zoom} -> ${after.zoom} " +
                                "target=${after.target}"
                        )
                    }, FIT_BOUNDS_SETTLE_MS)
                }
            }
            RouteUiState.Idle,
            is RouteUiState.Preparing,
            is RouteUiState.Failed,
            -> RouteRenderer.clear(style)
        }
    }

    // Navigation rendering + camera follow: keyed on the whole nav state so
    // every published snapshot (≈1/s) re-renders. The route comes from the
    // NAVIGATING result, not the preview state — a re-route swaps the line
    // and the map/banner/drawer all move together because they all read
    // this one flow.
    LaunchedEffect(loaded_style, navState) {
        val style = loaded_style ?: return@LaunchedEffect
        val map = map_libre ?: return@LaunchedEffect
        when (val ns = navState) {
            is NavigationCoordinator.NavState.Navigating -> {
                RouteRenderer.showRoute(style, ns.result, accent_argb, casing_argb)
                val snapshot = ns.snapshot
                if (snapshot?.snapped != null) {
                    // cancelTransitions first: a queued preview fit-bounds
                    // (or a stale follow animator) must not fight this move.
                    map.cancelTransitions()
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(snapshot.snapped.lat, snapshot.snapped.lon))
                                .zoom(NAV_ZOOM)
                                // No bearing from the chip yet (a stationary
                                // first fix): keep whatever heading the
                                // camera had rather than snapping to north.
                                .bearing(snapshot.bearing ?: map.cameraPosition.bearing)
                                .build(),
                        )
                    )
                }
            }

            is NavigationCoordinator.NavState.Arrived -> {
                // Keep the completed route visible at the destination.
                RouteRenderer.showRoute(style, ns.result, accent_argb, casing_argb)
            }

            is NavigationCoordinator.NavState.Failed -> {
                // The old route stays on the map — the user can still read
                // and follow it. Re-render it too, or a theme restyle
                // during this panel would drop the one thing the user
                // still has.
                ns.result?.let { RouteRenderer.showRoute(style, it, accent_argb, casing_argb) }
            }

            NavigationCoordinator.NavState.Idle -> Unit
        }
    }

    // The user-location puck, Google-Maps style: blue and pulsing while
    // fixes arrive, grey at the last fix once the stream goes quiet,
    // nothing before the first fix. During navigation the position is the
    // snapped route point (the same thing the camera follows); everywhere
    // else — browsing, preview, arrived — it is the raw fix. Liveness is
    // ALWAYS the live presence: a silent GPS turns the navigating puck
    // grey too.
    LaunchedEffect(loaded_style, navState, location_presence) {
        val style = loaded_style ?: return@LaunchedEffect
        val presence = location_presence
        when (val ns = navState) {
            is NavigationCoordinator.NavState.Navigating -> {
                val point =
                    ns.snapshot?.snapped ?: (presence as? LocationPresence.Active)?.point
                val active = presence is LocationPresence.Active
                LocationPuck.show(style, point, active)
                if (point != null && active) LocationPuck.startPulse(style)
            }

            else -> when (presence) {
                is LocationPresence.Active -> {
                    LocationPuck.show(style, presence.point, active = true)
                    LocationPuck.startPulse(style)
                }
                is LocationPresence.Lost ->
                    LocationPuck.show(style, presence.point, active = false)
                // No fix ever landed: arm the layers empty so a restyle
                // mid-session re-adds them before the first fix arrives.
                null -> LocationPuck.show(style, null, active = true)
            }
        }
    }
}

private fun fitCameraToArchive(map: MapLibreMap, archive: ArchiveInfo) {
    val bounds = LatLngBounds.Builder()
        .include(LatLng(archive.north, archive.west))
        .include(LatLng(archive.south, archive.east))
        .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, FIT_BOUNDS_PADDING_PX))
}

/** The shared style template, merged in from :lib:map-style's assets. */
private var cached_style_template: String? = null

private fun loadStyleTemplate(context: Context): String {
    cached_style_template?.let { return it }
    val template = context.assets.open("style-template.json")
        .bufferedReader()
        .use { it.readText() }
    cached_style_template = template
    return template
}

private const val FIT_BOUNDS_PADDING_PX = 64
private const val ROUTE_BOUNDS_PADDING_PX = 96
/**
 * How far past the archive's tile maxzoom a route preview may frame:
 * enough to see a tiny route up close, not so far that overzoomed tiles
 * stop rendering (z=25.5, where an uncapped fit sends a meters-long
 * route, is a blank canvas).
 */
private const val PREVIEW_MAX_OVERZOOM = 4.0
/** How far below the status bar the scrim keeps fading. */
private val STATUS_SCRIM_FADE = 48.dp
/** The fit-bounds settle log fires after the camera animation ends. */
private const val FIT_BOUNDS_SETTLE_MS = 2_500L
/** Street-level, but wide enough to recognize the place in context. */
private const val SELECTED_PLACE_ZOOM = 14.5
/** Camera-follow zoom: close enough to read the streets being driven. */
private const val NAV_ZOOM = 17.0

/**
 * Theme tokens carry CSS-style colors — the style JSON wants that syntax,
 * but `android.graphics.Color.parseColor` only understands `#hex` and would
 * throw on the light theme's `rgb(239,239,239)` background. Both forms are
 * supported here because the map chrome reads whatever token it needs.
 */
private fun themeColorArgb(colorToken: String): Int = when {
    colorToken.startsWith("#") -> android.graphics.Color.parseColor(colorToken)
    colorToken.startsWith("rgb(") -> {
        val parts = colorToken.removePrefix("rgb(").removeSuffix(")")
            .split(',').map { it.trim().toInt() }
        android.graphics.Color.rgb(parts[0], parts[1], parts[2])
    }
    else -> throw IllegalArgumentException("unsupported theme color: $colorToken")
}