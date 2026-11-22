package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedProgram

/**
 * Single source of truth for deciding whether a recorded program has meaningful
 * in-progress playback (used by both deep-link resume and Android TV Watch Next).
 */
object WatchProgressPolicy {
    const val MIN_PROGRESS_SECONDS: Double = 5.0
    const val COMPLETION_RATIO: Double = 0.95

    fun isInProgress(positionSeconds: Double, durationSeconds: Double?): Boolean {
        if (!positionSeconds.isFinite() || positionSeconds <= MIN_PROGRESS_SECONDS) return false
        if (durationSeconds == null || !durationSeconds.isFinite() || durationSeconds <= 0.0) return true
        return positionSeconds < durationSeconds * COMPLETION_RATIO
    }

    fun resumePositionSeconds(positionSeconds: Double?, durationSeconds: Double?): Double {
        if (positionSeconds == null) return 0.0
        return if (isInProgress(positionSeconds, durationSeconds)) positionSeconds else 0.0
    }

    fun effectiveDurationSeconds(program: RecordedProgram): Double? =
        program.recordedVideo.duration.takeIf { it.isFinite() && it > 0.0 }
            ?: program.duration.takeIf { it.isFinite() && it > 0.0 }
}
