package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/** One Android audio-focus owner for all live slots, including the muted picture. */
internal class LiveAudioFocus(context: Context, private val onPlaybackAllowed: (Boolean) -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var requested = false
    var playbackAllowed = false
        private set
    val isAwaitingGain: Boolean get() = requested && !playbackAllowed
    private val listener = AudioManager.OnAudioFocusChangeListener(::onFocusChange)
    private val request: FocusRequest = if (Build.VERSION.SDK_INT >= 26) {
        Api26FocusRequest(manager, listener)
    } else {
        LegacyFocusRequest(manager, listener)
    }

    fun acquire(): Boolean {
        if (!requested) {
            requested = request.acquire() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
                request.release()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> playbackAllowed = false
            else -> return
        }
        onPlaybackAllowed(playbackAllowed)
    }

    fun release() {
        if (requested) request.release()
        requested = false
        playbackAllowed = false
    }
}

private interface FocusRequest {
    fun acquire(): Int
    fun release()
}

@Suppress("DEPRECATION")
private class LegacyFocusRequest(
    private val manager: AudioManager,
    private val listener: AudioManager.OnAudioFocusChangeListener,
) : FocusRequest {
    override fun acquire(): Int = manager.requestAudioFocus(
        listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
    )
    override fun release() { manager.abandonAudioFocus(listener) }
}

@RequiresApi(26)
private class Api26FocusRequest(
    private val manager: AudioManager,
    listener: AudioManager.OnAudioFocusChangeListener,
) : FocusRequest {
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
        .build()

    override fun acquire(): Int = manager.requestAudioFocus(request)
    override fun release() { manager.abandonAudioFocusRequest(request) }
}
