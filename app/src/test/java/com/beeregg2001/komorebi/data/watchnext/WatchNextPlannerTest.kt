package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchNextPlannerTest {

    private val nowMillis = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

    private fun sampleChannel(
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

    private fun sampleProgram(
        id: Int,
        title: String = "Program $id",
        startTime: String = "2026-06-15T10:00:00+09:00",
        endTime: String = "2026-06-15T10:30:00+09:00",
        duration: Double = 1800.0,
        videoDuration: Double = duration,
        status: String = "Recorded",
        isRecording: Boolean = false,
        playbackPosition: Double = 0.0,
        channel: RecordedChannel? = sampleChannel(),
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
            duration = videoDuration,
            containerFormat = "MPEG-TS",
            videoCodec = "MPEG-2",
            audioCodec = "AAC",
        ),
        isRecording = isRecording,
        playbackPosition = playbackPosition,
        directThumbnailUrl = directThumbnailUrl,
        apiThumbnailUrl = apiThumbnailUrl,
    )

    @Test
    fun `CONTINUE items are ordered by watchedAt desc and capped at 6`() {
        val programs = (1..8).associateWith { sampleProgram(it) }
        val history = (1..8).map { id ->
            WatchNextHistoryInput(
                id = id,
                title = "Show $id",
                durationSeconds = 1800.0,
                positionSeconds = 300.0,
                watchedAtMillis = nowMillis - (10 - id) * 60_000L,
            )
        }

        val result = WatchNextPlanner.plan(
            history = history,
            recordings = emptyList(),
            lookup = programs,
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        assertEquals(6, result.size)
        assertEquals(listOf(8, 7, 6, 5, 4, 3), result.map { it.recordedProgramId })
        assertTrue(result.all { it.kind == WatchNextKind.CONTINUE })
        assertEquals(1_800_000L, result.first().durationMillis)
        assertEquals(300_000L, result.first().positionMillis)
    }

    @Test
    fun `CONTINUE drops items at or below 5s and at or above 95 percent completion`() {
        val history = listOf(
            WatchNextHistoryInput(1, "Too Early", 1000.0, 5.0, nowMillis - 1_000L),
            WatchNextHistoryInput(2, "Valid Lower", 1000.0, 5.1, nowMillis - 2_000L),
            WatchNextHistoryInput(3, "Valid Upper", 1000.0, 949.9, nowMillis - 3_000L),
            WatchNextHistoryInput(4, "Completed Exact", 1000.0, 950.0, nowMillis - 4_000L),
            WatchNextHistoryInput(5, "Completed Over", 1000.0, 995.0, nowMillis - 5_000L),
            WatchNextHistoryInput(6, "Unknown Duration", 0.0, 120.0, nowMillis - 6_000L),
        )

        val result = WatchNextPlanner.plan(
            history = history,
            recordings = emptyList(),
            lookup = emptyMap(),
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        assertEquals(listOf(2, 3, 6), result.map { it.recordedProgramId })
        val unknownDurationItem = result.first { it.recordedProgramId == 6 }
        assertNull(unknownDurationItem.durationMillis)
        assertNull(unknownDurationItem.positionMillis)
    }

    @Test
    fun `NEW filters recording, watched, duplicates, old, and unparseable endTime and caps at 4`() {
        val validRecent = (1..6).map { idx ->
            sampleProgram(
                id = 100 + idx,
                endTime = Instant.ofEpochMilli(nowMillis - idx * 3_600_000L).toString(),
            )
        }
        val duplicateOfNewest = validRecent.first().copy(title = "Duplicate")
        val currentlyRecordingFlag = sampleProgram(
            id = 201,
            isRecording = true,
            endTime = Instant.ofEpochMilli(nowMillis - 10_000L).toString(),
        )
        val currentlyRecordingStatus = sampleProgram(
            id = 202,
            status = "Recording",
            endTime = Instant.ofEpochMilli(nowMillis - 20_000L).toString(),
        )
        val watchedViaProgramPos = sampleProgram(
            id = 203,
            playbackPosition = 6.0,
            endTime = Instant.ofEpochMilli(nowMillis - 30_000L).toString(),
        )
        val watchedViaHistoryCompleted = sampleProgram(
            id = 204,
            playbackPosition = 0.0,
            endTime = Instant.ofEpochMilli(nowMillis - 40_000L).toString(),
        )
        val watchedViaHistoryInProgress = sampleProgram(
            id = 205,
            playbackPosition = 0.0,
            endTime = Instant.ofEpochMilli(nowMillis - 50_000L).toString(),
        )
        val olderThan14Days = sampleProgram(
            id = 206,
            endTime = Instant.ofEpochMilli(nowMillis - WatchNextPlanner.NEW_WINDOW_MILLIS - 1L).toString(),
        )
        val unparseableEndTime = sampleProgram(
            id = 207,
            endTime = "not-a-timestamp",
        )
        val watchedAtBoundaryStillNew = sampleProgram(
            id = 208,
            playbackPosition = 5.0,
            endTime = Instant.ofEpochMilli(nowMillis - 1_000L).toString(),
        )

        val history = listOf(
            WatchNextHistoryInput(204, "Done", 1800.0, 1750.0, nowMillis - 100_000L),
            WatchNextHistoryInput(205, "Watching", 1800.0, 120.0, nowMillis - 90_000L),
        )
        val allRecordings = listOf(
            duplicateOfNewest,
            currentlyRecordingFlag,
            currentlyRecordingStatus,
            watchedViaProgramPos,
            watchedViaHistoryCompleted,
            watchedViaHistoryInProgress,
            olderThan14Days,
            unparseableEndTime,
            watchedAtBoundaryStillNew,
        ) + validRecent

        val result = WatchNextPlanner.plan(
            history = history,
            recordings = allRecordings,
            lookup = allRecordings.associateBy { it.id },
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        val continueItems = result.filter { it.kind == WatchNextKind.CONTINUE }
        val newItems = result.filter { it.kind == WatchNextKind.NEW }
        assertEquals(listOf(205), continueItems.map { it.recordedProgramId })
        assertEquals(listOf(208, 101, 102, 103), newItems.map { it.recordedProgramId })
        assertTrue(newItems.all { it.positionMillis == null })
    }

    @Test
    fun `total is at most 10 and NEW engagement stays strictly below min CONTINUE and strictly decreasing`() {
        val continueHistory = (1..7).map { id ->
            WatchNextHistoryInput(
                id = id,
                title = "Continue $id",
                durationSeconds = 1800.0,
                positionSeconds = 300.0,
                watchedAtMillis = nowMillis - (id * 86_400_000L),
            )
        }
        val newRecordings = (10..15).map { id ->
            sampleProgram(
                id = id,
                endTime = Instant.ofEpochMilli(nowMillis - (id - 10) * 1_000L).toString(),
            )
        }

        val result = WatchNextPlanner.plan(
            history = continueHistory,
            recordings = newRecordings,
            lookup = newRecordings.associateBy { it.id },
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        assertEquals(10, result.size)
        val continueItems = result.filter { it.kind == WatchNextKind.CONTINUE }
        val newItems = result.filter { it.kind == WatchNextKind.NEW }
        assertEquals(6, continueItems.size)
        assertEquals(4, newItems.size)

        val minContinueEngagement = continueItems.minOf { it.engagementMillis }
        assertTrue(result.all { it.engagementMillis <= nowMillis })
        assertTrue(newItems.all { it.engagementMillis < minContinueEngagement })
        for (i in 0 until newItems.lastIndex) {
            assertTrue(newItems[i].engagementMillis > newItems[i + 1].engagementMillis)
        }
    }

    @Test
    fun `future watchedAt and future recording endTime are clamped to now`() {
        val futureWatchedAt = nowMillis + 3_600_000L
        val futureEnd = Instant.ofEpochMilli(nowMillis + 7_200_000L).toString()
        val futureRecording = sampleProgram(id = 2, endTime = futureEnd)

        val result = WatchNextPlanner.plan(
            history = listOf(
                WatchNextHistoryInput(1, "Future Continue", 1800.0, 200.0, futureWatchedAt),
            ),
            recordings = listOf(futureRecording),
            lookup = mapOf(2 to futureRecording),
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        assertEquals(2, result.size)
        assertEquals(nowMillis, result[0].engagementMillis)
        assertTrue(result[1].engagementMillis < result[0].engagementMillis)
        assertTrue(result[1].engagementMillis <= nowMillis)
    }

    @Test
    fun `NHK exclusion skips unknown channels when active, keeps when inactive, and drops excluded channels`() {
        val nhkProgram = sampleProgram(
            id = 1,
            channel = sampleChannel(id = "gr001", name = "NHK総合"),
            endTime = Instant.ofEpochMilli(nowMillis - 10_000L).toString(),
        )
        val tbsProgram = sampleProgram(
            id = 2,
            channel = sampleChannel(id = "gr006", name = "TBS"),
            endTime = Instant.ofEpochMilli(nowMillis - 20_000L).toString(),
        )
        val noChannelRecording = sampleProgram(
            id = 4,
            channel = null,
            endTime = Instant.ofEpochMilli(nowMillis - 30_000L).toString(),
        )
        val history = listOf(
            WatchNextHistoryInput(1, "NHK Show", 1800.0, 300.0, nowMillis - 1_000L),
            WatchNextHistoryInput(2, "TBS Show", 1800.0, 300.0, nowMillis - 2_000L),
            WatchNextHistoryInput(3, "Unknown Lookup Show", 1800.0, 300.0, nowMillis - 3_000L),
        )
        val lookup = mapOf(1 to nhkProgram, 2 to tbsProgram)
        val isNhk: (RecordedProgram) -> Boolean = { it.channel?.name?.contains("NHK") == true }

        val activeResult = WatchNextPlanner.plan(
            history = history,
            recordings = listOf(nhkProgram, noChannelRecording),
            lookup = lookup,
            exclusionActive = true,
            isExcluded = isNhk,
            nowMillis = nowMillis,
        )
        assertEquals(listOf(2), activeResult.map { it.recordedProgramId })

        val inactiveResult = WatchNextPlanner.plan(
            history = history,
            recordings = listOf(nhkProgram, noChannelRecording),
            lookup = lookup,
            exclusionActive = false,
            isExcluded = isNhk,
            nowMillis = nowMillis,
        )
        assertEquals(listOf(2, 3, 4), inactiveResult.map { it.recordedProgramId })
    }

    @Test
    fun `thumbnail URLs keep only http and https and description formats JST timestamp`() {
        val program = sampleProgram(
            id = 50,
            startTime = "2026-06-15T21:30:00+09:00",
            endTime = "2026-06-15T22:00:00+09:00",
            channel = sampleChannel(name = "テレビ東京"),
            directThumbnailUrl = "file:///tmp/local.jpg",
            apiThumbnailUrl = "https://example.test/api/videos/50/thumbnail",
        )

        val result = WatchNextPlanner.plan(
            history = emptyList(),
            recordings = listOf(program),
            lookup = mapOf(50 to program),
            exclusionActive = false,
            isExcluded = { false },
            nowMillis = nowMillis,
        )

        assertEquals(1, result.size)
        assertEquals(listOf("https://example.test/api/videos/50/thumbnail"), result.first().thumbnailUrls)
        assertEquals("テレビ東京 · 6/15(月) 21:30", result.first().description)
    }
}
