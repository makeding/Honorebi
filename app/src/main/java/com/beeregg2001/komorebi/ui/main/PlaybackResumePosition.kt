package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram

private const val MINIMUM_RESUME_POSITION_SECONDS = 5.0
private const val END_OF_PROGRAM_GUARD_SECONDS = 10.0

/**
 * Returns the initial playback position for a recording.
 *
 * History is preferred over the locally held [RecordedProgram.playbackPosition]. A history
 * item may be associated by either the Konomi program id or the recorded video id.
 */
fun playbackResumePositionMs(
    program: RecordedProgram,
    watchHistory: List<KonomiHistoryProgram>,
    forcedPositionSeconds: Double? = null,
): Long {
    if (forcedPositionSeconds != null) {
        return forcedPositionSeconds.toPlaybackMilliseconds()
    }

    val durationSeconds = program.recordedVideo.duration
    val historyPosition = watchHistory
        .firstOrNull { history ->
            history.program.id.toIntOrNull() == program.id ||
                history.videoId == program.recordedVideo.id
        }
        ?.playback_position
        ?.takeIf { it.isValidResumePosition(durationSeconds) }
    val programPosition = program.playbackPosition
        .takeIf { it.isValidResumePosition(durationSeconds) }

    return (historyPosition ?: programPosition ?: 0.0).toPlaybackMilliseconds()
}

private fun Double.isValidResumePosition(durationSeconds: Double): Boolean =
    this > MINIMUM_RESUME_POSITION_SECONDS &&
        (durationSeconds <= 0.0 || this < durationSeconds - END_OF_PROGRAM_GUARD_SECONDS)

private fun Double.toPlaybackMilliseconds(): Long =
    (coerceAtLeast(0.0) * 1000.0).toLong()
