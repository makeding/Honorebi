package com.beeregg2001.komorebi.data.previewchannels

import com.beeregg2001.komorebi.data.watchnext.PreviewArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewChannelOpsTest {

    private fun candidate(
        internalId: String,
        kind: PreviewChannelKind = PreviewChannelKind.LIVE,
        weight: Int = 10,
    ): PreviewProgramCandidate = PreviewProgramCandidate(
        internalId = internalId,
        kind = kind,
        title = "Title $internalId",
        description = "Desc $internalId",
        startMillis = if (kind == PreviewChannelKind.LIVE) 1_000L else null,
        endMillis = if (kind == PreviewChannelKind.LIVE) 2_000L else null,
        durationMillis = 1_000L,
        deepLink = if (kind == PreviewChannelKind.LIVE) {
            "komorebi://live/${internalId.removePrefix("live:")}"
        } else {
            "komorebi://recording/${internalId.removePrefix("rec:")}"
        },
        artworkKind = if (kind == PreviewChannelKind.LIVE) PreviewArtworkKind.LIVE else PreviewArtworkKind.RECORDING,
        artworkKey = if (kind == PreviewChannelKind.LIVE) {
            PreviewChannelPlanner.liveArtworkKey(internalId.removePrefix("live:"))
        } else {
            internalId.removePrefix("rec:")
        },
        artworkUrls = emptyList(),
        weight = weight,
    )

    @Test
    fun `deletes undesired rows, updates browsable rows, and inserts missing rows`() {
        val existing = listOf(
            ExistingPreviewProgram(rowId = 1L, internalId = "live:gr001", browsable = true),
            ExistingPreviewProgram(rowId = 2L, internalId = "live:gr002", browsable = true),
        )
        val c1 = candidate("live:gr001", weight = 12)
        val c3 = candidate("live:gr003", weight = 11)

        val ops = PreviewChannelOps.planOps(existing, listOf(c1, c3))

        assertEquals(
            listOf(
                PreviewProgramOp.Delete(2L),
                PreviewProgramOp.Update(1L, c1),
                PreviewProgramOp.Insert(c3),
            ),
            ops,
        )
    }

    @Test
    fun `duplicate internalIds keep highest rowId and delete lower rowIds`() {
        val existing = listOf(
            ExistingPreviewProgram(rowId = 5L, internalId = "rec:42", browsable = true),
            ExistingPreviewProgram(rowId = 12L, internalId = "rec:42", browsable = true),
            ExistingPreviewProgram(rowId = 8L, internalId = "rec:42", browsable = true),
        )
        val c42 = candidate("rec:42", PreviewChannelKind.RECORDINGS)

        val ops = PreviewChannelOps.planOps(existing, listOf(c42))

        assertEquals(
            listOf(
                PreviewProgramOp.Delete(8L),
                PreviewProgramOp.Delete(5L),
                PreviewProgramOp.Update(12L, c42),
            ),
            ops,
        )
    }

    @Test
    fun `non-browsable desired row is skipped and never re-inserted, while undesired non-browsable row is deleted`() {
        val existing = listOf(
            ExistingPreviewProgram(rowId = 10L, internalId = "rec:5", browsable = false),
            ExistingPreviewProgram(rowId = 11L, internalId = "rec:6", browsable = false),
        )
        val c5 = candidate("rec:5", PreviewChannelKind.RECORDINGS)

        val ops = PreviewChannelOps.planOps(existing, listOf(c5))

        assertEquals(listOf(PreviewProgramOp.Delete(11L)), ops)
    }

    @Test
    fun `blank internalId rows are deleted and duplicate desired candidates are deduplicated`() {
        val existing = listOf(
            ExistingPreviewProgram(rowId = 1L, internalId = "", browsable = true),
            ExistingPreviewProgram(rowId = 2L, internalId = "   ", browsable = true),
        )
        val c1 = candidate("live:gr001", weight = 12)
        val c1Dup = candidate("live:gr001", weight = 11)

        val ops = PreviewChannelOps.planOps(existing, listOf(c1, c1Dup))

        assertEquals(
            listOf(
                PreviewProgramOp.Delete(1L),
                PreviewProgramOp.Delete(2L),
                PreviewProgramOp.Insert(c1),
            ),
            ops,
        )
    }

    @Test
    fun `empty existing and desired produces empty ops`() {
        assertTrue(PreviewChannelOps.planOps(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `artworkKeyFromInternalId resolves valid keys and rejects invalid ones`() {
        assertEquals(
            PreviewChannelPlanner.liveArtworkKey("gr011"),
            PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.LIVE, "live:gr011"),
        )
        assertNull(PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.LIVE, "live:"))
        assertNull(PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.LIVE, "rec:12"))

        assertEquals(
            "42",
            PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.RECORDINGS, "rec:42"),
        )
        assertNull(PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.RECORDINGS, "rec:../42"))
        assertNull(PreviewChannelOps.artworkKeyFromInternalId(PreviewChannelKind.RECORDINGS, "live:gr011"))
    }
}
