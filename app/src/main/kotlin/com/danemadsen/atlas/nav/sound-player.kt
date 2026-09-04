package com.danemadsen.atlas.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.danemadsen.atlas.R

/**
 * The navigation sound effects: short, ear-catching cues the user's eyes
 * don't have to be on the screen for. TTS carries the sentence; these
 * carry the moment.
 *
 *  - [Sound.GPS_CONNECTED] / [Sound.GPS_DISCONNECTED] — the fix stream
 *    came back after a gap, or has been silent past the lost threshold.
 *  - [Sound.TURN_NOW] — the maneuver is at the wheel (the turn is being
 *    executed).
 *  - [Sound.TURN_MISSED] — the off-route streak fired a re-route.
 */
class SoundPlayer(context: Context) {

    enum class Sound { GPS_CONNECTED, GPS_DISCONNECTED, TURN_NOW, TURN_MISSED }

    private val sound_pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val sound_ids = mutableMapOf<Sound, Int>()

    init {
        for (sound in Sound.entries) {
            sound_ids[sound] = sound_pool.load(context, sound.resId(), 1)
        }
    }

    /** Fire-and-forget; overlapping cues are capped at one stream. */
    fun play(sound: Sound) {
        val id = sound_ids[sound] ?: return
        sound_pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        sound_pool.release()
        sound_ids.clear()
    }

    private fun Sound.resId(): Int = when (this) {
        Sound.GPS_CONNECTED -> R.raw.gps_connected
        Sound.GPS_DISCONNECTED -> R.raw.gps_disconnected
        Sound.TURN_NOW -> R.raw.turn_now
        Sound.TURN_MISSED -> R.raw.turn_missed
    }
}