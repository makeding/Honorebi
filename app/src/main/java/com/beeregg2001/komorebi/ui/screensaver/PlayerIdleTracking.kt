package com.beeregg2001.komorebi.ui.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.Player

/** Buffering with play requested is playback; pausing buffering retains the paused intent. */
internal fun playerIdleSession(
    playbackState: Int,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    hasError: Boolean,
    info: PausedPlayback,
): PlayerIdleSession = PlayerIdleSession(
    active = isPlaying || (!hasError && playWhenReady &&
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)),
    paused = info.takeIf { !hasError && !playWhenReady &&
        (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) },
    ended = !hasError && playbackState == Player.STATE_ENDED,
)

@Composable
internal fun TrackPlayerIdle(player: Player?, title: String, subtitle: String = "") {
    val info = PausedPlayback(title, subtitle)
    val currentInfo = rememberUpdatedState(info)
    val owner = remember { Any() }
    fun publish() {
        ScreensaverInteractionTracker.updatePlayer(owner, player?.let {
            playerIdleSession(it.playbackState, it.playWhenReady, it.isPlaying, it.playerError != null, currentInfo.value)
        })
    }
    SideEffect { publish() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish() }
        }
        player?.addListener(listener)
        publish()
        onDispose {
            player?.removeListener(listener)
            ScreensaverInteractionTracker.updatePlayer(owner, null)
        }
    }
}
