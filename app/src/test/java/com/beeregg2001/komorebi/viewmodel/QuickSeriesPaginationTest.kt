package com.beeregg2001.komorebi.viewmodel

import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSeriesPaginationTest {
    @Test
    fun loadsEveryUniquePageUntilTheReportedSeriesTotalIsReached() = runTest {
        val first = program(1)
        val second = program(2)
        val third = program(3)
        val fourth = program(4)
        val requests = mutableListOf<Int>()

        val result = fetchAllUniqueRecordedPrograms { page ->
            requests += page
            when (page) {
                1 -> RecordedApiResponse(4, listOf(first, second))
                2 -> RecordedApiResponse(4, listOf(second, third))
                3 -> RecordedApiResponse(4, listOf(third, fourth))
                else -> error("Unexpected page $page")
            }
        }

        assertEquals(listOf(1, 2, 3), requests)
        assertEquals(listOf(1, 2, 3, 4), result.map { it.id })
    }

    @Test
    fun stopsWhenTheProviderRepeatsAPageInsteadOfMakingProgress() = runTest {
        val repeatedPage = listOf(program(1), program(2))
        val requests = mutableListOf<Int>()

        val result = fetchAllUniqueRecordedPrograms { page ->
            requests += page
            RecordedApiResponse(10, repeatedPage)
        }

        assertEquals(listOf(1, 2), requests)
        assertEquals(listOf(1, 2), result.map { it.id })
    }

    private fun program(id: Int) = RecordedProgram(
        id = id,
        title = "Series $id",
        description = "",
        startTime = "2026-08-10T13:00:00+09:00",
        endTime = "2026-08-10T13:30:00+09:00",
        duration = 1_800.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = id,
            status = "Recorded",
            filePath = "/$id.ts",
            duration = 1_800.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC",
        ),
    )
}
