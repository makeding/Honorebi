package com.beeregg2001.komorebi.data.paging

import androidx.paging.PagingSource
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedProgramPagingSourceTest {
    @Test
    fun excludedServerPageStillAdvancesUsingRawPagination() = kotlinx.coroutines.runBlocking {
        val source = RecordedProgramPagingSource(
            recordProvider = FakeRecordProvider(),
            query = "",
            order = "desc",
            isProgramExcluded = { true },
        )

        val result = source.load(PagingSource.LoadParams.Refresh(null, 30, false))

        assertTrue(result is PagingSource.LoadResult.Page)
        result as PagingSource.LoadResult.Page
        assertTrue(result.data.isEmpty())
        assertEquals(2, result.nextKey)
    }

    private class FakeRecordProvider : RecordProvider {
        override suspend fun getRecordedPrograms(
            page: Int,
            order: String,
            channelId: String?,
            genre: String?,
        ) = RecordedApiResponse(60, List(30) { recording(it + 1) })

        override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = Result.failure(UnsupportedOperationException())
        override suspend fun searchRecordedPrograms(keyword: String, page: Int, order: String) = RecordedApiResponse(0, emptyList())
        override suspend fun getRecordStreamUrl(videoId: Int, quality: String, sessionId: String, offsetSeconds: Double, isRecording: Boolean) = ""
        override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> = Result.success(emptyList())
        override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) = Unit
        override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null
        override suspend fun getStreamQualities(): List<StreamQuality> = emptyList()
    }

    private companion object {
        fun recording(id: Int) = RecordedProgram(
            id = id,
            title = "recording-$id",
            description = "",
            startTime = "2026-09-30T00:00:00+09:00",
            endTime = "2026-09-30T00:30:00+09:00",
            duration = 1800.0,
            isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(id, "Recorded", "/recording/$id.ts", null, null, 1800.0, "MPEG-TS", "H.264", "AAC-LC"),
        )
    }
}
