package com.beeregg2001.komorebi.ui.player

import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared crop state. Playback modes keep their own input profile, not their own state model. */
enum class PlayerCropMode { HIDDEN, MENU, DIRECT_ADJUST }

enum class PlayerZoomOrigin { TopLeft, TopRight, BottomLeft, BottomRight }

@Stable
class PlayerCropState {
    var isEnabled by mutableStateOf(false)
    var mode by mutableStateOf(PlayerCropMode.HIDDEN)
    var zoomPercent by mutableFloatStateOf(100f)
    var xPercent by mutableFloatStateOf(0f)
    var yPercent by mutableFloatStateOf(0f)
    var origin by mutableStateOf(PlayerZoomOrigin.TopRight)

    fun reset() {
        zoomPercent = 100f
        xPercent = 0f
        yPercent = 0f
        origin = PlayerZoomOrigin.TopRight
    }
}

/** Deliberate differences in the existing live and recorded crop remotes. */
data class PlayerCropInputProfile(
    val movementStep: Float,
    val clampPosition: Boolean,
    val supportsZoomKeys: Boolean,
    val centerCyclesZoom: Boolean,
    val consumeUnhandledKey: Boolean
)

val LivePlayerCropInputProfile = PlayerCropInputProfile(
    movementStep = 2f,
    clampPosition = false,
    supportsZoomKeys = false,
    centerCyclesZoom = true,
    consumeUnhandledKey = false
)

val RecordedPlayerCropInputProfile = PlayerCropInputProfile(
    movementStep = 5f,
    clampPosition = true,
    supportsZoomKeys = true,
    centerCyclesZoom = false,
    consumeUnhandledKey = true
)

/**
 * Handles the crop-adjust remote contract without knowing which player owns the crop.
 * Returns false only when the key remains available to the owning scene.
 */
fun PlayerCropState.handleDirectAdjustKey(
    keyCode: Int,
    isActionDown: Boolean,
    profile: PlayerCropInputProfile,
    onReturnToMenu: () -> Unit = {}
): Boolean {
    if (mode != PlayerCropMode.DIRECT_ADJUST) return false

    val isDirection = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    val isZoomKey = profile.supportsZoomKeys &&
        (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND)
    val isExitKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE

    if (!isDirection && !isZoomKey && !isExitKey) return profile.consumeUnhandledKey
    if (!isActionDown) return true

    fun position(value: Float): Float = if (profile.clampPosition) value.coerceIn(0f, 100f) else value
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> yPercent = position(yPercent - profile.movementStep)
        KeyEvent.KEYCODE_DPAD_DOWN -> yPercent = position(yPercent + profile.movementStep)
        KeyEvent.KEYCODE_DPAD_LEFT -> xPercent = position(xPercent - profile.movementStep)
        KeyEvent.KEYCODE_DPAD_RIGHT -> xPercent = position(xPercent + profile.movementStep)
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> zoomPercent =
            (zoomPercent + profile.movementStep).coerceAtMost(200f)
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND -> zoomPercent =
            (zoomPercent - profile.movementStep).coerceAtLeast(100f)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (profile.centerCyclesZoom) {
                zoomPercent = when {
                    zoomPercent < 125f -> 125f
                    zoomPercent < 150f -> 150f
                    zoomPercent < 175f -> 175f
                    zoomPercent < 200f -> 200f
                    else -> 100f
                }
            } else {
                mode = PlayerCropMode.MENU
                onReturnToMenu()
            }
        }
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
            mode = PlayerCropMode.MENU
            onReturnToMenu()
        }
    }
    return true
}
