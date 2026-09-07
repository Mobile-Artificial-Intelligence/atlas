package com.danemadsen.atlas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danemadsen.atlas.AtlasApplication
import com.danemadsen.atlas.data.PmtilesRepository
import com.danemadsen.atlas.data.RegionInfo
import com.danemadsen.atlas.data.RegionStore
// The routing adoption's bounded-IO helpers (stage-to-file + manifest parse)
// are internal top-level functions of the same module — reused here so the
// UI's fingerprint pairing reads exactly what the adoption will read.
import com.danemadsen.atlas.graph.copyBounded
import com.danemadsen.atlas.graph.parseRoutingManifest
import com.danemadsen.atlas.graph.stageZip
import com.danemadsen.atlas.search.SearchIndexer
import com.danemadsen.atlas.intent.ExternalMapIntentHandler
import com.danemadsen.atlas.intent.MapIntentRequest
import com.danemadsen.atlas.intent.LocationIntentLauncher
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.RouterGateway
import com.danemadsen.atlas.routing.GraphBuildCoordinator
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.search.SearchCoordinator
import com.danemadsen.atlas.ui.savedlocations.DEFAULT_PIN_NAME
import com.danemadsen.atlas.ui.savedlocations.SavedLocation
import com.danemadsen.atlas.ui.savedlocations.defaultLabel
import com.danemadsen.atlas.ui.savedlocations.SavedLocationStore
import com.danemadsen.atlas.ui.savedlocations.SavedSlot
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
// createSavedStateHandle is a top-level extension on CreationExtras in
// lifecycle 2.8+ — the JVM facade class SavedStateHandleSupport is not
// addressable from Kotlin, so the object-member import form does not resolve.
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The whole UI is a function of this state:
 *
 * `NeedsArchive` (fresh install) → `Importing` → `MapReady`,
 * with `ImportFailed` recoverable back to `NeedsArchive`.
 */
sealed interface AtlasUiState {
    data object NeedsArchive : AtlasUiState
    data class Importing(
        val progress: Float?,
        val stage: ImportStage = ImportStage.COPY_ARCHIVE,
        /** What the running stage is working on — the file/region being copied or installed. */
        val detail: String? = null,
    ) : AtlasUiState
    data class ImportFailed(val message: String) : AtlasUiState

    /**
     * Any number of installed map regions ([RegionStore]); [primary] is the
     * one the camera, maxZoom clamps and search anchor default to — the
     * nearest to the user's view, or the newest import.
     */
    data class MapReady(
        val regions: List<RegionInfo>,
        val primary: RegionInfo,
    ) : AtlasUiState
}

/**
 * The import's ordered stages — the onboarding dialog renders them as a
 * checklist (done / running with progress), so the stage is a value, not a
 * message the UI has to match strings against.
 */
enum class ImportStage { COPY_ARCHIVE, INSTALL_ROUTING, INSTALL_SEARCH }

/**
 * The bottom tab bar's destination. Navigation mode hides the bar entirely
 * (the turn banner and nav panel own the screen), so the tab only matters
 * while [NavigationCoordinator.NavState] is Idle.
 */
enum class Tab { MAP, SAVED, SETTINGS }

/**
 * The routing side of the screen, orthogonal to the archive state:
 * long-press a destination → `Preparing` (which may hide a multi-minute
 * bucket build) → `Previewing`, with `Failed` recoverable via re-route.
 */
sealed interface RouteUiState {
    /** No route — the map is just a map. */
    data object Idle : RouteUiState
    /** A route is calculating, or its missing bucket is building. */
    data class Preparing(val bucket: String?) : RouteUiState
    /** A calculated route is on the map and in the drawer. */
    data class Previewing(val result: RouteResult) : RouteUiState
    data class Failed(val message: String) : RouteUiState
}

/** The search side of the screen: query → debounced results → drawer. */
sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class Results(val hits: List<PlaceHit>) : SearchUiState
}

/**
 * A settled camera: the one thing process death must not lose besides
 * the route. LMK kills the foreground app WITHOUT saving instance state
 * ("app died, no saved state" in logcat), so [androidx.lifecycle.SavedStateHandle]
 * comes back empty for exactly the kills that matter — the camera (and
 * route destination) ride in SharedPreferences instead.
 */
data class CameraSnapshot(
    val lon: Double,
    val lat: Double,
    val zoom: Double,
    val bearing: Double,
)

/**
 * The camera fly an external geo: intent asked for — coordinates plus the
 * optional `z=` zoom. Null zoom falls back to the map's place-selection
 * zoom (the fly is Atlas framing an intent target, like a drawer fly-to).
 */
data class IntentCameraTarget(
    val lon: Double,
    val lat: Double,
    val zoom: Double?,
)

class AtlasViewModel(
    private val app: AtlasApplication,
    private val repository: PmtilesRepository,
    private val saved_state: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow<AtlasUiState>(initialState())
    val state: StateFlow<AtlasUiState> = _state.asStateFlow()

    private val _routeState = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val routeState: StateFlow<RouteUiState> = _routeState.asStateFlow()

    /**
     * Navigation mode's state, published by the NavigationService's fix
     * loop through the coordinator (same process, no file bus needed).
     */
    val navState: StateFlow<NavigationCoordinator.NavState> =
        NavigationCoordinator.navState

    /** The in-flight route job; a new destination cancels the old one. */
    private var routeJob: Job? = null
    private var selectedProfile = RouteProfile.CAR
    private var lastDestination: GeoPoint? = null

    /** Small persisted app settings (the TTS mute, overlay, saved places). */
    private val prefs =
        app.getSharedPreferences(NavigationCoordinator.PREFS_NAME, android.content.Context.MODE_PRIVATE)

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    /** The debounced query job; a new keystroke cancels the old one. */
    private var searchJob: Job? = null

    /** The in-flight routing-ZIP install — single flight, see installRoutingData. */
    private var install_job: Job? = null

    /** The in-flight search-index install — single flight, see installSearchData. */
    private var search_install_job: Job? = null

    /**
     * The background engine warmup: parses the routing profiles and runs
     * one throwaway route so the user's first real route is warm. The
     * cost would otherwise land on that first route — minutes on slow
     * hardware — and here it lands on idle time right after the map
     * appears instead.
     */
    private var warmEngineJob: Job? = null

    /** The place the drawer selected, for the map's fly-to effect. */
    private val _selectedPlace = MutableStateFlow<PlaceHit?>(null)
    val selectedPlace: StateFlow<PlaceHit?> = _selectedPlace.asStateFlow()

    /** The map's idle center, reported by the map screen — the search ranker's distance anchor. */
    var mapCenter: GeoPoint? = null

    /**
     * The camera to restore on a fresh style load: prefs at process start,
     * then every user move the map settles on. A recreation must restore
     * the CURRENT view, not the process-start snapshot — the ViewModel
     * outlives the activity, so [savedCamera] is read again for a new
     * MapView while init does not re-run. Null when this install never
     * settled a camera (or the archive was just replaced).
     */
    private var initial_camera: CameraSnapshot? =
        if (prefs.contains(KEY_CAMERA_LON) && prefs.contains(KEY_CAMERA_LAT)) {
            CameraSnapshot(
                lon = prefs.getFloat(KEY_CAMERA_LON, 0.0f).toDouble(),
                lat = prefs.getFloat(KEY_CAMERA_LAT, 0.0f).toDouble(),
                zoom = prefs.getFloat(KEY_CAMERA_ZOOM, 0.0f).toDouble(),
                bearing = prefs.getFloat(KEY_CAMERA_BEARING, 0.0f).toDouble(),
            )
        } else {
            null
        }

    val savedCamera: CameraSnapshot? get() = initial_camera

    /**
     * The map reports every settled camera: the search anchor moves, and a
     * USER move persists for process-death restore. The follow camera
     * during navigation is deliberately NOT persisted — it moves about
     * once a second, is not the user's own view, and would burn a disk
     * write at fix rate. Programmatic moves (the preview fit, a drawer
     * fly-to, the adb deep link) are the map's framing of a route or place,
     * not a view the user chose — restoring them would reopen the app
     * framed on a route long since dismissed.
     */
    fun onCameraSettled(camera: CameraSnapshot, from_user_move: Boolean) {
        mapCenter = GeoPoint(camera.lon, camera.lat)
        if (navState.value !is NavigationCoordinator.NavState.Idle) return
        if (!from_user_move) return
        initial_camera = camera
        prefs.edit()
            .putFloat(KEY_CAMERA_LON, camera.lon.toFloat())
            .putFloat(KEY_CAMERA_LAT, camera.lat.toFloat())
            .putFloat(KEY_CAMERA_ZOOM, camera.zoom.toFloat())
            .putFloat(KEY_CAMERA_BEARING, camera.bearing.toFloat())
            .apply()
    }

    /** The bottom tab bar's selection, orthogonal to every other state. */
    private val _activeTab = MutableStateFlow(Tab.MAP)
    val activeTab: StateFlow<Tab> = _activeTab.asStateFlow()

    /**
     * Saved destinations (Home/Work pins plus the arbitrary list), persisted
     * as one JSON string under [KEY_SAVED_LOCATIONS]. They are user-curated
     * data — a Home must survive an archive replace the way the TTS mute
     * does — and a stale coordinate after a city switch degrades honestly:
     * requestRoute's insideArchive bounds check fails it into the normal
     * Failed drawer instead of lying or crashing.
     */
    private val _savedLocations = MutableStateFlow(
        SavedLocationStore.decode(prefs.getString(KEY_SAVED_LOCATIONS, null)),
    )
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    /**
     * The map's long-press location menu: a chosen point the user can
     * route to, save as Home/Work, or save as a plain location — the one
     * place slots are set from, now that the saved-locations screen no
     * longer sets them.
     */
    private val _locationMenuPoint = MutableStateFlow<GeoPoint?>(null)
    val locationMenuPoint: StateFlow<GeoPoint?> = _locationMenuPoint.asStateFlow()

    /**
     * The long-press menu's optional title label — set when the point was
     * chosen by an external geo: intent (`q=lat,lon(Label)`), null for a
     * plain long-press (the coordinate text is the title then).
     */
    private val _locationMenuLabel = MutableStateFlow<String?>(null)
    val locationMenuLabel: StateFlow<String?> = _locationMenuLabel.asStateFlow()

    /** The menu's tap-away/Cancel: clears the menu without side effects. */
    fun dismissLocationMenu() {
        _locationMenuPoint.value = null
        _locationMenuLabel.value = null
    }

    /**
     * Voice-guidance mute, persisted so it survives process death and
     * applies to the next session before the first fix can speak. The
     * settings switch and the navigation panel's Mute button both write
     * here — one source of truth, so the two can never disagree.
     */
    private val _ttsMuted = MutableStateFlow(prefs.getBoolean(NavigationCoordinator.KEY_TTS_MUTED, false))
    val ttsMuted: StateFlow<Boolean> = _ttsMuted.asStateFlow()

    /**
     * The floating turn banner's persisted switch — same prefs file, same
     * shape as the mute. Handed to [NavigationCoordinator.start] the way
     * the mute is; a mid-navigation toggle rides the coordinator's
     * [NavigationCoordinator.overlayRequested] to the live session.
     */
    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean(KEY_OVERLAY_ENABLED, false))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    init {
        // Process death must not silently drop a route the user had:
        // restore the destination + profile and re-request — the buckets
        // are already on disk, so the recalculation is seconds, and a
        // mid-build Preparing re-attaches to the still-running :graph
        // service through the same ensure path. But NOT while a session
        // is still driving: NavigationService keeps the process alive
        // after the activity finishes, and this ViewModel is fresh — the
        // restore's requestRoute would stop the live navigation.
        if (navState.value is NavigationCoordinator.NavState.Idle) {
            val saved_lon = saved_state.get<Double>(KEY_DEST_LON)
            val saved_lat = saved_state.get<Double>(KEY_DEST_LAT)
            if (saved_lon != null && saved_lat != null) {
                selectedProfile = saved_state.get<RouteProfile>(KEY_PROFILE) ?: RouteProfile.CAR
                requestRoute(GeoPoint(saved_lon, saved_lat))
            } else {
                // LMK kills the foreground app without ever running
                // onSaveInstanceState, so saved_state can come back empty
                // while prefs (written at request time) still carry the
                // route the user was mid-way through.
                val persisted_lon = readPersistedDouble(KEY_DEST_LON)
                val persisted_lat = readPersistedDouble(KEY_DEST_LAT)
                if (persisted_lon != null && persisted_lat != null) {
                    selectedProfile = prefs.getString(KEY_PROFILE, null)
                        ?.let { name -> RouteProfile.entries.firstOrNull { it.name == name } }
                        ?: RouteProfile.CAR
                    requestRoute(GeoPoint(persisted_lon, persisted_lat))
                }
            }
        }
        // Arrival is a terminal state: a kill while sitting on the Arrived
        // screen must not resurrect a trip already driven, so the
        // persisted route drops the moment the session is over — the
        // in-memory preview survives until the Done button dismisses it.
        viewModelScope.launch {
            navState.collect { current ->
                if (current is NavigationCoordinator.NavState.Arrived) {
                    clearPersistedRoute()
                }
            }
        }
        // The shade's Mute action (and later the car's) goes through the
        // coordinator, not through this ViewModel — mirror it so the
        // panel/Settings switch never disagrees with what actually speaks.
        // Never writes prefs: the coordinator's own toggle persists, and
        // this VM toggle already did for the phone-side path.
        viewModelScope.launch {
            navState.collect { current ->
                val muted =
                    (current as? NavigationCoordinator.NavState.Navigating)?.muted ?: return@collect
                if (muted != _ttsMuted.value) _ttsMuted.value = muted
            }
        }
        // An archive without an index (a mid-import process death, or an
        // install from before search existed) gets its build triggered
        // here — through the coordinator's ordering rule, so a routing
        // build that is about to run goes first.
        if (state.value is AtlasUiState.MapReady) {
            viewModelScope.launch { GraphBuildCoordinator.triggerSearchIndex(app) }
        }
        // Same for the engine warmup: with an archive on disk the map is
        // already usable, so the cold-engine cost belongs here in the
        // background, not on the user's first route.
        if (state.value is AtlasUiState.MapReady) warmEngine()
    }

    /**
     * The debounced search: 250 ms after the last keystroke, query the
     * FTS index and rank around the map's center (archive center before
     * the map has ever idled).
     */
    fun onSearchQueryChange(query: String) {
        if (query.isBlank()) {
            searchJob?.cancel()
            _searchState.value = SearchUiState.Idle
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val ready = state.value as? AtlasUiState.MapReady ?: return@launch
            val center = mapCenter ?: GeoPoint(ready.primary.centerLon, ready.primary.centerLat)
            // Multi-region: the coordinator opens a handle per installed
            // region's DB and merges — no archive handle in the UI contract.
            val hits = SearchCoordinator.search(
                app,
                query,
                center.lon,
                center.lat,
            )
            _searchState.value = SearchUiState.Results(hits)
        }
    }

    /** The map screen consumed the selected place's fly-to. */
    fun onPlaceShown() {
        _selectedPlace.value = null
    }

    /**
     * The camera target an external geo: intent asked for — set by
     * [onMapIntent], consumed by the map's fly and cleared by
     * [onIntentCameraShown]. Null-safe across activity recreations: the
     * request is never re-applied (the handler's seq dedup), and a target
     * that outlives its activity waits for the next map composition.
     */
    private val _intentCamera = MutableStateFlow<IntentCameraTarget?>(null)
    val intentCamera: StateFlow<IntentCameraTarget?> = _intentCamera.asStateFlow()

    /** The map screen consumed the intent's camera fly. */
    fun onIntentCameraShown() {
        _intentCamera.value = null
    }

    /**
     * The query an external geo: intent wants run through the offline
     * index. One-shot like [intentCamera]: the search bar copies it into
     * its own text field, the normal debounced search runs, done.
     */
    private val _pendingSearchQuery = MutableStateFlow<String?>(null)
    val pendingSearchQuery: StateFlow<String?> = _pendingSearchQuery.asStateFlow()

    /** The search bar consumed the intent's query. */
    fun onPendingSearchQueryShown() {
        _pendingSearchQuery.value = null
    }

    /**
     * The single inlet for external map intents: one [ExternalMapIntentHandler.Event],
     * one application to existing state —
     *
     * - a coordinate flies the camera and opens the long-press location
     *   menu at the point (the label, when the URI carried one, becomes
     *   the menu's title and the saved/share name);
     * - a search query rides the normal offline search path.
     *
     * The seq dedups a replayed event (activity recreation re-delivers the
     * handler's replay cache) without suppressing a genuine second open
     * of the same geo: link.
     */
    fun onMapIntent(event: ExternalMapIntentHandler.Event) {
        if (event.seq <= last_intent_seq) return
        last_intent_seq = event.seq
        ExternalMapIntentHandler.consumeReplay()
        when (val request = event.request) {
            is MapIntentRequest.Coordinate -> {
                _intentCamera.value = IntentCameraTarget(
                    lon = request.longitude,
                    lat = request.latitude,
                    zoom = request.zoom,
                )
                _locationMenuLabel.value = request.label
                // GeoPoint is (lon, lat) — the parser's fields arrive the
                // other way round, and a swapped pair here lands the menu
                // point (and the pin) off the map entirely.
                _locationMenuPoint.value = GeoPoint(request.longitude, request.latitude)
            }
            is MapIntentRequest.Search -> {
                _pendingSearchQuery.value = request.query
            }
        }
    }

    /** Highest intent seq this VM has applied — the replay dedup bound. */
    private var last_intent_seq = 0L

    /** Drawer row tap: fly the camera there. */
    fun selectPlace(place: PlaceHit) {
        _selectedPlace.value = place
    }

    /**
     * Search popover row tap: open the location menu — the same route
     * menu a long-press gets — at the place's point, named after it. The
     * camera flies there too, so the menu's actions act on a point the
     * user can actually see.
     */
    fun openPlaceMenu(place: PlaceHit) {
        selectPlace(place)
        _locationMenuLabel.value = place.name
        _locationMenuPoint.value = GeoPoint(place.lon, place.lat)
    }

    /**
     * The location menu's "Open with…": the long-press point (or the
     * geo-intent target) offered to every installed geo: handler through
     * the system chooser. A toast keeps the no-handler case honest.
     */
    fun openLocationWith(point: GeoPoint) {
        val ok = LocationIntentLauncher.openWith(app, point.lat, point.lon)
        if (!ok) toast("No other map app can open that location")
    }

    /**
     * The location menu's "Share": the point (named when it came from a
     * labeled geo: link) out through the Android Sharesheet as plain text.
     */
    fun shareLocation(point: GeoPoint) {
        val ok = LocationIntentLauncher.share(
            app,
            _locationMenuLabel.value,
            point.lat,
            point.lon,
        )
        if (!ok) toast("Nothing can share that location")
    }

    /** Launches the background engine warmup (idempotent per archive). */
    private fun warmEngine() {
        if (warmEngineJob?.isActive == true) return
        warmEngineJob = viewModelScope.launch {
            RouterGateway.warmEngine(app)
        }
    }

    /**
     * Kicks the search-index build through the coordinator's ordering
     * rule — routing data first, search second. The on-device pass runs
     * in the background `:graph` service; progress lands in the same
     * status banner routing uses, and a failure is announced by the
     * service's done notification.
     */
    private fun ensureSearchIndex(force: Boolean = false) {
        viewModelScope.launch { GraphBuildCoordinator.triggerSearchIndex(app, force) }
    }

    /**
     * Long-press destination: routes from the user's current fix to
     * [destination]. Per the offline product rule, the origin needs
     * location permission actually granted — denied permission or no fix
     * means no route, surfaced as Failed rather than silently skipped.
     */
    fun requestRoute(destination: GeoPoint) {
        // A new destination mid-navigation ends the running session: its
        // route is about to be replaced, and the service's fix loop must
        // not keep announcing the abandoned one.
        if (navState.value !is NavigationCoordinator.NavState.Idle) {
            stopNavigation()
        }
        routeJob?.cancel()
        lastDestination = destination
        saved_state[KEY_DEST_LON] = destination.lon
        saved_state[KEY_DEST_LAT] = destination.lat
        saved_state[KEY_PROFILE] = selectedProfile
        // The prefs mirror: same data, but it survives the LMK-style
        // kill that never saves instance state (see init). The
        // destination persists as its Double's string form — a Float
        // round-trip loses ~0.2 m, and a long-press within that margin
        // of the archive boundary would pass insideArchive when pressed
        // but fail it after restore, stranding a Failed drawer for a
        // point this app itself accepted.
        prefs.edit()
            .putString(KEY_DEST_LON, destination.lon.toString())
            .putString(KEY_DEST_LAT, destination.lat.toString())
            .putString(KEY_PROFILE, selectedProfile.name)
            .apply()
        routeJob = viewModelScope.launch {
            _routeState.value = RouteUiState.Preparing(null)
            // A long-press on the gray void beyond every installed region's
            // tiles can never route — fail before spending a build on it.
            val regions = repository.loadRegions()
            if (RegionStore.regionForPoint(regions, destination.lon, destination.lat) == null) {
                failRoute("the destination is outside the loaded map areas")
                return@launch
            }
            val origin = GraphBuildCoordinator.currentLocationInRegions(app)
                ?: run {
                    failRoute(
                        "no location fix for the origin — grant location permission and wait for a GPS fix",
                    )
                    return@launch
                }
            try {
                val result = RouterGateway.route(
                    context = app,
                    profile = selectedProfile,
                    origin = GeoPoint(origin.longitude, origin.latitude),
                    destination = destination,
                    onPreparing = { bucket -> _routeState.value = RouteUiState.Preparing(bucket) },
                )
                _routeState.value = RouteUiState.Previewing(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cancelled job (user long-pressed a new destination, or
                // pressed Close) must never write terminal state over its
                // replacement's: the engine runs on Dispatchers.Default
                // and its failures are delivered into this coroutine even
                // after cancel, with no suspension point left to re-check.
                // The explicit check here is the last line of defense.
                currentCoroutineContext().ensureActive()
                failRoute(e.message ?: "route failed")
            }
        }
    }

    /**
     * A terminal failure also drops the saved destination: process-death
     * restore must re-launch a route the user was MID-way through (a build
     * or a preview), never re-attempt one they saw fail — an unattended
     * "no location fix" failure would otherwise burn 30 s of fresh-fix
     * wait and possibly bucket builds on every relaunch.
     */
    private fun failRoute(message: String) {
        clearPersistedRoute()
        _routeState.value = RouteUiState.Failed(message)
    }

    /**
     * Drops the route from BOTH restore mirrors — the prefs copy (see
     * [requestRoute]) and saved_state's — so no path that abandons a
     * route can leave it behind for a future launch to resurrect.
     */
    private fun clearPersistedRoute() {
        saved_state.remove<Double>(KEY_DEST_LON)
        saved_state.remove<Double>(KEY_DEST_LAT)
        saved_state.remove<RouteProfile>(KEY_PROFILE)
        prefs.edit()
            .remove(KEY_DEST_LON)
            .remove(KEY_DEST_LAT)
            .remove(KEY_PROFILE)
            .apply()
    }

    /**
     * A persisted coordinate, written as a Double's string form but
     * readable from installs that still hold a Float in the key (a
     * getString/getFloat on the wrong type throws, so probe by exception).
     */
    private fun readPersistedDouble(key: String): Double? =
        try {
            prefs.getString(key, null)?.toDoubleOrNull()
        } catch (_: ClassCastException) {
            try {
                prefs.getFloat(key, 0.0f).toDouble()
            } catch (_: ClassCastException) {
                null
            }
        }

    /** The profile chips: re-run the current route on [profile]. */
    fun selectProfile(profile: RouteProfile) {
        selectedProfile = profile
        saved_state[KEY_PROFILE] = profile
        prefs.edit().putString(KEY_PROFILE, profile.name).apply()
        lastDestination?.let { requestRoute(it) }
    }

    /** The preview drawer's Start: hands the route to the navigation runtime. */
    fun startNavigation() {
        val result = (_routeState.value as? RouteUiState.Previewing)?.result ?: return
        // Navigation owns the whole screen (banner + panel, no tab bar);
        // leaving whichever tab was open behind it would resurface the
        // moment the session ends.
        _activeTab.value = Tab.MAP
        NavigationCoordinator.start(app, result, _ttsMuted.value, _overlayEnabled.value)
    }

    /**
     * Navigation's Stop/Close/Done: ends the session, back to the preview —
     * except Done on the arrival screen, where the trip is over:
     * re-offering "Start" on a route already driven is wrong, so the
     * completed route and its destination drop away to the plain map.
     */
    fun stopNavigation() {
        if (navState.value is NavigationCoordinator.NavState.Arrived) {
            dismissRoute()
        } else {
            NavigationCoordinator.stop(app)
        }
    }

    /** The navigation panel's Mute/Unmute (and the Settings switch). */
    fun toggleMute() {
        val muted = !_ttsMuted.value
        _ttsMuted.value = muted
        prefs.edit().putBoolean(NavigationCoordinator.KEY_TTS_MUTED, muted).apply()
        // The live session (if any) flips too, so what speaks matches
        // what the settings switch shows — and the coordinator's own
        // write keeps the shade's Mute action, the car's Mute, and this
        // switch on one persisted source of truth.
        if (navState.value is NavigationCoordinator.NavState.Navigating) {
            NavigationCoordinator.toggleMute(app)
        }
    }

    /**
     * The Settings tab's floating-banner switch: persist, and re-drive a
     * live session's overlay through the coordinator — the same path a
     * mute toggle rides, so a mid-navigation change is immediate.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        _overlayEnabled.value = enabled
        prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
        NavigationCoordinator.setOverlayRequested(enabled)
    }

    fun openSettings() {
        _activeTab.value = Tab.SETTINGS
    }

    /** Back and the Saved/Settings screens' way back to the Map tab. */
    fun closeSettings() {
        _activeTab.value = Tab.MAP
    }

    /** The middle tab's seam, symmetric with openSettings/closeSettings. */
    fun openSavedLocations() {
        _activeTab.value = Tab.SAVED
    }

    /** The general tab setter, for callers that hold a [Tab]. */
    fun selectTab(tab: Tab) {
        _activeTab.value = tab
    }

    private fun persistSavedLocations() {
        prefs.edit().putString(KEY_SAVED_LOCATIONS, SavedLocationStore.encode(_savedLocations.value)).apply()
    }

    /**
     * Upserts into the saved list: a slotted location replaces the old
     * occupant of its slot, others append. The single source of truth for
     * the saved list, called by every creation entry point.
     */
    fun saveLocation(location: SavedLocation) {
        _savedLocations.value = SavedLocationStore.upsert(_savedLocations.value, location)
        persistSavedLocations()
    }

    fun renameSavedLocation(id: String, name: String) {
        _savedLocations.value = SavedLocationStore.rename(_savedLocations.value, id, name)
        persistSavedLocations()
    }

    fun deleteSavedLocation(id: String) {
        _savedLocations.value = SavedLocationStore.delete(_savedLocations.value, id)
        persistSavedLocations()
    }

    /** Demotes a pinned place to the general list without deleting it. */
    fun clearSavedSlot(id: String) {
        _savedLocations.value = SavedLocationStore.clearSlot(_savedLocations.value, id)
        persistSavedLocations()
    }

    /**
     * The map's long-press now opens the location menu instead of routing
     * straight away — the menu (route / save as Home / save as Work /
     * save as a location) is the one place slots get set from.
     */
    fun onMapLongPress(point: GeoPoint) {
        _locationMenuPoint.value = point
    }

    /** The menu's Route button: the same choke point every destination
     * uses, then the menu goes away. */
    fun routeHere(point: GeoPoint) {
        _locationMenuPoint.value = null
        requestRoute(point)
    }

    /**
     * The menu's save buttons: Home/Work land in their slots (replacing
     * any previous occupant), a plain save appends. Both clear the menu —
     * the point is consumed either way.
     */
    fun setSlotFromMenu(slot: SavedSlot) {
        val point = _locationMenuPoint.value ?: return
        saveLocation(
            SavedLocation(
                id = SavedLocationStore.newId(),
                name = _locationMenuLabel.value ?: slot.defaultLabel(),
                lon = point.lon,
                lat = point.lat,
                slot = slot,
            ),
        )
        _locationMenuPoint.value = null
        _locationMenuLabel.value = null
        toast("Set as ${slot.defaultLabel()}")
    }

    fun saveMenuPoint() {
        val point = _locationMenuPoint.value ?: return
        saveLocation(
            SavedLocation(
                id = SavedLocationStore.newId(),
                name = _locationMenuLabel.value ?: DEFAULT_PIN_NAME,
                lon = point.lon,
                lat = point.lat,
            ),
        )
        _locationMenuPoint.value = null
        _locationMenuLabel.value = null
        toast("Location saved")
    }

    /**
     * Search popover's star: save the place, or — when that point is
     * already in the saved list — unsave it. Matched by coordinates, so
     * the same place toggles identically across searches.
     */
    fun togglePlaceSaved(place: PlaceHit) {
        val existing = _savedLocations.value.firstOrNull {
            it.lon == place.lon && it.lat == place.lat
        }
        if (existing != null) {
            _savedLocations.value = SavedLocationStore.delete(_savedLocations.value, existing.id)
            toast("Removed \"${place.name}\"")
        } else {
            saveLocation(
                SavedLocation(
                    id = SavedLocationStore.newId(),
                    name = place.name,
                    lon = place.lon,
                    lat = place.lat,
                ),
            )
            toast("Saved \"${place.name}\"")
        }
    }

    /** "Save map center": the always-current idle camera center. */
    fun saveMapCenter() {
        val center = mapCenter
        if (center == null) {
            toast("Move the map a little first")
            return
        }
        saveLocation(
            SavedLocation(
                id = SavedLocationStore.newId(),
                name = DEFAULT_PIN_NAME,
                lon = center.lon,
                lat = center.lat,
            ),
        )
        toast("Location saved")
    }

    /**
     * Tap on a saved row: the identical single choke point every other
     * destination uses (long-press, search Route, restore, reRoute), so
     * it inherits stop-nav-first, route-job cancel, the archive bounds
     * check and bucket building for free. The Previewing fit-bounds
     * effect on the map owns the camera after that.
     */
    fun goToSavedLocation(location: SavedLocation) {
        _activeTab.value = Tab.MAP
        requestRoute(GeoPoint(location.lon, location.lat))
    }

    /**
     * Settings' "Prepare all routing data": every region the archive
     * covers, built in the background `:graph` process — progress lands
     * in the same status banner an on-demand build uses.
     */
    fun prepareAllRoutingData() {
        _activeTab.value = Tab.MAP
        viewModelScope.launch {
            GraphBuildCoordinator.startAll(app)
        }
    }

    /**
     * Settings' "Rebuild routing data": the recovery path after an app
     * update changed the routing profile assets — the old `.rd5` buckets
     * were built against the old data and must not survive it. Wipes
     * everything, then prepares the current area exactly like a fresh
     * import would.
     */
    fun rebuildRoutingData() {
        // A live session is reading those segments right now; wiping
        // under a driver is never the user's intent.
        if (navState.value !is NavigationCoordinator.NavState.Idle) {
            toast("Stop navigation before rebuilding routing data")
            return
        }
        _activeTab.value = Tab.MAP
        // The route the UI drops here is dropped everywhere: a rebuild
        // tears the map's state down, and a later process death must
        // not resurrect the route against the freshly-wiped data.
        routeJob?.cancel()
        lastDestination = null
        clearPersistedRoute()
        _routeState.value = RouteUiState.Idle
        viewModelScope.launch {
            GraphBuildCoordinator.cancel(app)
            GraphBuildCoordinator.wipeRoutingData(app)
            GraphBuildCoordinator.triggerLocalBuild(app)
        }
    }

    /**
     * Settings' "Install routing data": adopts a prebuilt routing ZIP — the
     * CI artifact paired with one of the installed map regions. The ZIP's
     * manifest pins the archive fingerprint, so the target region resolves
     * by fingerprint (NOT pick order), and a ZIP no installed region was
     * built from is skipped with a toast rather than installing silently
     * wrong roads. This is also the recovery path when a region's routing
     * data was never installed alongside its archive.
     */
    fun installRoutingData(uri: Uri) {
        // Same rule as rebuild: a live session reads those segment files.
        if (navState.value !is NavigationCoordinator.NavState.Idle) {
            toast("Stop navigation before installing routing data")
            return
        }
        // A route mid-Preparation is waiting on the very build this
        // install would cancel — and unlike rebuildRoutingData, an install
        // does not tear the route down, so the route would keep polling a
        // build it can no longer observe and die with a misleading
        // message. Make the user land the route first instead.
        if (routeState.value is RouteUiState.Preparing) {
            toast("Wait for the route to finish preparing before installing routing data")
            return
        }
        // Single flight, like the import flow: two adoptions share the
        // manager's fixed adopt-scratch dir and build-state tmp file, and
        // the second would delete the first's extracted segments
        // mid-validation.
        if (install_job?.isActive == true) {
            toast("Routing data install is already running")
            return
        }
        install_job = viewModelScope.launch {
            try {
                // A live :graph build writes the same segments dir and
                // build-state file the adoption renames into — stop it
                // first (the same handshake the import flow uses).
                awaitStoppedBuild()
                installPairedRoutingZip(uri, repository.loadRegions())
            } catch (e: CancellationException) {
                // ViewModel cleared mid-install: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"
                toast("Routing data was not installed ($reason)")
            }
        }
    }

    /**
     * Settings' "Load search index": adopts the prebuilt search index —
     * the CI artifact paired with one of the installed map regions. Same
     * fingerprint pairing as the routing install: the DB file's name pins
     * the region's [RegionInfo.searchFingerprint], so the target region
     * resolves by content, and a ZIP no installed region was built from is
     * skipped with a toast rather than installing a silently-wrong search.
     */
    fun installSearchData(uri: Uri) {
        // Single flight: two adoptions share the coordinator's fixed
        // adopt-scratch dir, and the second would delete the first's
        // extracted DB mid-validation.
        if (search_install_job?.isActive == true) {
            toast("Search index install is already running")
            return
        }
        search_install_job = viewModelScope.launch {
            try {
                // A running pass (either kind) writes files the adoption
                // renames into — stop it first, same handshake the routing
                // install uses. Cancel is cooperative, so wait it out.
                awaitStoppedBuild()
                installPairedSearchZip(uri, repository.loadRegions())
            } catch (e: CancellationException) {
                // ViewModel cleared mid-install: not a user-facing failure.
                throw e
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"
                toast("Search index was not installed ($reason)")
            }
        }
    }

    /**
     * Settings' "Rebuild search index": the DBs are wiped and the pass
     * re-runs through the background service — progress lands in the
     * same status banner routing uses. Like the other rebuild actions,
     * it leaves the Settings tab for the Map tab.
     */
    fun rebuildSearchIndex() {
        if (_state.value !is AtlasUiState.MapReady) return
        _activeTab.value = Tab.MAP
        viewModelScope.launch {
            awaitStoppedBuild()
            SearchCoordinator.deleteIndexes(app)
            ensureSearchIndex(force = true)
        }
    }

    /**
     * Stops any live `:graph` build (either kind) and waits for the
     * service to unwind — the same handshake installRoutingData uses,
     * shared now that search builds in the service too. Used by the
     * Settings install/rebuild paths before they touch the files a live
     * run is writing.
     */
    private suspend fun awaitStoppedBuild() {
        if (GraphBuildCoordinator.readStatusAsync(app)?.running != true) return
        toast("Stopping the running build first…")
        GraphBuildCoordinator.cancel(app)
        while (true) {
            val status = GraphBuildCoordinator.readStatusAsync(app)
            if (status?.running != true) break
            if (System.currentTimeMillis() - status.timestampMs > BUILD_STOP_STALE_MS) break
            delay(2_000)
        }
    }

    /** The Failed drawer's Retry: same destination, fresh attempt. */
    fun reRoute() {
        lastDestination?.let { requestRoute(it) }
    }

    fun dismissRoute() {
        if (navState.value !is NavigationCoordinator.NavState.Idle) {
            // The coordinator directly, NOT stopNavigation(): that one
            // delegates back here on arrival, so the pair would recurse.
            NavigationCoordinator.stop(app)
        }
        routeJob?.cancel()
        lastDestination = null
        clearPersistedRoute()
        _routeState.value = RouteUiState.Idle
    }

    /**
     * Onboarding's multi-region import (and, via [addRegions], the Settings
     * "Add map region" flow): copies every selected map archive, then
     * installs the routing ZIPs, then the search-index ZIPs, then triggers
     * the search pass for anything still un-indexed.
     *
     * Pairing is by FINGERPRINT, not pick order: each routing ZIP's
     * manifest names the archive fingerprint it was built from, and each
     * search ZIP's DB file name pins the search fingerprint — the ZIP is
     * adopted by whichever installed region carries that fingerprint, and
     * a ZIP matching no region is skipped with a toast, never a failure
     * (the map works; the on-device build is the designed fallback).
     */
    fun importRegions(
        archiveUris: List<Uri>,
        routingZips: List<Uri>,
        searchZips: List<Uri>,
    ) {
        // Two taps can land in one input batch before recomposition removes
        // the Import button: both would copy into the SAME staging file and
        // interleave (or the second job would follow the first's rename).
        if (_state.value is AtlasUiState.Importing) return
        importRegionsInternal(archiveUris, routingZips, searchZips)
    }

    /**
     * Settings' "Add map region": the same shared body as [importRegions] —
     * the only behavioral difference is the prefs wipe below, which is
     * scoped to the empty→non-empty transition or a primary-fingerprint
     * change, so adding a second region never drops the saved camera.
     */
    fun addRegions(
        archiveUris: List<Uri>,
        routingZips: List<Uri> = emptyList(),
        searchZips: List<Uri> = emptyList(),
    ) = importRegionsInternal(archiveUris, routingZips, searchZips)

    private fun importRegionsInternal(
        archive_uris: List<Uri>,
        routing_zips: List<Uri>,
        search_zips: List<Uri>,
    ) {
        if (archive_uris.isEmpty()) return
        // A replace chosen from Settings: the import dialog must be the
        // topmost surface, so the user sees its progress.
        _activeTab.value = Tab.MAP
        // The replaced region's route must not survive the import: its
        // in-memory preview would paint over the new tiles and the fit
        // would fly the camera to the old city, with a Start button
        // offering navigation on a route computed against the replaced
        // graph. The prefs wipe below only covers the restore path —
        // the live route needs the full teardown.
        dismissRoute()
        val regions_before = repository.loadRegions()
        val primary_before = RegionStore.primaryRegion(regions_before, cameraAnchor())
        _state.value = AtlasUiState.Importing(null)
        viewModelScope.launch {
            try {
                // 1. Copy every archive first — a region that fails its copy
                // fails the import (the map cannot render without it), while
                // the optional ZIPs below degrade to toasts.
                var copied = 0
                for (uri in archive_uris) {
                    val label = repository.displayName(uri) ?: "map archive"
                    val detail = if (archive_uris.size > 1) {
                        "$label (${copied + 1} of ${archive_uris.size})"
                    } else {
                        label
                    }
                    _state.value = AtlasUiState.Importing(null, ImportStage.COPY_ARCHIVE, detail)
                    repository.importRegion(uri) { progress ->
                        _state.value =
                            AtlasUiState.Importing(progress, ImportStage.COPY_ARCHIVE, detail)
                    }
                    copied++
                }
                val regions_after = repository.loadRegions()
                // 2. Routing ZIPs, paired by manifest fingerprint. A failure
                // is NOT an import failure: the map works and the on-device
                // build is the designed fallback, so the message surfaces
                // as a toast and the flow continues.
                var routing_index = 0
                for (zip in routing_zips) {
                    val label = repository.displayName(zip) ?: "routing data"
                    _state.value = AtlasUiState.Importing(
                        null,
                        ImportStage.INSTALL_ROUTING,
                        if (routing_zips.size > 1) {
                            "$label (${routing_index + 1} of ${routing_zips.size})"
                        } else {
                            label
                        },
                    )
                    installPairedRoutingZip(zip, regions_after)
                    routing_index++
                }
                // 3. Search-index ZIPs, paired by DB-file-name fingerprint —
                // same skip-don't-fail contract. The adoptions join the
                // search pass's write lock, so unwind any running pass
                // FIRST (cancel() alone is asynchronous, and a still-"active"
                // pass would make the adopt refuse and the import leave the
                // region unindexed until the next launch).
                awaitStoppedBuild()
                var search_index = 0
                for (zip in search_zips) {
                    val label = repository.displayName(zip) ?: "search index"
                    _state.value = AtlasUiState.Importing(
                        null,
                        ImportStage.INSTALL_SEARCH,
                        if (search_zips.size > 1) {
                            "$label (${search_index + 1} of ${search_zips.size})"
                        } else {
                            label
                        },
                    )
                    installPairedSearchZip(zip, regions_after)
                    search_index++
                }
                // 4. Everything still un-indexed (no adopted ZIP, or a ZIP
                // that failed) builds through the background pass.
                ensureSearchIndex()
                _state.value = readyState(regions_after)
                    ?: error("the map regions vanished during the import")
                // A new region must not inherit the previous install's
                // dismissed-build tombstones — either kind.
                GraphBuildCoordinator.setBuildDismissed(app, false)
                GraphBuildCoordinator.setSearchDismissed(app, false)
                // The camera/destination wipe is SCOPED: only a fresh
                // install (empty→non-empty) or a primary-region UPDATE (the
                // same region re-imported from a newer build carries a
                // different fingerprint) invalidates the saved view —
                // adding a second region must not drop the camera. The
                // wiped keys are all region-derived state; user-curated
                // data (saved.locations, tts.muted) is deliberately NOT in
                // this list, and a stale destination coordinate degrades
                // honestly via requestRoute's bounds check → Failed drawer.
                val empty_to_nonempty = regions_before.isEmpty()
                val primary_changed = primary_before?.let { before ->
                    regions_after.firstOrNull { it.id == before.id }
                        ?.routingFingerprint != before.routingFingerprint
                } ?: false
                if (empty_to_nonempty || primary_changed) {
                    prefs.edit()
                        .remove(KEY_CAMERA_LON)
                        .remove(KEY_CAMERA_LAT)
                        .remove(KEY_CAMERA_ZOOM)
                        .remove(KEY_CAMERA_BEARING)
                        .remove(KEY_DEST_LON)
                        .remove(KEY_DEST_LAT)
                        .remove(KEY_PROFILE)
                        .apply()
                    initial_camera = null
                }
                // The warmup is keyed to nothing region-specific — but a
                // fresh import is the natural moment to ensure it has run
                // for THIS region's routing data.
                warmEngine()
            } catch (e: Exception) {
                _state.value = AtlasUiState.ImportFailed(
                    e.message ?: "the map archive could not be imported",
                )
            }
        }
    }

    /**
     * Adopts one routing ZIP into the installed region whose
     * [RegionInfo.routingFingerprint] matches the ZIP's manifest. The ZIP
     * is staged to the cache dir first — the manifest read needs the
     * central directory (ZipInputStream refuses GitHub-artifact ZIPs), and
     * the staged copy is what the adoption consumes, so the bytes are
     * read exactly twice: once here, once into the segments dir.
     * Unmatched / unreadable ZIPs degrade to a toast, never a failure.
     */
    private suspend fun installPairedRoutingZip(zip: Uri, regions: List<RegionInfo>) {
        var staged: File? = null
        try {
            val staged_file = stageZipForPairing(zip, "the routing data file")
            staged = staged_file
            val fingerprint = routingZipFingerprint(staged_file)
            val region = fingerprint?.let { fp ->
                regions.firstOrNull { it.routingFingerprint == fp }
            }
            if (region == null) {
                toast(
                    "\"${zipDisplayName(zip)}\" was not built from any installed map " +
                        "region — skipped. Atlas will prepare routing on this device instead.",
                )
                return
            }
            GraphBuildCoordinator.installRoutingData(app, region.id, Uri.fromFile(staged_file))
            toast("Routing data installed for ${region.displayName} — routing is ready.")
        } catch (e: CancellationException) {
            // The import coroutine was cancelled (ViewModel cleared): not a
            // user-facing failure, and the generic catch below would toast
            // a false "was not installed" from it.
            throw e
        } catch (e: Exception) {
            // The adopt path throws user-actionable messages by design;
            // anything that slips through with a null or blank message
            // must not render as "(null)".
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"
            toast(
                "Routing data was not installed ($reason) — " +
                    "Atlas will prepare routing on this device instead.",
            )
        } finally {
            staged?.delete()
        }
    }

    /**
     * Adopts one search-index ZIP into the installed region whose
     * [RegionInfo.searchFingerprint] matches the ZIP's DB file name — the
     * same pairing the adoption itself validates against its manifest.
     * Unmatched / unreadable ZIPs degrade to a toast, never a failure.
     */
    private suspend fun installPairedSearchZip(zip: Uri, regions: List<RegionInfo>) {
        var staged: File? = null
        var input: java.io.InputStream? = null
        try {
            val staged_file = stageZipForPairing(zip, "the search index file")
            staged = staged_file
            val fingerprint = searchZipFingerprint(staged_file)
            val region = fingerprint?.let { fp ->
                regions.firstOrNull { it.searchFingerprint == fp }
            }
            if (region == null) {
                toast(
                    "\"${zipDisplayName(zip)}\" was not built from any installed map " +
                        "region — skipped. Atlas will build the search index on this device instead.",
                )
                return
            }
            val stream = app.contentResolver.openInputStream(Uri.fromFile(staged_file))
                ?: error("the search index file could not be opened")
            input = stream
            val adoption = SearchCoordinator.adoptPrebuiltIndex(app, region, stream)
            toast(
                "Search index installed for ${region.displayName} — ${adoption.places} places " +
                    "and ${adoption.addresses} addresses are searchable.",
            )
        } catch (e: CancellationException) {
            // Same reasoning as the routing pairing above.
            throw e
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"
            toast(
                "Search index was not installed ($reason) — Atlas will build " +
                    "the search index on this device instead.",
            )
        } finally {
            input?.close()
            staged?.delete()
        }
    }

    /**
     * Copies a routing/search ZIP into the cache dir so its central
     * directory can be read: the CI artifacts' STORED entries carry data
     * descriptors ZipInputStream refuses, so pairing needs a real file
     * ([stageZip]). Bounded by the same caps the adoption uses — a huge or
     * hostile file cannot fill the partition with an opaque ENOSPC.
     */
    private suspend fun stageZipForPairing(zip: Uri, what: String): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val staged = File(app.cacheDir, "pairing-" + System.nanoTime() + ".zip")
            try {
                val input = app.contentResolver.openInputStream(zip)
                    ?: error("$what could not be opened")
                input.use { stageZip(it, staged, PAIRING_ZIP_CAP_BYTES, PAIRING_FREE_FLOOR_BYTES, what) }
                staged
            } catch (e: Exception) {
                staged.delete()
                throw e
            }
        }

    /**
     * The archive fingerprint a staged routing ZIP's manifest pins, or
     * null when the ZIP carries no manifest (a hand-made ZIP — the
     * adoption's "trust the user" case has no fingerprint to pair by).
     * A malformed manifest throws: that is a genuinely broken ZIP.
     */
    private fun routingZipFingerprint(staged: File): String? {
        val zf = java.util.zip.ZipFile(staged)
        try {
            val entry = zf.entries().asSequence()
                .firstOrNull { it.name.substringAfterLast('/') == "manifest.json" }
                ?: return null
            val out = java.io.ByteArrayOutputStream()
            copyBounded(zf.getInputStream(entry), out, PAIRING_MANIFEST_CAP_BYTES, "the routing data manifest")
            return parseRoutingManifest(out.toString(Charsets.UTF_8)).first
        } finally {
            zf.close()
        }
    }

    /**
     * The search fingerprint a staged search ZIP pins, read from the DB
     * entry's name (`search-<fingerprint>.db` — the fingerprint IS the
     * file name, per SearchIndexer.databaseFile), or null when no DB entry
     * is present (nothing to pair by — skipped, not failed).
     */
    private fun searchZipFingerprint(staged: File): String? {
        val zf = java.util.zip.ZipFile(staged)
        try {
            val name = zf.entries().asSequence()
                .map { it.name.substringAfterLast('/') }
                .firstOrNull { SEARCH_DB_ENTRY_RE.matchEntire(it) != null }
                ?: return null
            return SEARCH_DB_ENTRY_RE.matchEntire(name)!!.groupValues[1]
        } finally {
            zf.close()
        }
    }

    /** A picked ZIP's display name for toasts and progress detail. */
    private fun zipDisplayName(zip: Uri): String = repository.displayName(zip) ?: "the selected file"

    /** The one user-facing channel while no map/dialog surface exists yet. */
    private fun toast(message: String) {
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
    }

    fun dismissError() {
        _state.value = AtlasUiState.NeedsArchive
    }

    /**
     * Removes one installed region: the registry entry, its map tiles, its
     * routing segments and its search DB + completion marker — the whole
     * per-region footprint. The last removal lands back in NeedsArchive.
     */
    fun removeRegion(region: RegionInfo) {
        // A live session may be reading exactly the segments (or snapping
        // against the tiles) this delete removes — never under a driver.
        if (navState.value !is NavigationCoordinator.NavState.Idle) {
            toast("Stop navigation before removing map data")
            return
        }
        viewModelScope.launch {
            try {
                // A running :graph build (either kind) may hold the region's
                // segment files or search DB open — stop it first, the same
                // handshake the install paths use.
                awaitStoppedBuild()
                RegionStore.remove(app, region.id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Per-region routing segments (WP3's per-region layout);
                    // a missing dir is already a no-op for deleteRecursively.
                    File(File(app.filesDir, "graph"), "segments/${region.id}").deleteRecursively()
                    // The region's search index, fingerprint-keyed files.
                    val search_dir = SearchCoordinator.searchDir(app)
                    SearchIndexer.databaseFile(search_dir, region.searchFingerprint).delete()
                    SearchIndexer.completionFile(search_dir, region.searchFingerprint).delete()
                }
                val regions = repository.loadRegions()
                val next = readyState(regions)
                if (next == null) {
                    // The last region is gone: no tiles to render, no map
                    // state to hold — the onboarding flow starts over. The
                    // route goes with it (nothing left to route against).
                    dismissRoute()
                    _state.value = AtlasUiState.NeedsArchive
                } else {
                    _state.value = next
                }
                toast("Removed ${region.displayName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"
                toast("The region could not be removed ($reason)")
            }
        }
    }

    /**
     * The MapReady state for a region list, with [RegionStore.primaryRegion]
     * resolved against the user's current view — null when the list is
     * empty (→ NeedsArchive).
     */
    private fun readyState(regions: List<RegionInfo>): AtlasUiState? {
        if (regions.isEmpty()) return null
        val primary = RegionStore.primaryRegion(regions, cameraAnchor()) ?: regions.first()
        return AtlasUiState.MapReady(regions, primary)
    }

    /**
     * The anchor the primary region is picked by: where the user is
     * looking (the persisted camera, else the last reported map center).
     * Null before either exists — the newest import wins then.
     */
    private fun cameraAnchor(): Pair<Double, Double>? =
        initial_camera?.let { it.lon to it.lat } ?: mapCenter?.let { it.lon to it.lat }

    private fun initialState(): AtlasUiState {
        // No camera anchor yet at construction time (initial_camera is
        // initialized after _state) — the newest import is primary.
        val regions = repository.loadRegions()
        return readyState(regions) ?: AtlasUiState.NeedsArchive
    }
}

/** The single AtlasViewModel, wired to the app container (manual DI). */
@Composable
fun rememberAtlasViewModel(): AtlasViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as AtlasApplication
    return viewModel(
        factory = viewModelFactory {
            initializer {
                AtlasViewModel(
                    app,
                    app.container.pmtilesRepository,
                    createSavedStateHandle(),
                )
            }
        },
    )
}

private const val KEY_DEST_LON = "route.destination.lon"
private const val KEY_DEST_LAT = "route.destination.lat"
private const val KEY_PROFILE = "route.profile"
private const val KEY_SAVED_LOCATIONS = "saved.locations"

/** The floating-banner switch, same dot-namespaced Boolean shape as the mute. */
private const val KEY_OVERLAY_ENABLED = "overlay.enabled"

// The camera keys live in the SAME prefs as the route-destination keys
// (same names as the SavedStateHandle ones above are fine — prefs and
// saved instance state are separate stores).
private const val KEY_CAMERA_LON = "camera.lon"
private const val KEY_CAMERA_LAT = "camera.lat"
private const val KEY_CAMERA_ZOOM = "camera.zoom"
private const val KEY_CAMERA_BEARING = "camera.bearing"
private const val SEARCH_DEBOUNCE_MS = 250L
// Mirrors GraphPrepFlow's staleness budget: a running status older than
// this means the :graph process died mid-build.
private const val BUILD_STOP_STALE_MS = 90_000L

// The fingerprint-pairing reads are bounded by the same caps the adoption
// paths use: a routing ZIP can be ~GB scale (the CI artifacts are stored
// uncompressed), the manifest a few MB, and 256 MB must stay free before
// any of it is staged to the cache dir.
private const val PAIRING_ZIP_CAP_BYTES = 16L shl 30 // 16 GB
private const val PAIRING_MANIFEST_CAP_BYTES = 4L shl 20 // 4 MB
private const val PAIRING_FREE_FLOOR_BYTES = 256L shl 20 // leave 256 MB free

// The search-index ZIP's DB entry name carries its pairing fingerprint:
// `search-<64 hex>.db` (SearchIndexer.databaseFile's layout).
private val SEARCH_DB_ENTRY_RE = Regex("search-([0-9a-f]{64})\\.db")