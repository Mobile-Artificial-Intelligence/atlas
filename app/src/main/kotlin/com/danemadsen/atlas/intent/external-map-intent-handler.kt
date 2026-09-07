package com.danemadsen.atlas.intent

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The inbound side of external map integration: turns an incoming Intent
 * into a [MapIntentRequest] and publishes it for the UI process to act on
 * (the ViewModel collects [requests] and drives the existing camera /
 * search / location-menu state — no new state of its own here).
 *
 * Shaped like [com.danemadsen.atlas.ui.DebugCameraBus] because it has the
 * same timing problem: onCreate emits long before composition has built
 * the ViewModel and the map, so the flow replays once and the consumer
 * calls [consumeReplay] after applying the request. The seq number lets
 * the ViewModel tell a genuine re-emission (the same geo: link opened
 * twice) from a replayed one landing again after an activity recreation
 * — only the seq is deduplicated, never the request value.
 */
object ExternalMapIntentHandler {

    /** A request plus its emission order — the dedup key, not the request. */
    data class Event(val seq: Long, val request: MapIntentRequest)

    private val next_seq = AtomicLong(0)

    // replay = 1: the cold-start geo: intent must survive the seconds
    // before the ViewModel's collector exists.
    private val _requests = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 4)
    val requests: SharedFlow<Event> = _requests.asSharedFlow()

    /**
     * Handles one incoming intent. True when it carried a geo: request
     * (published to [requests]); false when the intent is none of ours —
     * the plain launcher launch, for instance — and must be ignored.
     */
    fun handle(intent: Intent?): Boolean {
        val request = MapIntentParser.parse(intent?.data) ?: return false
        _requests.tryEmit(Event(next_seq.incrementAndGet(), request))
        return true
    }

    /**
     * Drops the replayed request once applied, so a map recreated later in
     * the same process (an archive replace tears it down wholesale) does
     * not receive the stale intent as a fresh one.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeReplay() {
        _requests.resetReplayCache()
    }
}