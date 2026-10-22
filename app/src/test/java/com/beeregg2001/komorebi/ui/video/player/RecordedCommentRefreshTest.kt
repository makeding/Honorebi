package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.ArchivedComment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordedCommentRefreshTest {
    private fun comment(time: Double) = ArchivedComment(time, "$time", "#ffffff", "user", "right", "medium")

    @Test fun `chase merges late history survives failure and schedules missing segment`() = runBlocking {
        val comments = mutableListOf<ArchivedComment>()
        val keys = mutableSetOf<String>()
        val complete = (0 until 501).map { comment(it.toDouble()) }
        var calls = 0
        var failures = 0
        var active = true
        refreshRecordedComments(
            keepRefreshing = { true }, accepts = { active },
            fetch = {
                when (++calls) {
                    1 -> Result.success(complete.take(15))
                    2 -> Result.failure(Exception("offline"))
                    else -> Result.success(complete)
                }
            },
            merge = { appendUniqueArchivedComments(comments, keys, it) },
            onFailure = { failures++; assertEquals(15, comments.size) },
            waitForRefresh = { if (calls == 3) active = false },
        )
        assertEquals(1, failures)
        assertEquals(complete, comments)
        assertEquals(complete.subList(338, 341), ArchivedCommentSchedule().next(comments, 338.0).comments)
    }

    @Test fun `response from replaced session cannot merge`() = runBlocking {
        var active = true
        refreshRecordedComments(
            keepRefreshing = { true }, accepts = { active },
            fetch = { active = false; Result.success(listOf(comment(1.0))) },
            merge = { fail("stale response") }, onFailure = { fail("unexpected failure") },
            waitForRefresh = { fail("stale session repeated") },
        )
    }

    @Test fun `completed recording stops after successful snapshot`() = runBlocking {
        var merged = false
        refreshRecordedComments(
            keepRefreshing = { false }, accepts = { true },
            fetch = { Result.success(emptyList()) }, merge = { merged = true },
            onFailure = { fail("unexpected failure") }, waitForRefresh = { fail("unnecessary refresh") },
        )
        assertTrue(merged)
    }
}
