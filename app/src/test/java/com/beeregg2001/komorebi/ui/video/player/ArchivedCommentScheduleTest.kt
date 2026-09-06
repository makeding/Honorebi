package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import com.beeregg2001.komorebi.data.model.ArchivedComment
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ArchivedCommentScheduleTest {
    private fun comment(time: Double, text: String = "$time") =
        ArchivedComment(time, text, "#ffffff", "user", "right", "medium")

    @Test fun `late initial load and chase insertion deliver new comments without requeueing lookahead`() {
        val schedule = ArchivedCommentSchedule()
        assertTrue(schedule.next(emptyList(), 10.0).comments.isEmpty())
        val first = listOf(comment(9.0), comment(10.5), comment(12.0))
        assertEquals(first.drop(1), schedule.next(first, 10.5).comments)
        val updated = (first + comment(10.8) + comment(12.5)).sortedBy { it.time }
        assertEquals(listOf(comment(10.8), comment(12.5)), schedule.next(updated, 11.0).comments)
        assertTrue(schedule.next(updated, 11.5).comments.isEmpty())
        assertTrue(schedule.next(updated.toMutableList(), 12.0).comments.isEmpty())
    }

    @Test fun `seek clears reservations and binary search skips history`() {
        val comments = (0..10000).map { comment(it.toDouble()) }
        val schedule = ArchivedCommentSchedule()
        schedule.next(comments, 5000.0)
        val forward = schedule.next(comments, 9000.0)
        assertTrue(forward.reset)
        assertEquals(comments.subList(9000, 9003), forward.comments)
        val backward = schedule.next(comments, 5000.0)
        assertTrue(backward.reset)
        assertEquals(comments.subList(5000, 5003), backward.comments)
        val freshProgram = ArchivedCommentSchedule()
        assertEquals(comments.subList(5000, 5003), freshProgram.next(comments, 5000.0).comments)
    }

    @Test fun `replacement shorter than old cursor still schedules late data`() {
        val schedule = ArchivedCommentSchedule()
        schedule.next((0..102).map { comment(it.toDouble()) }, 100.0)
        val replacement = listOf(comment(100.5, "late"), comment(102.0))
        assertEquals(listOf(replacement.first()), schedule.next(replacement, 100.5).comments)
    }

    @Test fun `observable chase list publishes immutable snapshots only on updates and honors fence`() = runBlocking {
        val source = mutableStateListOf<ArchivedComment>()
        val snapshots = ArchivedCommentSnapshots(source)
        var active = true
        val token = com.beeregg2001.komorebi.ui.player.RecordedPlaybackToken(1L, 1L, 1)
        val fence = RecordedPlaybackFence(token, { active }, token)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { snapshots.observe(fence) }
        try {
            val knownKeys = mutableSetOf<String>()
            appendUniqueArchivedComments(source, knownKeys, listOf(comment(1.0)))
            Snapshot.sendApplyNotifications()
            yield()
            val loaded = snapshots.current
            assertEquals(listOf(comment(1.0)), loaded)
            repeat(10) { assertSame(loaded, snapshots.current) }
            appendUniqueArchivedComments(source, knownKeys, listOf(comment(1.0), comment(2.0)))
            Snapshot.sendApplyNotifications()
            yield()
            assertEquals(listOf(comment(1.0)), loaded) // retained background snapshot never mutates
            assertEquals(listOf(comment(1.0), comment(2.0)), snapshots.current)
            active = false
            val beforeSwitch = snapshots.current
            source.add(comment(3.0))
            Snapshot.sendApplyNotifications()
            yield()
            assertSame(beforeSwitch, snapshots.current)
        } finally { observer.cancelAndJoin() }
    }
}
