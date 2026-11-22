package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NextEpisodeResolverTest {

    private val nowMillis = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

    private fun channel(
        id: String = "gr006",
        name: String = "TBS",
    ): RecordedChannel = RecordedChannel(
        id = id,
        networkId = 32736,
        serviceId = 1024,
        displayChannelId = id,
        type = "GR",
        name = name,
        channelNumber = "061",
    )

    private fun episode(
        id: Int,
        title: String,
        seriesId: Int? = 10,
        seriesName: String? = null,
        episodeNumber: String? = null,
        isEpisodic: Boolean? = null,
        startTime: String = "2026-06-15T10:00:00+09:00",
        endTime: String = "2026-06-15T10:30:00+09:00",
        duration: Double = 1800.0,
        videoDuration: Double = duration,
        isPartiallyRecorded: Boolean = false,
        isRecording: Boolean = false,
        status: String = "Recorded",
        playbackPosition: Double = 0.0,
        channel: RecordedChannel? = channel(),
    ): RecordedProgram = RecordedProgram(
        id = id,
        title = title,
        seriesId = seriesId,
        seriesName = seriesName,
        episodeNumber = episodeNumber,
        isEpisodic = isEpisodic,
        description = "desc",
        startTime = startTime,
        endTime = endTime,
        duration = duration,
        isPartiallyRecorded = isPartiallyRecorded,
        channel = channel,
        recordedVideo = RecordedVideo(
            id = id,
            status = status,
            filePath = "/recordings/$id.ts",
            duration = videoDuration,
            containerFormat = "MPEG-TS",
            videoCodec = "MPEG-2",
            audioCodec = "AAC",
        ),
        isRecording = isRecording,
        playbackPosition = playbackPosition,
    )

    @Test
    fun `resolves next episode by episode number`() {
        val ep1 = episode(id = 1, title = "葬送のフリーレン 第1話", episodeNumber = "1")
        val ep2 = episode(id = 2, title = "葬送のフリーレン 第2話", episodeNumber = "2")
        val ep3 = episode(id = 3, title = "葬送のフリーレン 第3話", episodeNumber = "3")

        val next = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep3, ep1, ep2),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )

        assertEquals(2, next?.id)
    }

    @Test
    fun `skips already completed next episodes from history or program playbackPosition`() {
        val ep1 = episode(id = 1, title = "作品A 第1話", episodeNumber = "1")
        val ep2 = episode(id = 2, title = "作品A 第2話", episodeNumber = "2")
        val ep3 = episode(id = 3, title = "作品A 第3話", episodeNumber = "3", playbackPosition = 1750.0)
        val ep4 = episode(id = 4, title = "作品A 第4話", episodeNumber = "4", playbackPosition = 4.0)

        val history = mapOf(
            2 to WatchNextHistoryInput(2, "作品A 第2話", 1800.0, 1720.0, nowMillis - 10_000L),
        )

        val next = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep1, ep2, ep3, ep4),
            history = history,
            exclusionActive = false,
            isExcluded = { false },
        )

        assertEquals(4, next?.id)
    }

    @Test
    fun `returns null when next episode is already in progress`() {
        val ep1 = episode(id = 1, title = "作品A 第1話", episodeNumber = "1")
        val ep2 = episode(id = 2, title = "作品A 第2話", episodeNumber = "2")
        val ep3 = episode(id = 3, title = "作品A 第3話", episodeNumber = "3")

        val history = mapOf(
            2 to WatchNextHistoryInput(2, "作品A 第2話", 1800.0, 300.0, nowMillis - 10_000L),
        )

        val next = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep2, ep3),
            history = history,
            exclusionActive = false,
            isExcluded = { false },
        )

        assertNull(next)
    }

    @Test
    fun `matches by seriesId when both present and falls back to normalized title otherwise`() {
        val srcWithId = episode(id = 1, title = "別名タイトルA 第1話", seriesId = 42, episodeNumber = "1")
        val sameIdDifferentTitle = episode(id = 2, title = "全然違う表記 第2話", seriesId = 42, episodeNumber = "2")
        val differentIdSameTitle = episode(id = 3, title = "別名タイトルA 第2話", seriesId = 99, episodeNumber = "2")

        assertTrue(NextEpisodeResolver.isSameSeries(srcWithId, sameIdDifferentTitle))
        assertFalse(NextEpisodeResolver.isSameSeries(srcWithId, differentIdSameTitle))

        val resolvedById = NextEpisodeResolver.resolveNext(
            source = srcWithId,
            candidates = listOf(differentIdSameTitle, sameIdDifferentTitle),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )
        assertEquals(2, resolvedById?.id)

        val srcNoId = episode(id = 10, title = "TVアニメ「葬送のフリーレン」 第1話[字]", seriesId = null, episodeNumber = "1")
        val candNoId = episode(id = 11, title = "葬送のフリーレン 第2話(再)", seriesId = null, episodeNumber = "2")
        val unrelated = episode(id = 12, title = "薬屋のひとりごと 第2話", seriesId = null, episodeNumber = "2")

        assertTrue(NextEpisodeResolver.isSameSeries(srcNoId, candNoId))
        assertFalse(NextEpisodeResolver.isSameSeries(srcNoId, unrelated))

        val resolvedByTitle = NextEpisodeResolver.resolveNext(
            source = srcNoId,
            candidates = listOf(unrelated, candNoId),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )
        assertEquals(11, resolvedByTitle?.id)
    }

    @Test
    fun `prefers complete recording and matching channel among duplicate episode recordings`() {
        val tbs = channel(id = "gr006", name = "TBS")
        val bs = channel(id = "bs161", name = "BS-TBS")

        val ep1 = episode(id = 1, title = "作品B 第1話", episodeNumber = "1", channel = tbs)
        val ep2OtherChannel = episode(id = 2, title = "作品B 第2話", episodeNumber = "2", channel = bs)
        val ep2SameChannelPartial = episode(
            id = 3,
            title = "作品B 第2話",
            episodeNumber = "2",
            channel = tbs,
            isPartiallyRecorded = true,
        )
        val ep2SameChannelComplete = episode(
            id = 4,
            title = "作品B 第2話",
            episodeNumber = "2",
            channel = tbs,
            isPartiallyRecorded = false,
        )

        val preferred = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep2OtherChannel, ep2SameChannelPartial, ep2SameChannelComplete),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )
        assertEquals(4, preferred?.id)

        // When only partial on same channel and complete on other channel, complete wins
        val completeWins = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep2SameChannelPartial, ep2OtherChannel),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )
        assertEquals(2, completeWins?.id)
    }

    @Test
    fun `skips excluded channels, null channel under active exclusion, and currently recording programs`() {
        val nhk = channel(id = "gr001", name = "NHK総合")
        val tbs = channel(id = "gr006", name = "TBS")

        val ep1 = episode(id = 1, title = "作品C 第1話", episodeNumber = "1", channel = tbs)
        val ep2Excluded = episode(id = 2, title = "作品C 第2話", episodeNumber = "2", channel = nhk)
        val ep2NullChannel = episode(id = 3, title = "作品C 第2話", episodeNumber = "2", channel = null)
        val ep2Recording = episode(id = 4, title = "作品C 第2話", episodeNumber = "2", channel = tbs, isRecording = true)
        val ep3Valid = episode(id = 5, title = "作品C 第3話", episodeNumber = "3", channel = tbs)

        val next = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(ep2Excluded, ep2NullChannel, ep2Recording, ep3Valid),
            history = emptyMap(),
            exclusionActive = true,
            isExcluded = { it.channel?.name?.contains("NHK") == true },
        )

        assertEquals(5, next?.id)
    }

    @Test
    fun `falls back to ascending startTime order when episode numbers are absent`() {
        val ep1 = episode(
            id = 1,
            title = "ドキュメント72時間",
            seriesId = 77,
            episodeNumber = null,
            isEpisodic = true,
            startTime = "2026-06-01T22:00:00+09:00",
        )
        val earlier = episode(
            id = 2,
            title = "ドキュメント72時間",
            seriesId = 77,
            episodeNumber = null,
            isEpisodic = true,
            startTime = "2026-05-25T22:00:00+09:00",
        )
        val nextLater = episode(
            id = 3,
            title = "ドキュメント72時間",
            seriesId = 77,
            episodeNumber = null,
            isEpisodic = true,
            startTime = "2026-06-08T22:00:00+09:00",
        )
        val evenLater = episode(
            id = 4,
            title = "ドキュメント72時間",
            seriesId = 77,
            episodeNumber = null,
            isEpisodic = true,
            startTime = "2026-06-15T22:00:00+09:00",
        )

        val next = NextEpisodeResolver.resolveNext(
            source = ep1,
            candidates = listOf(evenLater, earlier, nextLater),
            history = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
        )

        assertEquals(3, next?.id)
    }

    @Test
    fun `isSeriesEpisode detects seriesId, episodeNumber, or isEpisodic`() {
        assertTrue(NextEpisodeResolver.isSeriesEpisode(episode(id = 1, title = "A", seriesId = 1, episodeNumber = null, isEpisodic = false)))
        assertTrue(NextEpisodeResolver.isSeriesEpisode(episode(id = 2, title = "A", seriesId = null, episodeNumber = "2", isEpisodic = false)))
        assertTrue(NextEpisodeResolver.isSeriesEpisode(episode(id = 3, title = "A", seriesId = null, episodeNumber = null, isEpisodic = true)))
        assertFalse(NextEpisodeResolver.isSeriesEpisode(episode(id = 4, title = "単発映画", seriesId = null, episodeNumber = null, isEpisodic = false)))
    }
}
