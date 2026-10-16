package com.beeregg2001.komorebi.ui.screensaver

import android.view.KeyEvent

/** A dialog owns focus, so explicitly route transport keys to the system MediaSession. */
internal fun handleScreensaverKey(
    event: KeyEvent,
    onDismiss: () -> Unit,
    dispatchMediaKey: (KeyEvent) -> Unit,
): Boolean {
    when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_RECORD -> dispatchMediaKey(event)
        else -> if (event.action == KeyEvent.ACTION_DOWN) onDismiss()
    }
    // The dismissal key must not also activate the paused player's controls.
    return true
}
