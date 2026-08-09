package com.beeregg2001.komorebi.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal val LocalSystemMediaSessionController = compositionLocalOf<SystemMediaSessionController?> { null }
internal val LocalSystemMediaSessionEpoch = compositionLocalOf<Long?> { null }

/** Provides one media-session owner for the current root playback epoch. */
@Composable
fun RootSystemMediaSessionHost(
    playbackSessionEpoch: Long?,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val controller = remember(context) { SystemMediaSessionController(context) }

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
