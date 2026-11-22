package com.beeregg2001.komorebi.data.watchnext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextOpsTest {

    private fun candidate(
        id: Int,
        kind: WatchNextKind = WatchNextKind.CONTINUE,
        engagementMillis: Long = 10_000L,
    ): WatchNextCandidate = WatchNextCandidate(
        recordedProgramId = id,
        kind = kind,
        title = "Program $id",
        description = null,
        durationMillis = if (kind == WatchNextKind.CONTINUE) 1_800_000L else null,
        positionMillis = if (kind == WatchNextKind.CONTINUE) 300_000L else null,
        engagementMillis = engagementMillis,
        thumbnailUrls = emptyList(),
    )

    @Test
    fun `deletes stale rows, updates browsable rows, and inserts new rows`() {
        val existing = listOf(
            ExistingWatchNextRow(1L, "rec:10", browsable = true, engagementMillis = 5_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(2L, "rec:20", browsable = true, engagementMillis = 4_000L, kind = WatchNextKind.NEW),
        )
        val c10 = candidate(10, WatchNextKind.CONTINUE, 9_000L)
        val c30 = candidate(30, WatchNextKind.NEW, 8_000L)

        val ops = WatchNextOps.planOps(existing, listOf(c10, c30), preserveNewRows = false)

        assertEquals(
            listOf(
                WatchNextOp.Delete(2L),
                WatchNextOp.Update(1L, c10),
                WatchNextOp.Insert(c30),
            ),
            ops,
        )
    }

    @Test
    fun `dismissed NEW row is skipped and never re-added`() {
        val existing = listOf(
            ExistingWatchNextRow(10L, "rec:5", browsable = false, engagementMillis = 5_000L, kind = WatchNextKind.NEW),
        )
        val c5 = candidate(5, WatchNextKind.NEW, 9_000L)

        val ops = WatchNextOps.planOps(existing, listOf(c5), preserveNewRows = false)

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `dismissed CONTINUE row is skipped unless engagement is newer, and dismissed NEW is re-added when promoted to CONTINUE`() {
        val existing = listOf(
            ExistingWatchNextRow(1L, "rec:1", browsable = false, engagementMillis = 10_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(2L, "rec:2", browsable = false, engagementMillis = 10_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(3L, "rec:3", browsable = false, engagementMillis = 20_000L, kind = WatchNextKind.NEW),
        )
        val c1SameTime = candidate(1, WatchNextKind.CONTINUE, 10_000L)
        val c2NewerTime = candidate(2, WatchNextKind.CONTINUE, 11_000L)
        val c3Promoted = candidate(3, WatchNextKind.CONTINUE, 15_000L)

        val ops = WatchNextOps.planOps(existing, listOf(c1SameTime, c2NewerTime, c3Promoted), preserveNewRows = false)

        assertEquals(
            listOf(
                WatchNextOp.Delete(2L),
                WatchNextOp.Insert(c2NewerTime),
                WatchNextOp.Delete(3L),
                WatchNextOp.Insert(c3Promoted),
            ),
            ops,
        )
    }

    @Test
    fun `preserveNewRows keeps stale NEW rows while still deleting stale CONTINUE rows`() {
        val existing = listOf(
            ExistingWatchNextRow(1L, "rec:1", browsable = true, engagementMillis = 10_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(2L, "rec:2", browsable = true, engagementMillis = 9_000L, kind = WatchNextKind.NEW),
        )

        val ops = WatchNextOps.planOps(existing, emptyList(), preserveNewRows = true)

        assertEquals(listOf(WatchNextOp.Delete(1L)), ops)
    }

    @Test
    fun `duplicate rows for same internalId keep highest rowId and delete lower rowIds`() {
        val existing = listOf(
            ExistingWatchNextRow(5L, "rec:42", browsable = true, engagementMillis = 1_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(12L, "rec:42", browsable = true, engagementMillis = 2_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(8L, "rec:42", browsable = true, engagementMillis = 1_500L, kind = WatchNextKind.CONTINUE),
        )
        val c42 = candidate(42, WatchNextKind.CONTINUE, 5_000L)

        val ops = WatchNextOps.planOps(existing, listOf(c42), preserveNewRows = false)

        assertEquals(
            listOf(
                WatchNextOp.Delete(8L),
                WatchNextOp.Delete(5L),
                WatchNextOp.Update(12L, c42),
            ),
            ops,
        )
    }

    @Test
    fun `rows without rec prefix are completely ignored`() {
        val existing = listOf(
            ExistingWatchNextRow(100L, "live:gr011", browsable = true, engagementMillis = 1_000L, kind = WatchNextKind.NEW),
            ExistingWatchNextRow(101L, "", browsable = true, engagementMillis = 1_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(102L, "other:99", browsable = true, engagementMillis = 1_000L, kind = WatchNextKind.NEW),
        )

        val ops = WatchNextOps.planOps(existing, emptyList(), preserveNewRows = false)

        assertTrue(ops.isEmpty())
    }

    @Test
    fun `preserveNextRows keeps stale NEXT rows while still deleting stale CONTINUE and NEW rows`() {
        val existing = listOf(
            ExistingWatchNextRow(1L, "rec:1", browsable = true, engagementMillis = 10_000L, kind = WatchNextKind.CONTINUE),
            ExistingWatchNextRow(2L, "rec:2", browsable = true, engagementMillis = 9_000L, kind = WatchNextKind.NEW),
            ExistingWatchNextRow(3L, "rec:3", browsable = true, engagementMillis = 8_000L, kind = WatchNextKind.NEXT),
        )

        val ops = WatchNextOps.planOps(
            existing = existing,
            desired = emptyList(),
            preserveNewRows = false,
            preserveNextRows = true,
        )

        assertEquals(listOf(WatchNextOp.Delete(1L), WatchNextOp.Delete(2L)), ops)
    }

    @Test
    fun `dismissed NEXT row is skipped and never re-added as NEXT, but re-added when promoted to CONTINUE`() {
        val existing = listOf(
            ExistingWatchNextRow(10L, "rec:5", browsable = false, engagementMillis = 5_000L, kind = WatchNextKind.NEXT),
            ExistingWatchNextRow(11L, "rec:6", browsable = false, engagementMillis = 5_000L, kind = WatchNextKind.NEXT),
        )
        val c5Next = candidate(5, WatchNextKind.NEXT, 9_000L)
        val c6Continue = candidate(6, WatchNextKind.CONTINUE, 9_000L)

        val ops = WatchNextOps.planOps(existing, listOf(c5Next, c6Continue), preserveNewRows = false)

        assertEquals(
            listOf(
                WatchNextOp.Delete(11L),
                WatchNextOp.Insert(c6Continue),
            ),
            ops,
        )
    }
}
