package com.beeregg2001.komorebi.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import com.beeregg2001.komorebi.data.remote.HonomiRemoteControlClient
import kotlinx.coroutines.delay

internal val LocalSystemMediaSessionController = compositionLocalOf<SystemMediaSessionController?> { null }
internal val LocalSystemMediaSessionEpoch = compositionLocalOf<Long?> { null }

/** Provides one media-session owner for the current root playback epoch. */
@Composable
fun RootSystemMediaSessionHost(
    playbackSessionEpoch: Long?,
    remoteContentType: String,
    remoteControlClient: HonomiRemoteControlClient,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { SystemMediaSessionController(context) }

    fun sendCurrentState() {
        val state = controller.playbackState()
        remoteControlClient.sendState(
            contentType = if (state == null) "Idle" else remoteContentType,
            title = state?.title,
            subtitle = state?.subtitle,
            artworkUrl = state?.artworkUrl,
            isPlaying = state?.isPlaying ?: false,
            isBuffering = state?.isBuffering ?: false,
            positionSeconds = state?.positionSeconds,
            durationSeconds = state?.durationSeconds,
            canSeek = remoteContentType == "Recorded" && state?.canSeek == true,
        )
    }

    LaunchedEffect(controller, remoteControlClient) {
        remoteControlClient.commands.collect { command ->
            when (command) {
                HonomiRemoteCommand.Play -> controller.play()
                HonomiRemoteCommand.Pause -> controller.pause()
                HonomiRemoteCommand.Stop -> controller.stop()
                is HonomiRemoteCommand.SeekRelative -> {
                    if (remoteContentType == "Recorded") {
                        controller.seekRelative((command.deltaSeconds * 1_000).toLong())
                    }
                }
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
