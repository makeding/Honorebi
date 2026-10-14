package com.beeregg2001.komorebi.data.repository

import android.app.Application
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.interceptor.BackendApiException
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ChannelApiResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class AppWakeRecoveryTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private suspend fun store(provider: LiveProvider): AppContentStore {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        exclusion.state.first { it.isLoaded }
        val unusedRecordProvider = Proxy.newProxyInstance(
            RecordProvider::class.java.classLoader, arrayOf(RecordProvider::class.java),
        ) { _, _, _ -> error("Live-only recovery must not request recordings") } as RecordProvider
        return AppContentStore(provider, unusedRecordProvider, settings, exclusion, scope, dispatcher,
            { scheduler.currentTime })
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    @Test(timeout = 10_000) fun lateServerRecoversAutomaticallyAndKeepsCachedChannels() = runBlocking {
        val provider = FakeLiveProvider()
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it.isNotEmpty() }
            store.setAppAwake(false)
            advance(600_000)
            assertEquals(1, provider.requests)
            assertFalse(store.connectionError.value)
            provider.failuresRemaining = 2
            provider.response = response("new")
            store.setAppAwake(true)
            store.refreshAfterRecovery()
            assertEquals(2, provider.requests)
            assertTrue(store.isChannelsLoading.value)
            assertEquals("old", store.groupedChannels.value["GR"]!!.single().id)
            advance(1_000)
            assertEquals(3, provider.requests)
            assertTrue(store.isChannelsLoading.value)
            advance(2_000)
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it["GR"]?.single()?.id == "new" }
            assertEquals(4, provider.requests)
            assertFalse(store.connectionError.value)
            assertNull(store.channelError.value)
            assertEquals(1, provider.maxConcurrent)
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun oldSleepingRequestCannotPublishFailureAndWakeQueuesOneFreshRequest() = runBlocking {
        val provider = FakeLiveProvider().apply {
            hold = CompletableDeferred()
            failuresRemaining = 1
        }
        try {
            val store = store(provider)
            val oldRequest = provider.hold!!
            store.setAppAwake(false)
            advance(120_000)
            store.setAppAwake(true)
            repeat(3) { store.refreshAfterRecovery() }
            assertEquals(1, provider.requests)
            oldRequest.complete(Unit)
            store.isChannelsLoading.first { !it }
            assertEquals(2, provider.requests)
            assertFalse(store.connectionError.value)
            assertNull(store.channelError.value)
            assertEquals(1, provider.maxConcurrent)
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun retryExhaustionIsBoundedAndSleepCancelsAnOldRetryWait() = runBlocking {
        val provider = FakeLiveProvider().apply { failuresRemaining = 100 }
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            store.refreshAfterRecovery()
            for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)) advance(wait)
            assertEquals(7, provider.requests) // initial failure + six recovery attempts
            assertFalse(store.isChannelsLoading.value)
            assertTrue(store.connectionError.value)
            assertEquals("network not ready", store.channelError.value)
            advance(30_000)
            assertEquals(7, provider.requests)
            store.refreshAfterRecovery()
            assertEquals(8, provider.requests)
            store.setAppAwake(false)
            scheduler.runCurrent()
            assertFalse(store.isChannelsLoading.value)
            provider.failuresRemaining = 0
            store.setAppAwake(true)
            store.refreshAfterRecovery()
            store.isChannelsLoading.first { !it }
            assertEquals(9, provider.requests)
            assertFalse(store.connectionError.value)
        } finally { scope.cancel() }
    }

    @Test(timeout = 10_000) fun authenticationFailurePreservesSpecificReasonWithoutAutomaticRetries() = runBlocking {
        val provider = FakeLiveProvider().apply {
            denied = BackendApiException("BACKEND_AUTH_DENIED", 403, "http://server/api/channels", "application/json",
                false, "access denied")
        }
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            store.refreshAfterRecovery()
            assertFalse(store.isChannelsLoading.value)
            advance(30_000)
            assertEquals(2, provider.requests)
            assertTrue(store.connectionError.value)
            assertTrue(store.channelError.value!!.contains("BACKEND_AUTH_DENIED / HTTP 403"))
        } finally { scope.cancel() }
    }

    private class FakeLiveProvider : LiveProvider {
        var requests = 0
        var maxConcurrent = 0
        private var concurrent = 0
        var failuresRemaining = 0
        var response = response("old")
        var hold: CompletableDeferred<Unit>? = null
        var denied: Exception? = null
        override suspend fun getChannels(): ChannelApiResponse {
            requests++
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            val waiting = hold
            hold = null
            val failure = denied ?: if (failuresRemaining-- > 0) IOException("network not ready") else null
            try {
                waiting?.await()
                failure?.let { throw it }
                return response
            } finally { concurrent-- }
        }
        override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int) = ""
        override suspend fun getChannelLogoUrl(channelId: String) = ""
    }

    companion object {
        private fun response(id: String) = ChannelApiResponse(terrestrial = listOf(Channel(
            id, id, "channel-$id", "1", type = "GR", isWatchable = true, isDisplay = true,
            programPresent = null, programFollowing = null,
        )))
    }
}
