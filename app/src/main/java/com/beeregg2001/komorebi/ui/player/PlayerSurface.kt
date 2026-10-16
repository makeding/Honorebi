package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
    val systemSleepAllowed by com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker.systemSleepAllowed.collectAsState()
    // Keep the scene's focus target on a Compose layout. AndroidView's native
    // focus interop cannot focus a controller-free PlayerView on Android TV.
    Box(modifier = Modifier.onFocusChanged { android.util.Log.i("PlayerInputTrace", "surface focus=$it") }.then(modifier), propagateMinConstraints = true) {
      AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                keepScreenOn = !systemSleepAllowed
                this.resizeMode = resizeMode
                viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                    android.util.Log.i("PlayerInputTrace", "native focus old=$old new=$new", Throwable("focus change"))
                }
                onViewCreated(this)
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
            view.keepScreenOn = !systemSleepAllowed
            onViewUpdated(view)
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = Modifier.matchParentSize()
      )
    }
}
