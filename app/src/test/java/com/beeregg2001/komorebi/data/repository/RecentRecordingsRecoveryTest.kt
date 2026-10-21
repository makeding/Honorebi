package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class RecentRecordingsRecoveryTest {
    @Test fun recoveryIsLazyThenRefreshesImmediatelyWithoutMenuEntry() = runTest {
        val (store, provider) = store()
        runCurrent()
        store.refreshAfterRecovery()
        runCurrent()
        assertEquals(0, provider.requests)

        store.refreshRecentRecordings()
        runCurrent()
        assertEquals(listOf(1), store.recentRecordings.value.map { it.id })
        provider.programs = listOf(recording(2), recording(1))
        store.refreshAfterRecovery()
        runCurrent()
        assertEquals(2, provider.requests)
        assertEquals(listOf(2, 1), store.recentRecordings.value.map { it.id })
    }

    @Test fun recoveryDuringOldRequestCoalescesAndRetainsCachedRow() = runTest {
        val (store, provider) = store()
        store.refreshRecentRecordings()
        runCurrent()
        val oldRequest = CompletableDeferred<Unit>()
        provider.hold = oldRequest
        provider.failure = IOException("network unavailable")
        store.refreshRecentRecordings()
        runCurrent()
        repeat(3) { store.refreshAfterRecovery() }
        runCurrent()
        assertEquals(2, provider.requests)
        assertTrue(store.isRecordingsLoading.value)
        assertEquals(listOf(1), store.recentRecordings.value.map { it.id })
        provider.programs = listOf(recording(2))
        provider.failure = null
        oldRequest.complete(Unit)
        runCurrent()
        assertEquals(3, provider.requests)
        assertEquals(listOf(2), store.recentRecordings.value.map { it.id })
        assertFalse(store.isRecordingsLoading.value)
        assertNull(store.recordingError.value)
        assertEquals(1, provider.maxConcurrent)
    }

    @Test fun failedRefreshKeepsCacheAndReconnectBypassesBackoff() = runTest {
        val (store, provider) = store()
        store.refreshRecentRecordings()
        runCurrent()
        provider.failure = IOException("network unavailable")
        store.refreshAfterRecovery()
        runCurrent()
        assertNotNull(store.recordingError.value)
        assertEquals(listOf(1), store.recentRecordings.value.map { it.id })
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, provider.requests)
        provider.failure = null
        provider.programs = emptyList()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(3, provider.requests)
        assertTrue(store.recentRecordings.value.isEmpty())
        assertNull(store.recordingError.value)
    }

    @Test fun slowChannelRequestDoesNotBlockRecordingPolling() = runTest {
        val (store, provider) = store()
        store.refreshRecentRecordings()
        runCurrent()
        provider.programs = listOf(recording(3))
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, provider.requests)
        assertEquals(listOf(3), store.recentRecordings.value.map { it.id })
    }

    @Test fun disconnectDiscardsOldRecordingResultAndRefreshesOnReconnect() = runTest {
        val (store, provider) = store()
        store.refreshRecentRecordings()
        runCurrent()
        val oldRequest = CompletableDeferred<Unit>()
        provider.hold = oldRequest
        provider.programs = listOf(recording(2))
        store.refreshRecentRecordings()
        runCurrent()
        store.setNetworkAvailable(false)
        oldRequest.complete(Unit)
        runCurrent()
        assertEquals(listOf(1), store.recentRecordings.value.map { it.id })
        provider.programs = listOf(recording(3))
        store.setNetworkAvailable(true)
        store.refreshAfterRecovery()
        runCurrent()
        assertEquals(listOf(3), store.recentRecordings.value.map { it.id })
        assertEquals(1, provider.maxConcurrent)
    }

    private fun TestScope.store(): Pair<AppContentStore, FakeRecordProvider> {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        runBlocking { exclusion.state.first { it.isLoaded } }
        val provider = FakeRecordProvider()
        // Keep channels suspended for the entire test: recordings must be independent.
        val live = object : LiveProvider {
            override suspend fun getChannels(): ChannelApiResponse = CompletableDeferred<ChannelApiResponse>().await()
            override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int) = ""
            override suspend fun getChannelLogoUrl(channelId: String) = ""
        }
        return AppContentStore(live, provider, settings, exclusion, backgroundScope,
            StandardTestDispatcher(testScheduler), { testScheduler.currentTime }) to provider
    }

    private class FakeRecordProvider : RecordProvider {
        var requests = 0
        var concurrent = 0
        var maxConcurrent = 0
        var programs = listOf(recording(1))
        var failure: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun getRecordedPrograms(page: Int, order: String, channelId: String?, genre: String?): RecordedApiResponse {
            assertEquals(1, page)
            assertEquals("desc", order)
            requests++
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            val response = programs
            val error = failure
            val waiting = hold
            hold = null
            try {
                waiting?.await()
                error?.let { throw it }
                return RecordedApiResponse(response.size, response)
            } finally { concurrent-- }
        }
        override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = Result.failure(UnsupportedOperationException())
        override suspend fun searchRecordedPrograms(keyword: String, page: Int, order: String) = RecordedApiResponse(0, emptyList())
        override suspend fun getRecordStreamUrl(videoId: Int, quality: String, sessionId: String, offsetSeconds: Double, isRecording: Boolean) = ""
        override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> = Result.success(emptyList())
        override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) = Unit
        override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null
    }

    companion object {
        private fun recording(id: Int) = RecordedProgram(
            id = id, title = "recording-$id", description = "",
            startTime = "2026-10-13T00:00:00+09:00", endTime = "2026-10-13T00:30:00+09:00",
            duration = 1800.0, isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(id, "Recorded", "/recording/$id.ts", null, null, 1800.0, "MPEG-TS", "H.264", "AAC-LC"),
        )
    }
}
