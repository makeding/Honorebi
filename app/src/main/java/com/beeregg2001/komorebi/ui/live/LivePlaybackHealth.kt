package com.beeregg2001.komorebi.ui.live

/** READY alone is not recovery: require continuous playback with advancing position. */
internal class LivePlaybackHealth(private val stableMs: Long = 10_000L) {
    private var startedAt: Long? = null
    private var lastAt: Long? = null
    private var lastPosition: Long? = null

    fun reset() { startedAt = null; lastAt = null; lastPosition = null }

    fun observe(now: Long, playing: Boolean, position: Long): Boolean {
        if (!playing) { reset(); return false }
        val previousAt = lastAt
        val previousPosition = lastPosition
        if (previousAt == null || previousPosition == null || position <= previousPosition ||
            now < previousAt || now - previousAt > 2_500L) startedAt = now
        lastAt = now
        lastPosition = position
        return now - (startedAt ?: now) >= stableMs
    }
}
