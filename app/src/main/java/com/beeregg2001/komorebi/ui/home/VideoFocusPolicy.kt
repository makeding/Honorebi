package com.beeregg2001.komorebi.ui.home

/**
 * Keeps the Video tab's restore decision independent from Compose attachment.
 * The caller scrolls the owning row before requesting the keyed card.
 */
internal enum class VideoFocusRow { RECENT, HISTORY }

internal data class VideoCardFocus(
    val row: VideoFocusRow,
    val programId: String,
)

internal object VideoFocusPolicy {
    /**
     * Playback returns follow the program that was actually playing when Back
     * was pressed.  A card's old row is intentionally ignored here: a program
     * which appears in both rows returns to Recent first, then Continue
     * Watching.  That makes A -> B playback restore B, rather than A's origin.
     */
    fun playerReturnTarget(
        playedProgramId: String?,
        recentProgramIds: List<String>,
        historyProgramIds: List<String>,
    ): VideoCardFocus? {
        if (playedProgramId != null && playedProgramId in recentProgramIds) {
            return VideoCardFocus(VideoFocusRow.RECENT, playedProgramId)
        }
        if (playedProgramId != null && playedProgramId in historyProgramIds) {
            return VideoCardFocus(VideoFocusRow.HISTORY, playedProgramId)
        }
        return historyProgramIds.firstOrNull()?.let { VideoCardFocus(VideoFocusRow.HISTORY, it) }
            ?: recentProgramIds.firstOrNull()?.let { VideoCardFocus(VideoFocusRow.RECENT, it) }
    }

    fun shouldWaitForPlayerReturn(
        playedProgramId: String?,
        recentProgramIds: List<String>,
        historyProgramIds: List<String>,
        recentLoading: Boolean,
        historyReady: Boolean,
    ): Boolean {
        if (playedProgramId != null && playedProgramId in recentProgramIds) return false
        // Recent has priority even when History already contains the program.
        return recentLoading || !historyReady
    }

    fun restoreTarget(
        requestedProgramId: String?,
        priorTarget: VideoCardFocus?,
        recentProgramIds: List<String>,
        historyProgramIds: List<String>,
    ): VideoCardFocus? {
        if (requestedProgramId == null) return priorTarget?.takeIf {
            contains(it, recentProgramIds, historyProgramIds)
        }

        priorTarget?.takeIf {
            it.programId == requestedProgramId && contains(it, recentProgramIds, historyProgramIds)
        }?.let { return it }

        if (requestedProgramId in recentProgramIds) {
            return VideoCardFocus(VideoFocusRow.RECENT, requestedProgramId)
        }
        if (requestedProgramId in historyProgramIds) {
            return VideoCardFocus(VideoFocusRow.HISTORY, requestedProgramId)
        }
        return null
    }

    fun rowItemIndex(target: VideoCardFocus, recentProgramIds: List<String>, historyProgramIds: List<String>): Int =
        when (target.row) {
            VideoFocusRow.RECENT -> recentProgramIds.indexOf(target.programId)
            VideoFocusRow.HISTORY -> historyProgramIds.indexOf(target.programId)
        }

    fun columnItemIndex(target: VideoCardFocus, hasRecent: Boolean): Int = when (target.row) {
        VideoFocusRow.RECENT -> 1
        VideoFocusRow.HISTORY -> if (hasRecent) 2 else 1
    }

    private fun contains(target: VideoCardFocus, recentProgramIds: List<String>, historyProgramIds: List<String>) =
        when (target.row) {
            VideoFocusRow.RECENT -> target.programId in recentProgramIds
            VideoFocusRow.HISTORY -> target.programId in historyProgramIds
        }
}
