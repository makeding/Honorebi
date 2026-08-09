package com.beeregg2001.komorebi.ui.video.player.policy

import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.EpgGenre
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePolicyTest {
    @Test
    fun landingEligibilityAcceptsAnimeAndRejectsUnrelatedGenres() {
        assertTrue(isNextEpisodeLandingEligible(program(genres = listOf(EpgGenre("アニメ", "")))))
        assertFalse(isNextEpisodeLandingEligible(program(genres = listOf(EpgGenre("ニュース", "")))))
    }

    @Test
    fun seriesKeyRemovesSpacesAndNormalizesCase() {
        assertEquals("test番組", normalizeQuickSeriesKey(" Test　番 組 "))
    }

    @Test
    fun atxThirtyMinuteRecordingUsesFixedTwentySixMinuteTrigger() {
        val channel = RecordedChannel("CS333", serviceId = 333, displayChannelId = "CS333", type = "CS", name = "AT-X", channelNumber = "333")

        assertEquals(
            26 * 60 * 1000L,
            calculateNextEpisodeCountdownStartMs(program(channel = channel), emptyList(), 30 * 60 * 1000L)
        )
    }

    @Test
    fun cPartClusterKeepsCountdownAtTheNormalEndWindow() {
        val cPartComments = listOf(25 * 60.0, 25 * 60.0 + 10, 25 * 60.0 + 20).map { comment(it, "Cパート") }

        assertTrue(hasCPartSignal(cPartComments, 30 * 60 * 1000L))
        assertEquals(
            30 * 60 * 1000L - NEXT_EPISODE_COUNTDOWN_WINDOW_MS,
            calculateNextEpisodeCountdownStartMs(program(), cPartComments, 30 * 60 * 1000L)
        )
    }

    @Test
    fun climaxRequiresEnoughProgramCommentsAndFindsADensePeak() {
        val durationMs = 30 * 60 * 1000L
        val insufficient = List(79) { comment(60.0 + it) }
        assertEquals(null, calculateCommentClimaxCountdownStartMs(insufficient, durationMs))

        val comments = buildList {
            repeat(45) { add(comment(60.0 + it)) }
            repeat(5) { add(comment(25 * 60.0 + it)) }
            repeat(5) { add(comment(25 * 60.0 + 10 + it)) }
            repeat(15) { add(comment(25 * 60.0 + 20 + it)) }
            repeat(5) { add(comment(25 * 60.0 + 40 + it)) }
            repeat(5) { add(comment(26 * 60.0 + it)) }
        }
        assertNotNull(calculateCommentClimaxCountdownStartMs(comments, durationMs))
    }

    private fun program(
        channel: RecordedChannel? = null,
        genres: List<EpgGenre>? = null
    ) = RecordedProgram(
        id = 1,
        title = "test",
        description = "",
        startTime = "2026-01-01T00:00:00+09:00",
        endTime = "2026-01-01T00:30:00+09:00",
        duration = 30 * 60.0,
        isPartiallyRecorded = false,
        channel = channel,
        recordedVideo = RecordedVideo(1, "Recorded", "/test.ts", duration = 30 * 60.0, containerFormat = "MPEG-TS", videoCodec = "H.264", audioCodec = "AAC"),
        genres = genres
    )

    private fun comment(time: Double, text: String = "comment") =
        ArchivedComment(time, text, "white", "author", "top", "medium")
}
