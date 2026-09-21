package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** One Android audio-focus owner for all live slots, including the muted picture. */
@RequiresApi(26)
internal class LiveAudioFocus(context: Context, private val onPlaybackAllowed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var requested = false
    var playbackAllowed = false
        private set
    val isAwaitingGain: Boolean get() = requested && !playbackAllowed
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, Handler(Looper.getMainLooper()))
        .build()

    fun acquire(): Boolean {
        if (!requested) {
            requested = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            playbackAllowed = requested
        }
        return playbackAllowed
    }

    internal fun onFocusChange(change: Int) {
        if (!requested) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> playbackAllowed = true
            AudioManager.AUDIOFOCUS_LOSS -> {
                playbackAllowed = false
                requested = false
                manager.abandonAudioFocusRequest(request)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> playbackAllowed = false
            else -> return
        }
        onPlaybackAllowed(playbackAllowed)
    }

    fun release() {
        if (requested) manager.abandonAudioFocusRequest(request)
        requested = false
        playbackAllowed = false
    }
}
