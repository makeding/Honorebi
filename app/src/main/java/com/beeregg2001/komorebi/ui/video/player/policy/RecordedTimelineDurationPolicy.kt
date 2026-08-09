package com.beeregg2001.komorebi.ui.video.player.policy

/**
 * Resolves the timeline shown for a completed recording.
 *
 * Raw MMTS duration is owned by libaribtlv's native duration probe. KonomiTV's
 * EIT-derived recorded duration is not a valid fallback for that container.
 */
fun resolveCompletedRecordingTimelineDurationMs(
    isRawMmtsPlayback: Boolean,
    konomiReportedDurationMs: Long,
    nativePlayerDurationMs: Long,
    playbackPositionMs: Long,
    bufferedPositionMs: Long,
): Long = maxOf(
    if (isRawMmtsPlayback) 0L else konomiReportedDurationMs,
    nativePlayerDurationMs,
    playbackPositionMs,
    bufferedPositionMs,
).coerceAtLeast(0L)
