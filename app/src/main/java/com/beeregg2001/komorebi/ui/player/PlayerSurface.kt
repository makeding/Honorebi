package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Owns only PlayerView attachment. Geometry, crop and focus stay in the calling scene. */
@Composable
fun PlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    onViewCreated: (PlayerView) -> Unit = {},
    onViewUpdated: (PlayerView) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                keepScreenOn = true
                this.resizeMode = resizeMode
                onViewCreated(this)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
            view.keepScreenOn = true
            onViewUpdated(view)
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = modifier
    )
}
