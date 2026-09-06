package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.beeregg2001.komorebi.data.model.ArchivedComment
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

/** SnapshotStateList.toList is an immutable O(1) snapshot, unlike List.toList. */
internal class ArchivedCommentSnapshots(private val source: SnapshotStateList<ArchivedComment>) {
    var current: List<ArchivedComment> = source.toList()
        private set

    suspend fun observe(fence: RecordedPlaybackFence) {
        snapshotFlow { source.toList() }.collect { snapshot ->
            if (fence.accepts()) current = snapshot
        }
    }
}

internal data class ArchivedCommentBatch(val reset: Boolean, val comments: List<ArchivedComment>)

/** Cursor over immutable, time-sorted snapshots. Deduplication retains only the scheduling window. */
internal class ArchivedCommentSchedule {
    private var previousSnapshot: List<ArchivedComment>? = null
    private var lastPosition: Double? = null
    private var index = 0
    private val scheduled = HashMap<String, Double>()

    fun next(snapshot: List<ArchivedComment>, currentSec: Double): ArchivedCommentBatch {
        val reset = lastPosition?.let { abs(currentSec - it) > 1.5 } ?: false
        if (reset) scheduled.clear()
        val earliest = if (reset) currentSec else currentSec - 0.5
        scheduled.entries.removeAll { it.value < earliest }
        if (reset || snapshot !== previousSnapshot) index = snapshot.firstIndexAtOrAfter(earliest)
        previousSnapshot = snapshot
        lastPosition = currentSec
        val ready = mutableListOf<ArchivedComment>()
        while (index < snapshot.size) {
            val comment = snapshot[index]
            if (comment.time > currentSec + 2.0) break
            if (comment.time >= earliest && scheduled.putIfAbsent(comment.stableCommentKey(), comment.time) == null) {
                ready.add(comment)
            }
            index++
        }
        return ArchivedCommentBatch(reset, ready)
    }
}

private fun List<ArchivedComment>.firstIndexAtOrAfter(timeSec: Double): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].time < timeSec) low = mid + 1 else high = mid
    }
    return low
}
