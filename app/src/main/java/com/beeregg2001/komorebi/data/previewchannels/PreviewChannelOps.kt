package com.beeregg2001.komorebi.data.previewchannels

import com.beeregg2001.komorebi.data.watchnext.WatchNextArtworkCache

data class ExistingPreviewProgram(
    val rowId: Long,
    val internalId: String,
    val browsable: Boolean,
)

sealed interface PreviewProgramOp {
    data class Insert(val candidate: PreviewProgramCandidate) : PreviewProgramOp
    data class Update(val rowId: Long, val candidate: PreviewProgramCandidate) : PreviewProgramOp
    data class Delete(val rowId: Long) : PreviewProgramOp
}

object PreviewChannelOps {

    fun planOps(
        existing: List<ExistingPreviewProgram>,
        desired: List<PreviewProgramCandidate>,
    ): List<PreviewProgramOp> {
        val ops = mutableListOf<PreviewProgramOp>()
        val canonicalById = LinkedHashMap<String, ExistingPreviewProgram>()

        val (blankRows, keyedRows) = existing.partition { it.internalId.isBlank() }
        for (row in blankRows) {
            ops += PreviewProgramOp.Delete(row.rowId)
        }

        for ((internalId, group) in keyedRows.groupBy { it.internalId }) {
            val sorted = group.sortedByDescending { it.rowId }
            canonicalById[internalId] = sorted.first()
            for (duplicate in sorted.drop(1)) {
                ops += PreviewProgramOp.Delete(duplicate.rowId)
            }
        }

        val dedupedDesired = desired.distinctBy { it.internalId }
        val desiredById = dedupedDesired.associateBy { it.internalId }

        for ((internalId, row) in canonicalById) {
            if (internalId !in desiredById) {
                ops += PreviewProgramOp.Delete(row.rowId)
            }
        }

        for (candidate in dedupedDesired) {
            val row = canonicalById[candidate.internalId]
            when {
                row == null -> {
                    ops += PreviewProgramOp.Insert(candidate)
                }

                row.browsable -> {
                    ops += PreviewProgramOp.Update(row.rowId, candidate)
                }

                else -> {
                    // User dismissed this item from the preview channel row; do not re-add it.
                }
            }
        }

        return ops
    }

    internal fun artworkKeyFromInternalId(
        kind: PreviewChannelKind,
        internalId: String,
    ): String? = when (kind) {
        PreviewChannelKind.LIVE -> {
            if (!internalId.startsWith(PreviewChannelPlanner.LIVE_INTERNAL_ID_PREFIX)) {
                null
            } else {
                val displayChannelId = internalId.removePrefix(PreviewChannelPlanner.LIVE_INTERNAL_ID_PREFIX)
                displayChannelId.takeIf { it.isNotBlank() }?.let(PreviewChannelPlanner::liveArtworkKey)
            }
        }

        PreviewChannelKind.RECORDINGS -> {
            if (!internalId.startsWith(PreviewChannelPlanner.RECORDING_INTERNAL_ID_PREFIX)) {
                null
            } else {
                val key = internalId.removePrefix(PreviewChannelPlanner.RECORDING_INTERNAL_ID_PREFIX)
                key.takeIf { WatchNextArtworkCache.PREVIEW_KEY_REGEX.matches(it) }
            }
        }
    }
}
