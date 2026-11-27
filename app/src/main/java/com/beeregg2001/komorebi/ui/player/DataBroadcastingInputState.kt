package com.beeregg2001.komorebi.ui.player

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.beeregg2001.komorebi.data.DataBroadcastingVirtualRemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class DataBroadcastingColorKey { Blue, Red, Green, Yellow }

enum class DataBroadcastingVirtualKey(
    val label: String,
    val wireKey: String?,
) {
    Blue("青", "blue"),
    Red("赤", "red"),
    Green("緑", "green"),
    Yellow("黄", "yellow"),
    Data("d", "data"),
    Back("←", "back"),
    Digit0("0", "0"),
    Digit1("1", "1"),
    Digit2("2", "2"),
    Digit3("3", "3"),
    Digit4("4", "4"),
    Digit5("5", "5"),
    Digit6("6", "6"),
    Digit7("7", "7"),
    Digit8("8", "8"),
    Digit9("9", "9"),
    Close("閉じる", null);

    companion object {
        val ALL: List<DataBroadcastingVirtualKey> = entries
    }
}

private val REQUIRED_DATA_BROADCASTING_KEYS = intArrayOf(
    NativeKeyEvent.KEYCODE_PROG_BLUE,
    NativeKeyEvent.KEYCODE_PROG_RED,
    NativeKeyEvent.KEYCODE_PROG_GREEN,
    NativeKeyEvent.KEYCODE_PROG_YELLOW,
    NativeKeyEvent.KEYCODE_0,
)

fun detectMissingDataBroadcastingHardwareKeys(activeDevice: InputDevice? = null): Boolean {
    if (activeDevice != null && !activeDevice.isVirtual) {
        val available = runCatching {
            activeDevice.hasKeys(*REQUIRED_DATA_BROADCASTING_KEYS)
        }.getOrNull()
        if (available != null) {
            return available.any { !it }
        }
    }
    // Check non-virtual physical remotes only; built-in Android TV "virtual-remote"
    // devices advertise full keymaps even when the physical remote has no color/digit keys.
    val physicalRemotes = runCatching {
        InputDevice.getDeviceIds()
            .toList()
            .mapNotNull(InputDevice::getDevice)
            .filter { device ->
                !device.isVirtual &&
                    !device.name.contains("virtual", ignoreCase = true) &&
                    !device.name.contains("ir receiver", ignoreCase = true) &&
                    (device.sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD ||
                        device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD)
            }
    }.getOrNull().orEmpty()
    if (physicalRemotes.isNotEmpty()) {
        return physicalRemotes.none { device ->
            runCatching { device.hasKeys(*REQUIRED_DATA_BROADCASTING_KEYS).all { it } }.getOrDefault(false)
        }
    }
    val available = runCatching {
        KeyCharacterMap.deviceHasKeys(REQUIRED_DATA_BROADCASTING_KEYS)
    }.getOrNull() ?: return true
    return available.any { !it }
}

/**
 * Mode-independent remote-input state for data broadcasting (B60 / future B24).
 * Manages passthrough D-pad navigation, Back double-tap / long-press, and the bottom
 * virtual remote bar when hardware color/digit keys are unavailable or explicitly opened.
 */
@Stable
class DataBroadcastingInputState(
    private val hasMissingHardwareKeys: () -> Boolean = DEFAULT_DETECTOR,
) {
    private var lastInputDevice: InputDevice? = null
    private val usesDefaultDetector: Boolean = hasMissingHardwareKeys === DEFAULT_DETECTOR
    var isVirtualRemoteVisible by mutableStateOf(false)
        private set
    var selectedVirtualKey by mutableStateOf(DataBroadcastingVirtualKey.Blue)
        private set
    var virtualRemoteInteractionSequence by mutableIntStateOf(0)
        private set

    private var hardwareDedicatedKeyObserved = false
    private var backKeyDownTimeMs = 0L
    private var backKeyLongPressed = false
    private var pendingBackJob: Job? = null

    fun recordInputDevice(device: InputDevice?) {
        if (device != null && !device.isVirtual) {
            lastInputDevice = device
        }
    }

    fun reset() {
        closeVirtualRemote()
        cancelPendingBack()
        backKeyDownTimeMs = 0L
        backKeyLongPressed = false
    }

    fun onDataBroadcastingActivated(mode: DataBroadcastingVirtualRemoteMode) {
        val missingKeys = if (lastInputDevice != null && usesDefaultDetector) {
            detectMissingDataBroadcastingHardwareKeys(lastInputDevice)
        } else {
            hasMissingHardwareKeys()
        }
        val shouldAutoShow = when (mode) {
            DataBroadcastingVirtualRemoteMode.ALWAYS -> true
            DataBroadcastingVirtualRemoteMode.MANUAL -> false
            DataBroadcastingVirtualRemoteMode.AUTO ->
                !hardwareDedicatedKeyObserved && missingKeys
        }
        if (shouldAutoShow) {
            openVirtualRemote()
        }
    }

    fun openVirtualRemote(initialKey: DataBroadcastingVirtualKey = selectedVirtualKey) {
        selectedVirtualKey = if (initialKey == DataBroadcastingVirtualKey.Close) {
            DataBroadcastingVirtualKey.Blue
        } else {
            initialKey
        }
        isVirtualRemoteVisible = true
        virtualRemoteInteractionSequence += 1
    }

    fun closeVirtualRemote() {
        isVirtualRemoteVisible = false
    }

    fun dispatchVirtualKey(
        key: DataBroadcastingVirtualKey,
        onBack: () -> Unit,
        onBlank: () -> Unit,
        onColorKey: (DataBroadcastingColorKey) -> Unit,
        onRemoteKey: (String) -> Unit,
    ) {
        selectedVirtualKey = key
        virtualRemoteInteractionSequence += 1
        when (key) {
            DataBroadcastingVirtualKey.Blue -> onColorKey(DataBroadcastingColorKey.Blue)
            DataBroadcastingVirtualKey.Red -> onColorKey(DataBroadcastingColorKey.Red)
            DataBroadcastingVirtualKey.Green -> onColorKey(DataBroadcastingColorKey.Green)
            DataBroadcastingVirtualKey.Yellow -> onColorKey(DataBroadcastingColorKey.Yellow)
            DataBroadcastingVirtualKey.Data -> {
                closeVirtualRemote()
                onBlank()
            }
            DataBroadcastingVirtualKey.Back -> onBack()
            DataBroadcastingVirtualKey.Close -> closeVirtualRemote()
            else -> key.wireKey?.let(onRemoteKey)
        }
    }

    fun handleKeyEvent(
        keyEvent: KeyEvent,
        scope: CoroutineScope,
        onBack: () -> Unit,
        onBlank: () -> Unit,
        onColorKey: (DataBroadcastingColorKey) -> Unit,
        onRemoteKey: (String) -> Unit,
    ): Boolean {
        recordInputDevice(keyEvent.nativeKeyEvent.device)
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isDown = keyEvent.type == KeyEventType.KeyDown
        val isUp = keyEvent.type == KeyEventType.KeyUp

        hardwareColorKey(keyCode)?.let { colorKey ->
            hardwareDedicatedKeyObserved = true
            if (isDown) {
                cancelPendingBack()
            } else if (isUp) {
                closeVirtualRemote()
                onColorKey(colorKey)
            }
            return true
        }

        if (isVirtualRemoteVisible) {
            if (keyCode !in virtualBarKeys) return false
            if (!isDown) return true
            cancelPendingBack()
            when (keyCode) {
                NativeKeyEvent.KEYCODE_DPAD_UP -> onRemoteKey("up")
                NativeKeyEvent.KEYCODE_DPAD_DOWN -> onRemoteKey("down")
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> moveSelection(-1)
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> moveSelection(+1)
                NativeKeyEvent.KEYCODE_DPAD_CENTER,
                NativeKeyEvent.KEYCODE_ENTER,
                NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> dispatchVirtualKey(
                    key = selectedVirtualKey,
                    onBack = onBack,
                    onBlank = onBlank,
                    onColorKey = onColorKey,
                    onRemoteKey = onRemoteKey,
                )
                NativeKeyEvent.KEYCODE_BACK,
                NativeKeyEvent.KEYCODE_ESCAPE -> closeVirtualRemote()
            }
            return true
        }

        if (keyCode == NativeKeyEvent.KEYCODE_BACK || keyCode == NativeKeyEvent.KEYCODE_ESCAPE) {
            if (isDown) {
                if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                    backKeyDownTimeMs = System.currentTimeMillis()
                    backKeyLongPressed = false
                } else if (!backKeyLongPressed && System.currentTimeMillis() - backKeyDownTimeMs > BACK_LONG_PRESS_MS) {
                    backKeyLongPressed = true
                    cancelPendingBack()
                    openVirtualRemote(DataBroadcastingVirtualKey.Blue)
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
            if (remoteKey.length == 1 && remoteKey[0] in '0'..'9') {
                hardwareDedicatedKeyObserved = true
            }
            if (isDown) cancelPendingBack() else if (isUp) onRemoteKey(remoteKey)
            return true
        }
        return false
    }

    private fun moveSelection(delta: Int) {
        val items = DataBroadcastingVirtualKey.ALL
        val currentIndex = items.indexOf(selectedVirtualKey).coerceAtLeast(0)
        val nextIndex = (currentIndex + delta + items.size) % items.size
        selectedVirtualKey = items[nextIndex]
        virtualRemoteInteractionSequence += 1
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
        val DEFAULT_DETECTOR: () -> Boolean = { detectMissingDataBroadcastingHardwareKeys(null) }
        const val BACK_DOUBLE_TAP_MS = 350L
        const val BACK_LONG_PRESS_MS = 500L

        val virtualBarKeys = setOf(
            NativeKeyEvent.KEYCODE_DPAD_UP,
            NativeKeyEvent.KEYCODE_DPAD_DOWN,
            NativeKeyEvent.KEYCODE_DPAD_LEFT,
            NativeKeyEvent.KEYCODE_DPAD_RIGHT,
            NativeKeyEvent.KEYCODE_DPAD_CENTER,
            NativeKeyEvent.KEYCODE_ENTER,
            NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
            NativeKeyEvent.KEYCODE_BACK,
            NativeKeyEvent.KEYCODE_ESCAPE,
        )

        fun hardwareColorKey(keyCode: Int): DataBroadcastingColorKey? = when (keyCode) {
            NativeKeyEvent.KEYCODE_PROG_BLUE -> DataBroadcastingColorKey.Blue
            NativeKeyEvent.KEYCODE_PROG_RED -> DataBroadcastingColorKey.Red
            NativeKeyEvent.KEYCODE_PROG_GREEN -> DataBroadcastingColorKey.Green
            NativeKeyEvent.KEYCODE_PROG_YELLOW -> DataBroadcastingColorKey.Yellow
            else -> null
        }

        fun directionKey(keyCode: Int): String? = when (keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_UP -> "up"
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> "down"
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> "left"
            else -> null
        }

        fun remoteKey(keyCode: Int): String? = when (keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_CENTER,
            NativeKeyEvent.KEYCODE_ENTER,
            NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            NativeKeyEvent.KEYCODE_0,
            NativeKeyEvent.KEYCODE_NUMPAD_0 -> "0"
            NativeKeyEvent.KEYCODE_1,
            NativeKeyEvent.KEYCODE_NUMPAD_1 -> "1"
            NativeKeyEvent.KEYCODE_2,
            NativeKeyEvent.KEYCODE_NUMPAD_2 -> "2"
            NativeKeyEvent.KEYCODE_3,
            NativeKeyEvent.KEYCODE_NUMPAD_3 -> "3"
            NativeKeyEvent.KEYCODE_4,
            NativeKeyEvent.KEYCODE_NUMPAD_4 -> "4"
            NativeKeyEvent.KEYCODE_5,
            NativeKeyEvent.KEYCODE_NUMPAD_5 -> "5"
            NativeKeyEvent.KEYCODE_6,
            NativeKeyEvent.KEYCODE_NUMPAD_6 -> "6"
            NativeKeyEvent.KEYCODE_7,
            NativeKeyEvent.KEYCODE_NUMPAD_7 -> "7"
            NativeKeyEvent.KEYCODE_8,
            NativeKeyEvent.KEYCODE_NUMPAD_8 -> "8"
            NativeKeyEvent.KEYCODE_9,
            NativeKeyEvent.KEYCODE_NUMPAD_9 -> "9"
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
