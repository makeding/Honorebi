package com.beeregg2001.komorebi.ui.video.player.policy

/**
 * Resolves the timeline shown for a completed recording.
 *
 * MMT/TLV playback uses the active Raw extractor or server HLS timeline.
 * KonomiTV's EIT-derived recorded duration is not a valid fallback for it.
 */
fun resolveCompletedRecordingTimelineDurationMs(
    usesNativeMmtDuration: Boolean,
    konomiReportedDurationMs: Long,
    nativePlayerDurationMs: Long,
    playbackPositionMs: Long,
    bufferedPositionMs: Long,
): Long = maxOf(
    if (usesNativeMmtDuration) 0L else konomiReportedDurationMs,
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
 * MMT/TLV recordings have a short bootstrap period before Raw or copy HLS is
 * selected. During that period, neither KonomiTV metadata nor a temporary
 * player duration is authoritative.
 */
fun resolveCompletedRecordingAutomationDurationMs(
    requiresRawMmtsPlayback: Boolean,
    isRawMmtsPlayback: Boolean,
    isMmtCopyHlsPlayback: Boolean = false,
    konomiReportedDurationMs: Long,
    nativePlayerDurationMs: Long,
): Long {
    if (requiresRawMmtsPlayback) {
        return if ((isRawMmtsPlayback || isMmtCopyHlsPlayback) && nativePlayerDurationMs > 0L) {
            nativePlayerDurationMs
        } else {
            0L
        }
    }

    return maxOf(konomiReportedDurationMs, nativePlayerDurationMs).coerceAtLeast(0L)
}
