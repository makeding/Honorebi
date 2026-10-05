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
