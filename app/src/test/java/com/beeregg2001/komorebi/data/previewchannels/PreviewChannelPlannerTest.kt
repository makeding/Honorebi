package com.beeregg2001.komorebi.data.previewchannels

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.Program
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.watchnext.PreviewArtworkKind
import com.beeregg2001.komorebi.data.watchnext.WatchNextArtworkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PreviewChannelPlannerTest {

    private val nowMillis = Instant.parse("2026-06-15T12:15:00Z").toEpochMilli() // 21:15 JST

    private fun sampleProgram(
        id: String = "prog-1",
        title: String = "News 21",
        startTime: String = "2026-06-15T21:00:00+09:00",
        endTime: String = "2026-06-15T21:30:00+09:00",
        duration: Int = 1800,
    ): Program = Program(
        id = id,
        title = title,
        description = "desc",
        detail = null,
        startTime = startTime,
        endTime = endTime,
        duration = duration,
        genres = null,
    )

    private fun sampleChannel(
        id: String = "gr011",
        displayChannelId: String = id,
        name: String = "TBS",
        type: String = "GR",
        isDisplay: Boolean = true,
        isSubchannel: Boolean = false,
        programPresent: Program? = sampleProgram(),
        programFollowing: Program? = sampleProgram(
            id = "prog-2",
            title = "Late Drama",
            startTime = "2026-06-15T21:30:00+09:00",
            endTime = "2026-06-15T22:00:00+09:00",
        ),
    ): Channel = Channel(
        id = id,
        displayChannelId = displayChannelId,
        name = name,
        channelNumber = "061",
        networkId = 32736,
        serviceId = 1024,
        type = type,
        isWatchable = true,
        is_subchannel = isSubchannel,
        isDisplay = isDisplay,
        programPresent = programPresent,
        programFollowing = programFollowing,
    )

    private fun sampleRecordedChannel(
        id: String = "gr011",
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

    private fun sampleRecordedProgram(
        id: Int,
        title: String = "Recording $id",
        startTime: String = "2026-06-15T20:00:00+09:00",
        endTime: String = "2026-06-15T20:30:00+09:00",
        duration: Double = 1800.0,
        status: String = "Recorded",
        isRecording: Boolean = false,
        channel: RecordedChannel? = sampleRecordedChannel(),
        directThumbnailUrl: String? = "https://example.test/thumb/$id.jpg",
        apiThumbnailUrl: String? = "http://example.test/api/$id.jpg",
    ): RecordedProgram = RecordedProgram(
        id = id,
        title = title,
        description = "desc",
        startTime = startTime,
        endTime = endTime,
        duration = duration,
        isPartiallyRecorded = false,
        channel = channel,
        recordedVideo = RecordedVideo(
            id = id,
            status = status,
            filePath = "/recordings/$id.ts",
            duration = duration,
            containerFormat = "MPEG-TS",
            videoCodec = "MPEG-2",
            audioCodec = "AAC",
        ),
        isRecording = isRecording,
        directThumbnailUrl = directThumbnailUrl,
        apiThumbnailUrl = apiThumbnailUrl,
    )

    @Test
    fun `planLive switches from programPresent to programFollowing at exact boundary and skips expired`() {
        val channel = sampleChannel(id = "gr006", name = "TBS")
        val beforeBoundary = Instant.parse("2026-06-15T21:29:59+09:00").toEpochMilli()
        val atBoundary = Instant.parse("2026-06-15T21:30:00+09:00").toEpochMilli()
        val afterFollowing = Instant.parse("2026-06-15T22:00:00+09:00").toEpochMilli()

        val duringPresent = PreviewChannelPlanner.planLive(listOf(channel), beforeBoundary) { false }
        assertEquals(1, duringPresent.size)
        assertEquals("News 21", duringPresent[0].title)
        assertEquals("TBS · 21:00〜21:30", duringPresent[0].description)
        assertEquals("live:gr006", duringPresent[0].internalId)
        assertEquals("komorebi://live/gr006", duringPresent[0].deepLink)
        assertEquals(PreviewArtworkKind.LIVE, duringPresent[0].artworkKind)

        val duringFollowing = PreviewChannelPlanner.planLive(listOf(channel), atBoundary) { false }
        assertEquals(1, duringFollowing.size)
        assertEquals("Late Drama", duringFollowing[0].title)
        assertEquals("TBS · 21:30〜22:00", duringFollowing[0].description)

        val expired = PreviewChannelPlanner.planLive(listOf(channel), afterFollowing) { false }
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `planLive skips tif, hidden, subchannel, blank displayChannelId, blank title, excluded, and duplicates`() {
        val channels = listOf(
            sampleChannel(id = "tif:input:1", displayChannelId = "tif:input:1"),
            sampleChannel(id = "gr001", name = "Hidden", isDisplay = false),
            sampleChannel(id = "gr002", name = "Sub", isSubchannel = true),
            sampleChannel(id = "gr003", displayChannelId = "   ", name = "BlankId"),
            sampleChannel(id = "gr004", name = "BlankTitle", programPresent = sampleProgram(title = "  ")),
            sampleChannel(id = "gr005", name = "NHK総合"),
            sampleChannel(id = "gr006", name = "TBS"),
            sampleChannel(id = "gr006_dup", displayChannelId = "gr006", name = "TBS Dup"),
            sampleChannel(id = "bs101", name = "BS日テレ", type = "BS"),
        )

        val result = PreviewChannelPlanner.planLive(channels, nowMillis) {
            it.name.contains("NHK")
        }

        assertEquals(listOf("live:gr006", "live:bs101"), result.map { it.internalId })
        assertEquals(listOf(12, 11), result.map { it.weight })
    }

    @Test
    fun `planLive preserves input order and caps at 12`() {
        val channels = (1..15).map { idx ->
            val type = if (idx <= 8) "GR" else "BS"
            sampleChannel(id = "${type.lowercase()}$idx", name = "Ch $idx", type = type)
        }

        val result = PreviewChannelPlanner.planLive(channels, nowMillis) { false }

        assertEquals(12, result.size)
        assertEquals((1..12).map { i -> if (i <= 8) "live:gr$i" else "live:bs$i" }, result.map { it.internalId })
        assertEquals((12 downTo 1).toList(), result.map { it.weight })
    }

    @Test
    fun `planRecordings filters recording-in-progress, excluded, blank title, sorts by endTime desc and caps at 12`() {
        val valid = (1..14).map { idx ->
            sampleRecordedProgram(
                id = idx,
                startTime = Instant.ofEpochMilli(nowMillis - (idx * 7_200_000L) - 1_800_000L).toString(),
                endTime = Instant.ofEpochMilli(nowMillis - idx * 7_200_000L).toString(),
            )
        }
        val recordingFlag = sampleRecordedProgram(
            id = 101,
            isRecording = true,
            endTime = Instant.ofEpochMilli(nowMillis - 1_000L).toString(),
        )
        val recordingStatus = sampleRecordedProgram(
            id = 102,
            status = "Recording",
            endTime = Instant.ofEpochMilli(nowMillis - 2_000L).toString(),
        )
        val nhkRecording = sampleRecordedProgram(
            id = 103,
            channel = sampleRecordedChannel(id = "gr001", name = "NHK総合"),
            endTime = Instant.ofEpochMilli(nowMillis - 3_000L).toString(),
        )
        val unknownChannel = sampleRecordedProgram(
            id = 104,
            channel = null,
            endTime = Instant.ofEpochMilli(nowMillis - 4_000L).toString(),
        )
        val blankTitle = sampleRecordedProgram(
            id = 105,
            title = "   ",
            endTime = Instant.ofEpochMilli(nowMillis - 5_000L).toString(),
        )
        val duplicateNewest = valid.first().copy(title = "Duplicate 1")

        val all = listOf(recordingFlag, recordingStatus, nhkRecording, unknownChannel, blankTitle, duplicateNewest) +
            valid.reversed()

        val activeExcl = PreviewChannelPlanner.planRecordings(
            recordings = all,
            nowMillis = nowMillis,
            exclusionActive = true,
            isExcluded = { it.channel?.name?.contains("NHK") == true },
        )
        assertEquals(12, activeExcl.size)
        assertEquals((1..12).map { "rec:$it" }, activeExcl.map { it.internalId })
        assertEquals((12 downTo 1).toList(), activeExcl.map { it.weight })
        assertEquals("komorebi://recording/1", activeExcl.first().deepLink)
        assertEquals("1", activeExcl.first().artworkKey)
        assertEquals(PreviewArtworkKind.RECORDING, activeExcl.first().artworkKind)

        val inactiveExcl = PreviewChannelPlanner.planRecordings(
            recordings = listOf(unknownChannel, nhkRecording),
            nowMillis = nowMillis,
            exclusionActive = false,
            isExcluded = { false },
        )
        assertEquals(listOf("rec:103", "rec:104"), inactiveExcl.map { it.internalId })
    }

    @Test
    fun `planRecordings keeps only http and https thumbnails and formats JST description`() {
        val program = sampleRecordedProgram(
            id = 50,
            startTime = "2026-06-15T21:30:00+09:00",
            endTime = "2026-06-15T22:00:00+09:00",
            channel = sampleRecordedChannel(name = "テレビ東京"),
            directThumbnailUrl = "file:///tmp/local.jpg",
            apiThumbnailUrl = "https://example.test/api/videos/50/thumbnail",
        )

        val result = PreviewChannelPlanner.planRecordings(
            recordings = listOf(program),
            nowMillis = nowMillis,
            exclusionActive = false,
            isExcluded = { false },
        )

        assertEquals(1, result.size)
        assertEquals(listOf("https://example.test/api/videos/50/thumbnail"), result[0].artworkUrls)
        assertEquals("テレビ東京 · 6/15(月) 21:30", result[0].description)
        assertEquals(1_800_000L, result[0].durationMillis)
    }

    @Test
    fun `nextRefreshDelayMillis clamps between 30s and 15m and returns 15m when empty`() {
        assertEquals(
            PreviewChannelPlanner.MAX_REFRESH_DELAY_MILLIS,
            PreviewChannelPlanner.nextRefreshDelayMillis(emptyList(), nowMillis),
        )

        val endingIn10s = PreviewChannelPlanner.planLive(
            listOf(
                sampleChannel(
                    id = "gr001",
                    programPresent = sampleProgram(
                        startTime = Instant.ofEpochMilli(nowMillis - 60_000L).toString(),
                        endTime = Instant.ofEpochMilli(nowMillis + 10_000L).toString(),
                    ),
                )
            ),
            nowMillis,
        ) { false }
        assertEquals(
            PreviewChannelPlanner.MIN_REFRESH_DELAY_MILLIS,
            PreviewChannelPlanner.nextRefreshDelayMillis(endingIn10s, nowMillis),
        )

        val endingIn3m = PreviewChannelPlanner.planLive(
            listOf(
                sampleChannel(
                    id = "gr001",
                    programPresent = sampleProgram(
                        startTime = Instant.ofEpochMilli(nowMillis - 60_000L).toString(),
                        endTime = Instant.ofEpochMilli(nowMillis + 180_000L).toString(),
                    ),
                ),
                sampleChannel(
                    id = "gr002",
                    programPresent = sampleProgram(
                        startTime = Instant.ofEpochMilli(nowMillis - 60_000L).toString(),
                        endTime = Instant.ofEpochMilli(nowMillis + 600_000L).toString(),
                    ),
                ),
            ),
            nowMillis,
        ) { false }
        assertEquals(
            185_000L,
            PreviewChannelPlanner.nextRefreshDelayMillis(endingIn3m, nowMillis),
        )

        val endingIn45m = PreviewChannelPlanner.planLive(
            listOf(
                sampleChannel(
                    id = "gr001",
                    programPresent = sampleProgram(
                        startTime = Instant.ofEpochMilli(nowMillis - 60_000L).toString(),
                        endTime = Instant.ofEpochMilli(nowMillis + 45L * 60L * 1000L).toString(),
                    ),
                )
            ),
            nowMillis,
        ) { false }
        assertEquals(
            PreviewChannelPlanner.MAX_REFRESH_DELAY_MILLIS,
            PreviewChannelPlanner.nextRefreshDelayMillis(endingIn45m, nowMillis),
        )
    }

    @Test
    fun `liveArtworkKey is stable 40-char lowercase SHA-1 matching preview key regex`() {
        val key1 = PreviewChannelPlanner.liveArtworkKey("gr011")
        val key2 = PreviewChannelPlanner.liveArtworkKey("gr011")
        val keyOther = PreviewChannelPlanner.liveArtworkKey("bs101")

        assertEquals(40, key1.length)
        assertEquals(key1, key2)
        assertNotEquals(key1, keyOther)
        assertTrue(WatchNextArtworkCache.PREVIEW_KEY_REGEX.matches(key1))
        assertTrue(WatchNextArtworkCache.PREVIEW_KEY_REGEX.matches(keyOther))
    }
}
