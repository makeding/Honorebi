package com.beeregg2001.komorebi.ui.main

import android.view.KeyEvent

enum class RemoteShortcut {
    GUIDE,
    DVR,
    TV,
}

data class RemoteKeyContext(
    val isInOsPip: Boolean,
    val isSettingsOpen: Boolean,
    val isAiOverlayOpen: Boolean,
    val hasRootOverlay: Boolean,
    val isLivePlaybackFullscreen: Boolean,
    val isTifPassthrough: Boolean,
    val isRecordedPlaybackFullscreen: Boolean,
)

/**
 * Pure key-mapping, scope-gating, and numeric buffer rules for global TV remote shortcuts.
 */
object RemoteKeyShortcuts {
    const val COMMIT_DELAY_MS = 1_500L
    const val COMMIT_DELAY_FULL_MS = 350L
    const val NOT_FOUND_DISPLAY_MS = 1_500L
    const val MAX_DIGITS = 3

    fun shortcutFor(keyCode: Int): RemoteShortcut? = when (keyCode) {
        KeyEvent.KEYCODE_GUIDE -> RemoteShortcut.GUIDE
        KeyEvent.KEYCODE_DVR -> RemoteShortcut.DVR
        KeyEvent.KEYCODE_TV -> RemoteShortcut.TV
        else -> null
    }

    fun digitFor(keyCode: Int): Char? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
        else -> null
    }

    fun shortcutsAllowed(ctx: RemoteKeyContext): Boolean =
        !ctx.isInOsPip && !ctx.isAiOverlayOpen

    fun numberTuningAllowed(ctx: RemoteKeyContext): Boolean =
        !ctx.isInOsPip &&
            !ctx.isSettingsOpen &&
            !ctx.isAiOverlayOpen &&
            ((ctx.isLivePlaybackFullscreen && !ctx.isTifPassthrough) ||
                (!ctx.isLivePlaybackFullscreen && !ctx.isRecordedPlaybackFullscreen && !ctx.hasRootOverlay))

    fun append(buffer: String, digit: Char): String {
        if (digit !in '0'..'9') return buffer
        return if (buffer.length >= MAX_DIGITS) {
            digit.toString()
        } else {
            buffer + digit
        }
    }

    fun commitDelayMs(buffer: String): Long =
        if (buffer.length >= MAX_DIGITS) COMMIT_DELAY_FULL_MS else COMMIT_DELAY_MS
}
