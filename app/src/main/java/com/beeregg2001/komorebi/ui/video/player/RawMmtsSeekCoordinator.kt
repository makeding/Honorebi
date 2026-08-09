package com.beeregg2001.komorebi.ui.video.player

/**
 * Keeps Raw MMTS seeks conflated and serialized.
 *
 * A new request replaces the queued target, but it cannot start until the
 * currently active seek has settled. This prevents repeated TLV repositioning
 * from continuously cancelling the range load that is trying to reach a RAP.
 */
internal class RawMmtsSeekCoordinator {
    private var latestRequestedPositionMs: Long? = null
    private var activePositionMs: Long? = null

    fun request(positionMs: Long) {
        latestRequestedPositionMs = positionMs
    }

    fun beginNext(): Long? {
        if (activePositionMs != null) return null
        return latestRequestedPositionMs?.also { activePositionMs = it }
    }

    /** Returns true when a newer target is still waiting. */
    fun finish(positionMs: Long): Boolean {
        if (activePositionMs != positionMs) return latestRequestedPositionMs != null
        activePositionMs = null
        if (latestRequestedPositionMs == positionMs) {
            latestRequestedPositionMs = null
        }
        return latestRequestedPositionMs != null
    }

    fun pendingPositionMs(): Long? = latestRequestedPositionMs ?: activePositionMs

    fun reset() {
        latestRequestedPositionMs = null
        activePositionMs = null
    }
}

internal fun resolveRecreatedPlayerStartPositionMs(
    explicitResumePositionMs: Long?,
    isFirstLoad: Boolean,
    retainedPlaybackPositionMs: Long,
): Long? = explicitResumePositionMs
    ?: retainedPlaybackPositionMs.takeIf { !isFirstLoad && it > 0L }

internal fun resolvePersistablePlaybackPositionMs(
    pendingSeekPositionMs: Long?,
    rawPlayerPositionMs: Long?,
    fallbackPositionMs: Long,
    isPlayerReady: Boolean,
    isLiveStream: Boolean,
    isRecordingChasePlayback: Boolean,
    playbackOffsetMs: Long
): Long {
    pendingSeekPositionMs?.let { return it.coerceAtLeast(0L) }
    val rawPositionMs = rawPlayerPositionMs
    if (
        rawPositionMs == null ||
        rawPositionMs < 0L ||
        rawPositionMs == 0L && fallbackPositionMs > 0L && !isPlayerReady
    ) {
        return fallbackPositionMs.coerceAtLeast(0L)
    }
    return if (isLiveStream && !isRecordingChasePlayback) {
        playbackOffsetMs + rawPositionMs
    } else {
        rawPositionMs
    }.coerceAtLeast(0L)
}
