package com.beeregg2001.komorebi.data.repository

import android.app.Application
import com.beeregg2001.komorebi.data.SettingsRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class AppContentStoreStartupRequestTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private suspend fun store(provider: LiveProvider): AppContentStore {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        val exclusion = NHKExclusionRepository(settings)
        exclusion.state.first { it.isLoaded }
        val unusedRecordProvider = Proxy.newProxyInstance(
            RecordProvider::class.java.classLoader, arrayOf(RecordProvider::class.java),
        ) { _, _, _ -> error("Channel startup must not request recordings") } as RecordProvider
        return AppContentStore(
            provider, unusedRecordProvider, settings, exclusion, scope, dispatcher,
            { scheduler.currentTime },
        )
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    @Test(timeout = 10_000)
    fun refreshChannelsIfStaleDuringInitialFlightMakesNoExtraRequest() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val provider = FakeLiveProvider().apply { this.hold = hold }
        try {
            val store = store(provider)
            assertEquals(1, provider.requests)
            store.refreshChannelsIfStale()
            assertEquals(1, provider.requests)
            hold.complete(Unit)
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it.isNotEmpty() }
            assertEquals(1, provider.requests)
            assertEquals(1, provider.maxConcurrent)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun refreshChannelsIfStaleAfterSuccessInsideIntervalMakesNoExtraRequest() = runBlocking {
        val provider = FakeLiveProvider()
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it.isNotEmpty() }
            assertEquals(1, provider.requests)

            store.refreshChannelsIfStale()
            assertEquals(1, provider.requests)

            advance(30_000)
            store.refreshChannelsIfStale()
            assertEquals(1, provider.requests)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun refreshChannelsIfStaleAfterFailureMakesOneRetryRequest() = runBlocking {
        val provider = FakeLiveProvider().apply { failuresRemaining = 1 }
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            assertEquals(1, provider.requests)
            assertTrue(store.connectionError.value)

            store.refreshChannelsIfStale()
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it.isNotEmpty() }
            assertEquals(2, provider.requests)
            assertFalse(store.connectionError.value)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun refreshChannelsIfStaleAfterIntervalExpiresMakesOneRefreshRequest() = runBlocking {
        val provider = FakeLiveProvider()
        try {
            val store = store(provider)
            store.isChannelsLoading.first { !it }
            store.groupedChannels.first { it.isNotEmpty() }
            assertEquals(1, provider.requests)

            store.setAppAwake(false)
            advance(60_000)
            store.setAppAwake(true)
            assertEquals(1, provider.requests)

            store.refreshChannelsIfStale()
            store.isChannelsLoading.first { !it }
            assertEquals(2, provider.requests)
        } finally {
            scope.cancel()
        }
    }

    private class FakeLiveProvider : LiveProvider {
        var requests = 0
        var maxConcurrent = 0
        private var concurrent = 0
        var failuresRemaining = 0
        var response = response("gr-1")
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun getChannels(): ChannelApiResponse {
            requests++
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            val waiting = hold
            hold = null
            val failure = if (failuresRemaining-- > 0) IOException("network not ready") else null
            try {
                waiting?.await()
                failure?.let { throw it }
                return response
            } finally {
                concurrent--
            }
        }

        override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int) = ""
        override suspend fun getChannelLogoUrl(channelId: String) = ""
    }

    companion object {
        private fun response(id: String) = ChannelApiResponse(
            terrestrial = listOf(
                Channel(
                    id = id,
                    displayChannelId = id,
                    name = "channel-$id",
                    channelNumber = "1",
                    type = "GR",
                    isWatchable = true,
                    isDisplay = true,
                    programPresent = null,
                    programFollowing = null,
                )
            )
        )
    }
}
