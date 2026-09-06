package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent

/** One gesture owner for hardware media keys; eventTime/downTime use Android's monotonic clock. */
internal class RecordedMediaKeys {
    enum class Action { PLAY, PAUSE, TOGGLE, BACK, FORWARD, PREVIOUS, NEXT }

    private var activeKey: Int? = null
    private var downTime = 0L
    private var lastActionTime = 0L

    fun reset() {
        activeKey = null
    }

    fun handle(event: KeyEvent, hasChapters: Boolean, dispatch: (Action) -> Unit): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Action.PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Action.TOGGLE
            KeyEvent.KEYCODE_MEDIA_REWIND -> Action.BACK
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Action.FORWARD
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> Action.PREVIOUS
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> Action.NEXT
            else -> { reset(); return false }
        }
        if (event.isCanceled) { reset(); return true }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                activeKey = event.keyCode
                downTime = event.downTime
                lastActionTime = event.eventTime
                if (action !in listOf(Action.PLAY, Action.PAUSE, Action.TOGGLE)) dispatch(action)
            } else if (activeKey == event.keyCode && downTime == event.downTime &&
                event.eventTime - lastActionTime >= REPEAT_INTERVAL_MS &&
                action in listOf(Action.BACK, Action.FORWARD)
            ) {
                lastActionTime = event.eventTime
                dispatch(if (hasChapters) {
                    if (action == Action.BACK) Action.PREVIOUS else Action.NEXT
                } else action)
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (activeKey == event.keyCode && downTime == event.downTime) {
                if (action in listOf(Action.PLAY, Action.PAUSE, Action.TOGGLE)) dispatch(action)
                reset()
            }
        }
        return true
    }

    private companion object {
        const val REPEAT_INTERVAL_MS = 400L
    }
}
