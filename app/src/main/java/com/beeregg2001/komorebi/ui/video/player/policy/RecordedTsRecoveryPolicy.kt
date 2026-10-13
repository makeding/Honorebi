package com.beeregg2001.komorebi.ui.video.player.policy

/** Bounded recovery for a completed recording, independent of Media3 and UI clocks. */
internal class RecordedTsRecoveryPolicy {
    var seekSerial = 0
        private set
    var pendingError = false
    var failureReason = "録画データの読み取り・デコードが進みません"
    var exhausted = false
        private set
    private var attempts = 0
    private var lastTargetMs = -1L
    private var observedPositionMs = -1L
    private var observedBufferMs = -1L
    private var observedBytes = 0L
    private var stalledSinceMs = -1L
    private var advancingSinceMs = -1L

    fun cancelPending() {
        seekSerial++
        pendingError = false
        clearObservation()
    }

    fun userSeek() {
        cancelPending()
        attempts = 0
        lastTargetMs = -1L
        pendingError = false
        exhausted = false
        clearObservation()
    }

    fun clearObservation() {
        observedPositionMs = -1L
        observedBufferMs = -1L
        stalledSinceMs = -1L
        advancingSinceMs = -1L
    }

    fun observe(nowMs: Long, positionMs: Long, bufferedMs: Long, bytesRead: Long,
                buffering: Boolean, loading: Boolean, playing: Boolean): Boolean {
        if (exhausted) return false
        if (playing && positionMs > observedPositionMs + 100L) {
            if (advancingSinceMs < 0L) advancingSinceMs = nowMs
            if (nowMs - advancingSinceMs >= 15_000L) {
                attempts = 0
                lastTargetMs = -1L
            }
        } else {
            advancingSinceMs = -1L
        }
        if (!buffering || positionMs > observedPositionMs + 100L ||
            bufferedMs > observedBufferMs + 100L || stalledSinceMs < 0L) {
            stalledSinceMs = if (buffering) nowMs else -1L
            observedBytes = bytesRead
        }
        observedPositionMs = positionMs
        observedBufferMs = bufferedMs
        return buffering && stalledSinceMs >= 0L && nowMs - stalledSinceMs >= 12_000L &&
            (bytesRead - observedBytes >= 1_048_576L ||
                !loading)
    }

    fun nextTarget(positionMs: Long, durationMs: Long): Long? {
        val steps = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L)
        if (attempts >= steps.size) {
            exhausted = true
            return null
        }
        val target = maxOf(positionMs, lastTargetMs) + steps[attempts++]
        if (durationMs > 0L && target >= durationMs - 1_000L) {
            exhausted = true
            return null
        }
        lastTargetMs = target
        clearObservation()
        return target
    }
}
