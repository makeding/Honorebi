package com.beeregg2001.komorebi.ui.player

/** Playback time advances even when there are no comments (and hence no frame callbacks). */
internal class DanmakuPlaybackClock(private val nowNs: () -> Long = System::nanoTime) {
    private var playedNs = 0L
    private var startedNs: Long? = null

    @Synchronized fun resume() {
        if (startedNs == null) startedNs = nowNs()
    }

    @Synchronized fun pause() {
        startedNs?.let { playedNs += nowNs() - it }
        startedNs = null
    }

    @Synchronized fun positionMs(frameNs: Long = nowNs()): Long =
        (playedNs + (startedNs?.let { (frameNs - it).coerceAtLeast(0L) } ?: 0L)) / 1_000_000L
}
