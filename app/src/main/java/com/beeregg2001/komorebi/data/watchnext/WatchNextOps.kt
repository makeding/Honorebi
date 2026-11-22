package com.beeregg2001.komorebi.data.watchnext

data class ExistingWatchNextRow(
    val rowId: Long,
    val internalId: String,
    val browsable: Boolean,
    val engagementMillis: Long,
    val kind: WatchNextKind?,
)

sealed interface WatchNextOp {
    data class Insert(val candidate: WatchNextCandidate) : WatchNextOp
    data class Update(val rowId: Long, val candidate: WatchNextCandidate) : WatchNextOp
    data class Delete(val rowId: Long) : WatchNextOp
}

object WatchNextOps {

    fun parseRecordedProgramId(internalId: String): Int? {
        if (!internalId.startsWith(WatchNextPlanner.INTERNAL_ID_PREFIX)) return null
        val rawId = internalId.removePrefix(WatchNextPlanner.INTERNAL_ID_PREFIX)
        if (rawId.isEmpty() || !rawId.all { it in '0'..'9' }) return null
        return rawId.toIntOrNull()?.takeIf { it > 0 }
    }

    fun planOps(
        existing: List<ExistingWatchNextRow>,
        desired: List<WatchNextCandidate>,
        preserveNewRows: Boolean,
    ): List<WatchNextOp> {
        val ops = mutableListOf<WatchNextOp>()
        val managedExisting = existing.filter {
            it.internalId.startsWith(WatchNextPlanner.INTERNAL_ID_PREFIX)
        }

        val canonicalById = LinkedHashMap<String, ExistingWatchNextRow>()
        for ((internalId, group) in managedExisting.groupBy { it.internalId }) {
            val sorted = group.sortedByDescending { it.rowId }
            canonicalById[internalId] = sorted.first()
            for (duplicate in sorted.drop(1)) {
                ops += WatchNextOp.Delete(duplicate.rowId)
            }
        }

        val dedupedDesired = desired.distinctBy { it.internalId }
        val desiredById = dedupedDesired.associateBy { it.internalId }

        for ((internalId, row) in canonicalById) {
            if (internalId !in desiredById) {
                if (preserveNewRows && row.kind == WatchNextKind.NEW) {
                    continue
                }
                ops += WatchNextOp.Delete(row.rowId)
            }
        }

        for (candidate in dedupedDesired) {
            val row = canonicalById[candidate.internalId]
            when {
                row == null -> {
                    ops += WatchNextOp.Insert(candidate)
                }

                row.browsable -> {
                    ops += WatchNextOp.Update(row.rowId, candidate)
                }

                else -> {
                    val shouldReAdd = candidate.kind == WatchNextKind.CONTINUE && (
                        row.kind != WatchNextKind.CONTINUE ||
                            candidate.engagementMillis > row.engagementMillis
                        )
                    if (shouldReAdd) {
                        ops += WatchNextOp.Delete(row.rowId)
                        ops += WatchNextOp.Insert(candidate)
                    }
                }
            }
        }

        return ops
    }
}
