@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerRuntimeState(
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val videoSize: VideoSize = VideoSize.UNKNOWN,
    val tracks: Tracks = Tracks.EMPTY,
    val error: PlaybackException? = null,
    val renderedFrameGeneration: Int = 0,
)

/** One owned playback slot. All modes share construction, observation and teardown. */
class PlayerRuntime internal constructor(
    val player: ExoPlayer,
) : AutoCloseable {
    constructor(context: Context, profile: PlayerProfile, mediaSourceFactory: MediaSource.Factory? = null) :
        this(PlayerFactory.create(context, profile, mediaSourceFactory))
    private val mutableState = MutableStateFlow(PlayerRuntimeState())
    val state: StateFlow<PlayerRuntimeState> = mutableState.asStateFlow()
    private val listeners = linkedSetOf<Player.Listener>()
    private var released = false

    private fun dispatch(action: (Player.Listener) -> Unit) {
        if (!released) listeners.toList().forEach { if (!released && it in listeners) action(it) }
    }

    private fun publish() {
        if (!released) mutableState.value = mutableState.value.copy(
            playbackState = player.playbackState, isPlaying = player.isPlaying,
            isLoading = player.isLoading, videoSize = player.videoSize,
            tracks = player.currentTracks, error = player.playerError,
        )
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish(); dispatch { it.onEvents(player, events) } }
        override fun onPlaybackStateChanged(playbackState: Int) { publish(); dispatch { it.onPlaybackStateChanged(playbackState) } }
        override fun onIsPlayingChanged(isPlaying: Boolean) { publish(); dispatch { it.onIsPlayingChanged(isPlaying) } }
        override fun onIsLoadingChanged(isLoading: Boolean) { publish(); dispatch { it.onIsLoadingChanged(isLoading) } }
        override fun onVideoSizeChanged(videoSize: VideoSize) { publish(); dispatch { it.onVideoSizeChanged(videoSize) } }
        override fun onTracksChanged(tracks: Tracks) { publish(); dispatch { it.onTracksChanged(tracks) } }
        override fun onPlayerError(error: PlaybackException) { publish(); dispatch { it.onPlayerError(error) } }
        override fun onMetadata(metadata: Metadata) { dispatch { it.onMetadata(metadata) } }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            dispatch { it.onPositionDiscontinuity(oldPosition, newPosition, reason) }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { dispatch { it.onTimelineChanged(timeline, reason) } }
        override fun onRenderedFirstFrame() {
            if (!released) mutableState.value = mutableState.value.copy(renderedFrameGeneration = mutableState.value.renderedFrameGeneration + 1)
            dispatch { it.onRenderedFirstFrame() }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { dispatch { it.onPlayWhenReadyChanged(playWhenReady, reason) } }
    }

    init { player.addListener(listener) }

    fun addListener(listener: Player.Listener): () -> Unit {
        checkThread()
        check(!released) { "Playback slot is released" }
        listeners.add(listener)
        return { checkThread(); listeners.remove(listener); Unit }
    }

    fun load(source: MediaSource, startPositionMs: Long? = null, playWhenReady: Boolean = true) {
        checkActive()
        if (startPositionMs == null) player.setMediaSource(source) else player.setMediaSource(source, startPositionMs)
        reprepare(playWhenReady)
    }

    fun load(item: MediaItem, startPositionMs: Long? = null, playWhenReady: Boolean = true) {
        checkActive()
        if (startPositionMs == null) player.setMediaItem(item) else player.setMediaItem(item, startPositionMs)
        reprepare(playWhenReady)
    }

    fun reprepare(playWhenReady: Boolean = true) { checkActive(); player.prepare(); player.playWhenReady = playWhenReady }
    fun pause() { checkActive(); player.pause() }
    fun play() { checkActive(); player.play() }
    fun stop() { checkActive(); player.stop() }
    fun seekTo(positionMs: Long) { checkActive(); player.seekTo(positionMs) }

    fun release() {
        checkThread()
        if (released) return
        released = true
        listeners.clear()
        player.removeListener(listener)
        try {
            try { player.stop(); player.clearMediaItems() } finally { player.release() }
        } finally {
            mutableState.value = PlayerRuntimeState()
        }
    }

    override fun close() = release()
    private fun checkActive() { checkThread(); check(!released) { "Playback slot is released" } }
    private fun checkThread() { check(Looper.myLooper() == player.applicationLooper) { "Playback commands must run on the player looper" } }
}
