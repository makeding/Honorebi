package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.CmSkipMode

private const val AUTO_CM_SKIP_END_GUARD_MS = 1_500L

internal fun shouldRunAutoCmSkipPolling(
    mode: CmSkipMode,
    chapters: List<ChapterInfo>,
    isPlaying: Boolean,
): Boolean = mode == CmSkipMode.AUTO && isPlaying && chapters.any { chapter ->
    chapter.isCm && chapter.startTimeMs < chapter.endTimeMs - AUTO_CM_SKIP_END_GUARD_MS
}

internal fun autoCmSkipTargetMs(
    currentPositionMs: Long,
    chapters: List<ChapterInfo>,
): Long? = chapters.firstOrNull { chapter ->
    chapter.isCm &&
        currentPositionMs >= chapter.startTimeMs &&
        currentPositionMs < chapter.endTimeMs - AUTO_CM_SKIP_END_GUARD_MS
}?.endTimeMs
