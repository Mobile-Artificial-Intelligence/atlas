package com.danemadsen.atlas.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the platform [TextToSpeech] engine for navigation announcements.
 *
 * Init is asynchronous and can fail (no engine installed, engine dies):
 * [available] stays false and the caller degrades to banner-only mode —
 * the plan's designed fallback. Speaking before init simply drops the
 * sentence; the banner carries the same information.
 *
 * Not thread-safe by contract: the navigation runtime calls it from its
 * single fix-collecting coroutine, and [shutdown] from teardown. The
 * [ready] flag is atomic only because the TTS callback thread writes it.
 */
class TtsSpeaker(context: Context) {

    /** True once the engine initialized successfully. */
    val available: Boolean get() = ready.get()

    /** Mute toggle: the banner keeps working; TTS goes quiet. */
    @Volatile var muted: Boolean = false

    private val ready = AtomicBoolean(false)
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.set(true)
    }

    /** Speaks [text], replacing whatever was mid-announcement. */
    fun speak(text: String) {
        if (muted || !ready.get()) return
        // The plain Locale constructor falls back to the engine default,
        // which matches the device language the instructions are not
        // translated against anyway (they are English-only for now).
        runCatching { tts.setSpeechRate(1.0f) }
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "atlas-nav-$announce_seq") }
        announce_seq++
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private var announce_seq = 0
}