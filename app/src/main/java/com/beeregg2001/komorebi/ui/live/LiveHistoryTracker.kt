package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.LiveHistoryPlaybackProgress

/**
 * Maps the first observed live media position to wall time minus the current buffer delay.
 * Thereafter media time, rather than device time, owns the clock so paused or stalled players
 * never advance the checkpoint.
 */
internal class LiveHistoryTracker {
    private var channelId: String? = null
    private var anchorMediaPositionMs: Long? = null
    private var anchorPlaybackTimeSeconds: Double? = null
    private var latestMediaPositionMs: Long? = null
    private var hasAdvanced = false

    fun hasObservation(): Boolean = channelId != null

    fun observe(
        channelId: String,
        mediaPositionMs: Long,
        bufferedPositionMs: Long,
        nowEpochMs: Long,
    ): Boolean {
        val previousPosition = latestMediaPositionMs
        if (this.channelId != channelId || anchorMediaPositionMs == null ||
            previousPosition != null && mediaPositionMs < previousPosition
        ) {
            this.channelId = channelId
            anchorMediaPositionMs = mediaPositionMs
            anchorPlaybackTimeSeconds = nowEpochMs / 1000.0 -
                (bufferedPositionMs - mediaPositionMs).coerceAtLeast(0L) / 1000.0
        }
        latestMediaPositionMs = mediaPositionMs
        // An anchor is not yet proof of playback. Callers checkpoint only once Media3's
        // observed media timeline moves after it.
        val advanced = previousPosition != null && mediaPositionMs != previousPosition
        if (advanced) hasAdvanced = true
        return advanced
    }

    fun latest(): LiveHistoryPlaybackProgress? {
        if (!hasAdvanced) return null
        val id = channelId ?: return null
        val anchorPosition = anchorMediaPositionMs ?: return null
        val anchorTime = anchorPlaybackTimeSeconds ?: return null
        val latestPosition = latestMediaPositionMs ?: return null
        return LiveHistoryPlaybackProgress(id, anchorTime + (latestPosition - anchorPosition) / 1000.0)
    }

    fun clear(): LiveHistoryPlaybackProgress? = latest().also {
        channelId = null
        anchorMediaPositionMs = null
        anchorPlaybackTimeSeconds = null
        latestMediaPositionMs = null
        hasAdvanced = false
    }
}
