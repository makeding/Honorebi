package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class DataBroadcastingColorKey { Blue, Red, Green, Yellow }

/**
 * Mode-independent remote-input state for BML data broadcasting. It intentionally owns only
 * input sequencing; whether BML is available, visible, or blank stays in the source module.
 */
@Stable
class DataBroadcastingInputState {
    var isColorSelectorVisible by mutableStateOf(false)
        private set
    var selectedColorKey by mutableStateOf(DataBroadcastingColorKey.Blue)
        private set

    private var backKeyDownTimeMs = 0L
    private var backKeyLongPressed = false
    private var pendingBackJob: Job? = null

    fun reset() {
        closeColorSelector()
        cancelPendingBack()
        backKeyDownTimeMs = 0L
        backKeyLongPressed = false
    }

    fun closeColorSelector() {
        isColorSelectorVisible = false
    }

    fun dispatchColorKey(
        colorKey: DataBroadcastingColorKey,
        onColorKey: (DataBroadcastingColorKey) -> Unit,
    ) {
        onColorKey(colorKey)
        closeColorSelector()
    }

    fun handleKeyEvent(
        keyEvent: KeyEvent,
        scope: CoroutineScope,
        onBack: () -> Unit,
        onBlank: () -> Unit,
        onColorKey: (DataBroadcastingColorKey) -> Unit,
        onRemoteKey: (String) -> Unit,
    ): Boolean {
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isDown = keyEvent.type == KeyEventType.KeyDown
        val isUp = keyEvent.type == KeyEventType.KeyUp

        hardwareColorKey(keyCode)?.let { colorKey ->
            if (isDown) cancelPendingBack() else if (isUp) dispatchColorKey(colorKey, onColorKey)
            return true
        }
        if (isColorSelectorVisible) {
            if (keyCode !in selectorKeys) return false
            if (!isDown) return true
            cancelPendingBack()
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> selectedColorKey = DataBroadcastingColorKey.Blue
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> selectedColorKey = DataBroadcastingColorKey.Red
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> selectedColorKey = DataBroadcastingColorKey.Green
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> selectedColorKey = DataBroadcastingColorKey.Yellow
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> dispatchColorKey(selectedColorKey, onColorKey)
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_ESCAPE -> closeColorSelector()
            }
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (isDown) {
                if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                    backKeyDownTimeMs = System.currentTimeMillis()
                    backKeyLongPressed = false
                } else if (!backKeyLongPressed && System.currentTimeMillis() - backKeyDownTimeMs > BACK_LONG_PRESS_MS) {
                    backKeyLongPressed = true
                    cancelPendingBack()
                    isColorSelectorVisible = true
                    selectedColorKey = DataBroadcastingColorKey.Blue
                }
                return true
            }
            if (isUp) {
                if (!backKeyLongPressed && System.currentTimeMillis() - backKeyDownTimeMs < BACK_LONG_PRESS_MS) {
                    onBackTap(scope, onBack, onBlank)
                }
                backKeyDownTimeMs = 0L
                backKeyLongPressed = false
                return true
            }
            return true
        }
        directionKey(keyCode)?.let { direction ->
            if (isDown) {
                cancelPendingBack()
                onRemoteKey(direction)
            }
            return true
        }
        remoteKey(keyCode)?.let { remoteKey ->
            if (isDown) cancelPendingBack() else if (isUp) onRemoteKey(remoteKey)
            return true
        }
        return false
    }

    private fun onBackTap(scope: CoroutineScope, onBack: () -> Unit, onBlank: () -> Unit) {
        if (pendingBackJob != null) {
            cancelPendingBack()
            onBlank()
            return
        }
        pendingBackJob = scope.launch {
            delay(BACK_DOUBLE_TAP_MS)
            onBack()
            pendingBackJob = null
        }
    }

    private fun cancelPendingBack() {
        pendingBackJob?.cancel()
        pendingBackJob = null
    }

    private companion object {
        const val BACK_DOUBLE_TAP_MS = 350L
        const val BACK_LONG_PRESS_MS = 500L
        val selectorKeys = setOf(
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_BACK,
            android.view.KeyEvent.KEYCODE_ESCAPE,
        )

        fun hardwareColorKey(keyCode: Int): DataBroadcastingColorKey? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_PROG_BLUE -> DataBroadcastingColorKey.Blue
            android.view.KeyEvent.KEYCODE_PROG_RED -> DataBroadcastingColorKey.Red
            android.view.KeyEvent.KEYCODE_PROG_GREEN -> DataBroadcastingColorKey.Green
            android.view.KeyEvent.KEYCODE_PROG_YELLOW -> DataBroadcastingColorKey.Yellow
            else -> null
        }

        fun directionKey(keyCode: Int): String? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> "up"
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            else -> null
        }

        fun remoteKey(keyCode: Int): String? = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> "enter"
            android.view.KeyEvent.KEYCODE_0,
            android.view.KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            android.view.KeyEvent.KEYCODE_1,
            android.view.KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            android.view.KeyEvent.KEYCODE_2,
            android.view.KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            android.view.KeyEvent.KEYCODE_3,
            android.view.KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            android.view.KeyEvent.KEYCODE_4,
            android.view.KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            android.view.KeyEvent.KEYCODE_5,
            android.view.KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            android.view.KeyEvent.KEYCODE_6,
            android.view.KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            android.view.KeyEvent.KEYCODE_7,
            android.view.KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            android.view.KeyEvent.KEYCODE_8,
            android.view.KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            android.view.KeyEvent.KEYCODE_9,
            android.view.KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            else -> null
        }
    }
}

@Composable
fun rememberDataBroadcastingInputState(identity: Any?): DataBroadcastingInputState {
    val state = remember(identity) { DataBroadcastingInputState() }
    DisposableEffect(state) {
        onDispose(state::reset)
    }
    return state
}
