@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.ui.player.recorded.RecordedPlaybackSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class RecordedPlaybackSourceResolverTest {
    @Test fun failureAndRetryPreserveTheRequestedProgramQualityAndPosition() = runBlocking {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        exclusion.state.first { it.isLoaded }
        val provider = SourceProvider()
        val resolver = RecordedPlaybackSourceResolver(provider, settings, exclusion)
        provider.error = HttpException(Response.error<String>(503, "private backend detail".toResponseBody()))
        val failed = resolver.resolve(42, "1080p", "first", 90.0, true)
        assertEquals("", failed.url)
        assertEquals("HTTP_503", failed.failure!!.code)
        assertFalse(failed.failure.message.contains("private"))
        provider.error = null
        val ready = resolver.resolve(42, "1080p", "retry", 90.0, true)
        assertNull(ready.failure)
        assertTrue(ready.usesTranscodingLiveWindow)
        assertEquals(listOf("42:1080p:first:90.0:true", "42:1080p:retry:90.0:true"), provider.requests)
    }

    @Test fun emptyAndNetworkResultsAreFailuresAndCancellationPropagates() = runBlocking {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        exclusion.state.first { it.isLoaded }
        val provider = SourceProvider()
        val resolver = RecordedPlaybackSourceResolver(provider, settings, exclusion)
        provider.url = "  "
        assertEquals("RECORDED_SOURCE", resolver.resolve(7, "1080p", "session").failure!!.code)
        provider.error = IOException("https://secret-token@server/stream")
        val failure = resolver.resolve(7, "1080p", "session").failure!!
        assertEquals("RECORDED_SOURCE_NETWORK", failure.code)
        assertFalse(failure.message.contains("secret"))
        provider.error = CancellationException("source replaced")
        try {
            resolver.resolve(7, "1080p", "session")
            fail("Cancellation must not become a visible error or retry")
        } catch (_: CancellationException) { }
    }

    private class SourceProvider : RecordProvider {
        var error: Exception? = null
        var url = "http://tv/api/xcode/42/playlist"
        val requests = mutableListOf<String>()
        override suspend fun getRecordStreamUrl(videoId: Int, quality: String, sessionId: String, offsetSeconds: Double, isRecording: Boolean): String {
            requests += "$videoId:$quality:$sessionId:$offsetSeconds:$isRecording"
            error?.let { throw it }
            return url
        }
        override suspend fun getRecordedPrograms(page: Int, order: String, channelId: String?, genre: String?) = RecordedApiResponse(0, emptyList())
        override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = Result.failure(UnsupportedOperationException())
        override suspend fun searchRecordedPrograms(keyword: String, page: Int, order: String) = RecordedApiResponse(0, emptyList())
        override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> = Result.success(emptyList())
        override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) = Unit
        override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null
    }
}
