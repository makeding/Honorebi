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

/**
 * Resolves the duration that may drive automatic end-of-playback behavior.
 *
 * Unlike the controls timeline, this value must never fall back to the observed
 * playback position: doing so makes a restored position look like the end of
 * the recording while the extractor is still discovering its duration.
 *
 * Recordings that require Raw MMTS also have a short bootstrap period before
 * the raw quality is selected. During that period, neither KonomiTV metadata
 * nor a temporary non-raw player duration is authoritative.
 */
fun resolveCompletedRecordingAutomationDurationMs(
    requiresRawMmtsPlayback: Boolean,
    isRawMmtsPlayback: Boolean,
    konomiReportedDurationMs: Long,
    nativePlayerDurationMs: Long,
): Long {
    if (requiresRawMmtsPlayback) {
        return if (isRawMmtsPlayback && nativePlayerDurationMs > 0L) {
            nativePlayerDurationMs
        } else {
            0L
        }
    }

    return maxOf(konomiReportedDurationMs, nativePlayerDurationMs).coerceAtLeast(0L)
}
