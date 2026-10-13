package com.beeregg2001.komorebi.ui.live

import android.os.SystemClock
import android.util.Log

/** Measures one opening without publishing timing updates into the player composition. */
internal class ChannelBrowserOpenTrace {
    private var eventTimeMillis = 0L
    private var requestedAtMillis = 0L
    private var drew = false
    private var focused = false

    fun begin(eventTime: Long) {
        eventTimeMillis = eventTime
        requestedAtMillis = SystemClock.uptimeMillis()
        drew = false
        focused = false
        Log.d(TAG, "open requested: event_uptime_ms=$eventTime, " +
            "handled_uptime_ms=$requestedAtMillis, input_delay_ms=${requestedAtMillis - eventTime}")
    }

    fun firstDraw() {
        if (requestedAtMillis == 0L || drew) return
        drew = true
        mark("first_draw")
    }

    fun initialFocus() {
        if (requestedAtMillis == 0L || focused) return
        focused = true
        mark("initial_focus")
    }

    private fun mark(stage: String) {
        val now = SystemClock.uptimeMillis()
        Log.d(TAG, "$stage: uptime_ms=$now, since_input_ms=${now - eventTimeMillis}, " +
            "since_request_ms=${now - requestedAtMillis}")
    }

    private companion object { const val TAG = "ChannelBrowserOpen" }
}
