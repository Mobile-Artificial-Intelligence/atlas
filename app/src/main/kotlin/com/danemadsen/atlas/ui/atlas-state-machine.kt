package com.danemadsen.atlas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.danemadsen.atlas.AtlasApplication
import com.danemadsen.atlas.data.ArchiveInfo
import com.danemadsen.atlas.data.PmtilesRepository
import com.danemadsen.atlas.routing.GeoPoint
import com.danemadsen.atlas.routing.RouteProfile
import com.danemadsen.atlas.routing.RouteResult
import com.danemadsen.atlas.routing.RouterGateway
import com.danemadsen.atlas.routing.GraphBuildCoordinator
import com.danemadsen.atlas.nav.NavigationCoordinator
import com.danemadsen.atlas.search.PlaceHit
import com.danemadsen.atlas.search.SearchCoordinator
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

/**
 * The whole UI is a function of this state:
 *
 * `NeedsArchive` (fresh install) → `Importing` → `MapReady`,
 * with `ImportFailed` recoverable back to `NeedsArchive`.
 */
sealed interface AtlasUiState {
    data object NeedsArchive : AtlasUiState
    data class Importing(val progress: Float?, val stage: String = STAGE_COPY_ARCHIVE) : AtlasUiState
    data class ImportFailed(val message: String) : AtlasUiState
    data class MapReady(val archive: ArchiveInfo) : AtlasUiState
}

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
    /** The index pass is running — results cannot exist yet. */
    data class Indexing(val indexed: Boolean) : SearchUiState
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

    /** Small persisted app settings (the TTS mute). */
    private val prefs = app.getSharedPreferences("atlas-settings", android.content.Context.MODE_PRIVATE)

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    /** The debounced query job; a new keystroke cancels the old one. */
    private var searchJob: Job? = null
    private var searchIndexJob: Job? = null

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

    /** The Settings overlay's visibility, orthogonal to every other state. */
    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

    /**
     * Voice-guidance mute, persisted so it survives process death and
     * applies to the next session before the first fix can speak. The
     * settings switch and the navigation panel's Mute button both write
     * here — one source of truth, so the two can never disagree.
     */
    private val _ttsMuted = MutableStateFlow(prefs.getBoolean(KEY_TTS_MUTED, false))
    val ttsMuted: StateFlow<Boolean> = _ttsMuted.asStateFlow()

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
        // An archive without an index (a mid-import process death, or an
        // install from before search existed) gets its cheap pass here —
        // search then works on first launch of an existing install too.
        (state.value as? AtlasUiState.MapReady)?.archive?.let { ensureSearchIndex(it) }
        // Same for the engine warmup: with an archive on disk the map is
        // already usable, so the cold-engine cost belongs here in the
        // background, not on the user's first route.
        if ((state.value as? AtlasUiState.MapReady)?.archive != null) warmEngine()
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
            val archive = (state.value as? AtlasUiState.MapReady)?.archive ?: return@launch
            val center = mapCenter ?: GeoPoint(archive.centerLon, archive.centerLat)
            val hits = SearchCoordinator.search(app, archive, query, center.lon, center.lat)
            _searchState.value = SearchUiState.Results(hits)
        }
    }

    /** The map screen consumed the selected place's fly-to. */
    fun onPlaceShown() {
        _selectedPlace.value = null
    }

    /** Drawer row tap: fly the camera there. */
    fun selectPlace(place: PlaceHit) {
        _selectedPlace.value = place
    }

    /** Drawer row Route button: same flow as a long-press destination. */
    fun routeToPlace(place: PlaceHit) {
        requestRoute(GeoPoint(place.lon, place.lat))
    }

    /** Launches the background engine warmup (idempotent per archive). */
    private fun warmEngine() {
        if (warmEngineJob?.isActive == true) return
        warmEngineJob = viewModelScope.launch {
            RouterGateway.warmEngine(app)
        }
    }

    private fun ensureSearchIndex(archive: ArchiveInfo) {
        if (SearchCoordinator.indexExists(app, archive)) return
        if (searchIndexJob?.isActive == true) return
        _searchState.value = SearchUiState.Indexing(false)
        searchIndexJob = viewModelScope.launch {
            try {
                SearchCoordinator.buildCheapIndex(
                    app,
                    archive,
                    com.danemadsen.atlas.data.ArchiveStore.archiveFile(app),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unhandled coroutine exception would take the whole
                // process down — and this runs from init on every cold
                // start while the index is missing, so a persistently
                // failing build would crash-loop the app. Instead the
                // failure surfaces once and search degrades to empty
                // results until the next launch retries it.
                toast(
                    "Search index could not be built — search is unavailable " +
                        "(${e.message?.takeIf { it.isNotBlank() } ?: "an unexpected error"})",
                )
            } finally {
                _searchState.value = SearchUiState.Idle
            }
        }
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
            // A long-press on the gray void beyond the archive's tiles can
            // never route — fail before spending a build on it.
            val archive = repository.loadArchiveInfo()
            if (archive == null ||
                !GraphBuildCoordinator.insideArchive(destination.lon, destination.lat, archive)
            ) {
                failRoute("the destination is outside the loaded map area")
                return@launch
            }
            val origin = GraphBuildCoordinator.currentLocationInArchive(app)
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
        // leaving the Settings tab open behind it would resurface the
        // moment the session ends.
        _settingsOpen.value = false
        NavigationCoordinator.start(app, result, _ttsMuted.value)
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

    /** The navigation panel's Mute/Unmute. */
    fun toggleMute() {
        val muted = !_ttsMuted.value
        _ttsMuted.value = muted
        prefs.edit().putBoolean(KEY_TTS_MUTED, muted).apply()
        // The live session (if any) flips too, so what speaks matches
        // what the settings switch shows.
        if (navState.value is NavigationCoordinator.NavState.Navigating) {
            NavigationCoordinator.toggleMute()
        }
    }

    fun openSettings() {
        _settingsOpen.value = true
    }

    fun closeSettings() {
        _settingsOpen.value = false
    }

    /**
     * Settings' "Prepare all routing data": every region the archive
     * covers, built in the background `:graph` process — progress lands
     * in the same status banner an on-demand build uses.
     */
    fun prepareAllRoutingData() {
        _settingsOpen.value = false
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
        _settingsOpen.value = false
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
     * Settings' "Rebuild search index": the DBs are wiped and the cheap
     * pass re-runs (tens of seconds, surfaced through the search state).
     * Like the other two rebuild actions, it leaves the Settings tab —
     * its only progress surface (the "Indexing places" chip) lives in
     * the search bar on the Map tab.
     */
    fun rebuildSearchIndex() {
        val archive = (_state.value as? AtlasUiState.MapReady)?.archive ?: return
        _settingsOpen.value = false
        viewModelScope.launch {
            // cancel() alone is asynchronous — ensureSearchIndex below would
            // see the old job still "active" and return without scheduling
            // anything, leaving search dead until the next launch. Wait for
            // the pass to unwind (it checks cancellation every 256 tiles /
            // zoom bracket) before wiping the DBs it may still hold open.
            searchIndexJob?.cancelAndJoin()
            SearchCoordinator.deleteIndexes(app)
            _searchState.value = SearchUiState.Idle
            ensureSearchIndex(archive)
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

    fun importArchive(uri: Uri, routingDataUri: Uri? = null) {
        // Two taps can land in one input batch before recomposition removes
        // the Import button: both would copy into the SAME staging file and
        // interleave (or the second job would follow the first's rename).
        if (_state.value is AtlasUiState.Importing) return
        // A replace chosen from Settings: the import dialog must be the
        // topmost surface, so the user sees its progress.
        _settingsOpen.value = false
        // The old archive's route must not survive the replace: its
        // in-memory preview would paint over the new tiles and the fit
        // would fly the camera to the old city, with a Start button
        // offering navigation on a route computed against the replaced
        // graph. The prefs wipe below only covers the restore path —
        // the live route needs the full teardown.
        dismissRoute()
        _state.value = AtlasUiState.Importing(null)
        viewModelScope.launch {
            try {
                val info = repository.importArchive(uri) { progress ->
                    _state.value = AtlasUiState.Importing(progress)
                }
                // The prebuilt routing data is the production path; its
                // installation is part of the import, not a background
                // follow-up — the user waits here while segments land.
                // A failure is NOT an import failure: the map works and
                // the on-device build is the designed fallback, so the
                // message surfaces as a toast and the flow continues.
                if (routingDataUri != null) {
                    _state.value = AtlasUiState.Importing(null, STAGE_INSTALL_ROUTING)
                    val buckets = try {
                        GraphBuildCoordinator.installRoutingData(app, routingDataUri)
                    } catch (e: Exception) {
                        // The adopt path throws user-actionable messages by
                        // design; anything that slips through with a null or
                        // blank message must not render as "(null)".
                        val reason = e.message?.takeIf { it.isNotBlank() }
                            ?: "an unexpected error"
                        toast(
                            "Routing data was not installed ($reason) — " +
                                "Atlas will prepare routing on this device instead.",
                        )
                        0
                    }
                    if (buckets > 0) {
                        toast("Routing data installed for $buckets region(s) — routing is ready.")
                    }
                }
                _state.value = AtlasUiState.MapReady(info)
                // A new archive must not inherit the previous archive's
                // dismissed-build tombstone.
                GraphBuildCoordinator.setBuildDismissed(app, false)
                // Nor its camera or any route aimed at the old tiles:
                // both restores are bounds-checked against the new
                // archive, but the clean slate is simpler and correct.
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
                // Nor its search index: unwind the old archive's running
                // pass (if any) BEFORE wiping the DBs — cancel() alone is
                // asynchronous, and the still-"active" job would make
                // ensureSearchIndex return early and leave the new archive
                // unindexed until the next launch. Then wipe the old DBs and
                // build the cheap pass for the new archive (tens of
                // seconds, surfaced through the search state).
                searchIndexJob?.cancelAndJoin()
                SearchCoordinator.deleteIndexes(app)
                ensureSearchIndex(info)
                // The warmup is keyed to nothing archive-specific — but a
                // fresh import is the natural moment to ensure it has run
                // for THIS archive's routing data.
                warmEngine()
            } catch (e: Exception) {
                _state.value = AtlasUiState.ImportFailed(
                    e.message ?: "the archive could not be imported",
                )
            }
        }
    }

    /** The one user-facing channel while no map/dialog surface exists yet. */
    private fun toast(message: String) {
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
    }

    fun dismissError() {
        _state.value = AtlasUiState.NeedsArchive
    }

    private fun initialState(): AtlasUiState =
        repository.loadArchiveInfo()?.let { AtlasUiState.MapReady(it) }
            ?: AtlasUiState.NeedsArchive
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
private const val KEY_TTS_MUTED = "tts.muted"

// The camera keys live in the SAME prefs as the route-destination keys
// (same names as the SavedStateHandle ones above are fine — prefs and
// saved instance state are separate stores).
private const val KEY_CAMERA_LON = "camera.lon"
private const val KEY_CAMERA_LAT = "camera.lat"
private const val KEY_CAMERA_ZOOM = "camera.zoom"
private const val KEY_CAMERA_BEARING = "camera.bearing"
private const val SEARCH_DEBOUNCE_MS = 250L
private const val STAGE_COPY_ARCHIVE = "Copying map archive into app storage…"
private const val STAGE_INSTALL_ROUTING = "Installing prebuilt routing data…"