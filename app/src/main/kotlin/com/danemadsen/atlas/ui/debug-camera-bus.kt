package com.danemadsen.atlas.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A debug deep link for driving the map camera from adb:
 *
 *   adb shell am start -n com.danemadsen.atlas/.AtlasActivity \
 *       -d "atlas://camera?lon=144.963&lat=-37.814&zoom=15.5&bearing=45"
 *
 * The activity parses the URI and emits here; the map screen collects and
 * moves the camera. Harmless in any build — it only moves the camera — and
 * it keeps acceptance tests free of fragile touch-injection timing.
 */
object DebugCameraBus {

    data class Request(
        val lon: Double,
        val lat: Double,
        val zoom: Double,
        // bearing 0 = north-up; a non-zero bearing also fades in the
        // compass widget, which is how the compass placement is verified.
        val bearing: Double = 0.0,
    )

    // replay = 1: the activity's onCreate emits long before the map's
    // collector exists (composition + getMapAsync take a second or more),
    // and without replay the cold-start deep link is silently dropped.
    private val _requests = MutableSharedFlow<Request>(replay = 1, extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    fun emit(request: Request) {
        _requests.tryEmit(request)
    }

    /**
     * Drops the replayed request after the map has applied it. Without
     * this, a map recreated later in the same process (an archive replace
     * tears the whole map down) receives the stale camera as a fresh
     * request — flying to a position that may be outside the new archive
     * and suppressing the first style load's archive fit.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeReplay() {
        _requests.resetReplayCache()
    }
}