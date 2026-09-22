package com.beeregg2001.komorebi.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRefreshRunnerTest {
    @Test fun reconnectDuringOldRequestRunsAgainAndCoalescesDuplicates() = runBlocking {
        val runner = ChannelRefreshRunner()
        val oldRequest = CompletableDeferred<Unit>()
        val freshRequest = CompletableDeferred<Unit>()
        var requests = 0
        val flight = launch(start = CoroutineStart.UNDISPATCHED) {
            runner.run {
                requests++
                if (requests == 1) oldRequest.await() else freshRequest.await()
            }
        }
        repeat(3) { runner.run(queueIfRunning = true) { error("Parallel request") } }
        assertEquals(1, requests)
        oldRequest.complete(Unit)
        kotlinx.coroutines.yield()
        assertEquals(2, requests)
        assertTrue(runner.isRunning)
        freshRequest.complete(Unit)
        flight.join()
        assertFalse(runner.isRunning)
    }

    @Test fun ordinaryRefreshDoesNotQueueAndCancellationReleasesFlight() = runBlocking {
        val runner = ChannelRefreshRunner()
        val flight = launch(start = CoroutineStart.UNDISPATCHED) {
            runner.run { CompletableDeferred<Unit>().await() }
        }
        runner.run { error("Duplicate refresh") }
        flight.cancel()
        flight.join()
        assertFalse(runner.isRunning)
        var requests = 0
        runner.run { requests++ }
        assertEquals(1, requests)
    }

    @Test(timeout = 10_000) fun failedOldHttpRequestIsRecheckedAfterReconnect() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{\"terrestrial\":[]}"))
        server.start()
        val client = OkHttpClient()
        val runner = ChannelRefreshRunner()
        val oldResponseReceived = CompletableDeferred<Unit>()
        val finishOldRequest = CompletableDeferred<Unit>()
        var offline = true
        var requests = 0
        try {
            val flight = launch {
                runner.run {
                    requests++
                    offline = withContext(Dispatchers.IO) {
                        client.newCall(Request.Builder().url(server.url("/api/channels")).build())
                            .execute().use { !it.isSuccessful }
                    }
                    if (requests == 1) {
                        oldResponseReceived.complete(Unit)
                        finishOldRequest.await()
                    }
                }
            }
            oldResponseReceived.await()
            assertTrue(offline)
            runner.run(queueIfRunning = true) { error("Parallel request") }
            finishOldRequest.complete(Unit)
            flight.join()
            assertEquals(2, server.requestCount)
            assertEquals("/api/channels", server.takeRequest().path)
            assertEquals("/api/channels", server.takeRequest().path)
            assertFalse(offline)
        } finally {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
