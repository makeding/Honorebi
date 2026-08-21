package com.beeregg2001.komorebi.media

import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import com.beeregg2001.komorebi.data.remote.HonomiRemoteControlClient
import kotlinx.coroutines.delay

internal val LocalSystemMediaSessionController = compositionLocalOf<SystemMediaSessionController?> { null }
internal val LocalSystemMediaSessionEpoch = compositionLocalOf<Long?> { null }

internal fun shouldDispatchRemoteTransport(
    command: HonomiRemoteCommand,
    remoteContentType: String,
    isPlaybackSwitching: Boolean,
): Boolean = when (command) {
    HonomiRemoteCommand.Stop -> true
    HonomiRemoteCommand.Play,
    HonomiRemoteCommand.Pause -> !isPlaybackSwitching
    is HonomiRemoteCommand.SeekRelative -> !isPlaybackSwitching && remoteContentType == "Recorded"
    HonomiRemoteCommand.VolumeUp,
    HonomiRemoteCommand.VolumeDown,
    HonomiRemoteCommand.VolumeMute -> true
    is HonomiRemoteCommand.OpenLive,
    is HonomiRemoteCommand.OpenRecording -> false
}

/** Provides one media-session owner for the current root playback epoch. */
@Composable
fun RootSystemMediaSessionHost(
    playbackSessionEpoch: Long?,
    remoteContentType: String,
    isPlaybackSwitching: Boolean,
    remoteControlClient: HonomiRemoteControlClient,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { SystemMediaSessionController(context) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val currentContentType by rememberUpdatedState(remoteContentType)
    val currentPlaybackSwitching by rememberUpdatedState(isPlaybackSwitching)

    fun sendCurrentState() {
        val state = controller.playbackState()
        remoteControlClient.sendState(
            contentType = if (state == null) "Idle" else currentContentType,
            title = state?.title,
            subtitle = state?.subtitle,
            artworkUrl = state?.artworkUrl,
            isPlaying = state?.isPlaying ?: false,
            isBuffering = currentPlaybackSwitching || state?.isBuffering == true,
            positionSeconds = state?.positionSeconds,
            durationSeconds = state?.durationSeconds,
            canSeek = !currentPlaybackSwitching && currentContentType == "Recorded" && state?.canSeek == true,
        )
    }

    LaunchedEffect(controller, remoteControlClient) {
        remoteControlClient.commands.collect { command ->
            if (!shouldDispatchRemoteTransport(command, currentContentType, currentPlaybackSwitching)) return@collect
            when (command) {
                HonomiRemoteCommand.Play -> controller.play()
                HonomiRemoteCommand.Pause -> controller.pause()
                HonomiRemoteCommand.Stop -> controller.stop()
                is HonomiRemoteCommand.SeekRelative -> {
                    if (remoteContentType == "Recorded") {
                        controller.seekRelative((command.deltaSeconds * 1_000).toLong())
                    }
                }
                HonomiRemoteCommand.VolumeUp -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI,
                )
                HonomiRemoteCommand.VolumeDown -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
                HonomiRemoteCommand.VolumeMute -> audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI,
                )
                is HonomiRemoteCommand.OpenLive,
                is HonomiRemoteCommand.OpenRecording -> Unit
            }
        }
    }

    LaunchedEffect(controller, remoteControlClient, remoteContentType, playbackSessionEpoch) {
        while (true) {
            sendCurrentState()
            delay(5_000)
        }
    }

    // プレイヤーの外部更新は即時通知し、上記の5秒周期送信は位置更新と取りこぼしの補完に限定する。
    DisposableEffect(controller, remoteControlClient, remoteContentType, playbackSessionEpoch) {
        controller.setPlaybackStateChangedListener(::sendCurrentState)
        onDispose {
            controller.setPlaybackStateChangedListener(null)
        }
    }

    // Parent effects are installed before the child SystemMediaSession effects,
    // so an attachment can only acquire the epoch that currently owns the root.
    DisposableEffect(controller, playbackSessionEpoch) {
        playbackSessionEpoch?.let(controller::openEpoch)
        onDispose {
            playbackSessionEpoch?.let(controller::closeEpoch)
        }
    }

    CompositionLocalProvider(
        LocalSystemMediaSessionController provides controller,
        LocalSystemMediaSessionEpoch provides playbackSessionEpoch,
        content = content,
    )
}
