package com.beeregg2001.komorebi.ui.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveStreamEventConnectionTest {
    @Test
    fun forwardsEventsFromTheCurrentConnection() {
        val factory = FakeFactory()
        val events = mutableListOf<String>()
        val connection = connection(factory, onEvent = events::add)

        connection.start()
        factory.sources.single().event("ONAir")

        assertEquals(listOf("ONAir"), events)
        assertSame(factory.request, connection.request())
    }

    @Test
    fun failureAndCloseFromOneSourceConsumeOnlyOneRetry() {
        val factory = FakeFactory()
        val connection = connection(factory)
        connection.start()
        val first = factory.sources.single()

        first.failure(IOException("lost"))
        first.closed()

        assertEquals(2, factory.sources.size)
        assertTrue(first.cancelled)
    }

    @Test
    fun staleSourceCallbacksAreIgnoredAfterReconnect() {
        val factory = FakeFactory()
        val events = mutableListOf<String>()
        val connection = connection(factory, onEvent = events::add)
        connection.start()
        val first = factory.sources.single()
        first.failure(IOException("lost"))
        val second = factory.sources.last()

        first.event("old")
        second.event("new")

        assertEquals(listOf("new"), events)
    }

    @Test
    fun exhaustsAfterTwoReconnectAttempts() {
        val factory = FakeFactory()
        val exhausted = mutableListOf<Pair<Throwable?, Int?>>()
        val connection = connection(factory, exhausted = exhausted)
        connection.start()

        factory.sources[0].failure(IOException("first"))
        factory.sources[1].closed()
        factory.sources[2].failure(IOException("last"), 503)

        assertEquals(3, factory.sources.size)
        assertEquals(1, exhausted.size)
        assertEquals(503, exhausted.single().second)
    }

    @Test
    fun cancelPreventsRetryAndQueuedOldCallbacks() {
        val factory = FakeFactory()
        val events = mutableListOf<String>()
        val connection = connection(factory, onEvent = events::add)
        connection.start()
        val first = factory.sources.single()

        connection.cancel()
        first.failure(IOException("late"))
        first.event("late")

        assertTrue(first.cancelled)
        assertEquals(1, factory.sources.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun networkDisconnectReconnectsAndForwardsTheNewSseEvent() {
        val server = MockWebServer()
        val events = mutableListOf<String>()
        val received = CountDownLatch(2)
        server.enqueue(sseResponse("first", SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(sseResponse("second", SocketPolicy.KEEP_OPEN))
        server.start()
        val client = OkHttpClient()
        val connection = LiveStreamEventConnection(
            factory = EventSources.createFactory(client),
            request = Request.Builder().url(server.url("/events")).build(),
            scope = CoroutineScope(Dispatchers.Default),
            isCurrent = { true },
            onEvent = { event -> synchronized(events) { events += event }; received.countDown() },
            onExhausted = { _, _ -> },
            retryDelayMs = 10,
        )
        try {
            connection.start()
            assertTrue("first connection and reconnect must deliver events", received.await(5, TimeUnit.SECONDS))
            synchronized(events) { assertEquals(listOf("first", "second"), events) }
            assertEquals("the disconnect must create a second HTTP SSE request", 2, server.requestCount)
        } finally {
            connection.cancel()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun validEventForHealthyWindowResetsTheReconnectBudget() {
        val factory = FakeFactory()
        val reset = CountDownLatch(1)
        val connection = connection(factory, healthyResetMs = 10, reset = reset)
        connection.start()
        factory.sources[0].failure(IOException("first"))
        factory.sources[1].event("valid")
        assertTrue("a received event must keep the connection healthy long enough to reset", reset.await(2, TimeUnit.SECONDS))

        factory.sources[1].failure(IOException("second"))
        factory.sources[2].failure(IOException("third"))
        factory.sources[3].failure(IOException("fourth"))

        assertEquals("a valid 10ms event window grants two fresh reconnects", 4, factory.sources.size)
    }

    @Test
    fun frequentEventsDoNotPostponeHealthyReset() {
        val factory = FakeFactory()
        val reset = CountDownLatch(1)
        val connection = connection(factory, healthyResetMs = 100, reset = reset)
        try {
            connection.start()
            val source = factory.sources.single()
            repeat(30) {
                source.event("valid")
                if (reset.await(10, TimeUnit.MILLISECONDS)) return
            }
            assertEquals("continuous events must not rearm the timer", 0L, reset.count)
        } finally { connection.cancel() }
    }

    @Test
    fun cancellationDuringReconnectDelayPreventsTheNextRequest() {
        val factory = FakeFactory()
        val connection = connection(factory, retryDelayMs = 100)
        connection.start()
        factory.sources.single().failure(IOException("lost"))
        connection.cancel()
        Thread.sleep(200) // The delayed reconnect is the behavior under test.

        assertEquals(1, factory.sources.size)
        assertTrue(factory.sources.single().cancelled)
    }

    private fun connection(
        factory: FakeFactory,
        onEvent: (String) -> Unit = {},
        exhausted: MutableList<Pair<Throwable?, Int?>> = mutableListOf(),
        retryDelayMs: Long = 0,
        healthyResetMs: Long = 10_000,
        reset: CountDownLatch? = null,
    ) = LiveStreamEventConnection(
        factory = factory,
        request = factory.request,
        scope = CoroutineScope(Dispatchers.Unconfined),
        isCurrent = { true },
        onEvent = onEvent,
        onExhausted = { cause, code -> exhausted += cause to code },
        retryDelayMs = retryDelayMs,
        healthyResetMs = healthyResetMs,
        log = { if (it.contains("retry budget reset")) reset?.countDown() },
    )

    private class FakeFactory : EventSource.Factory {
        val request = Request.Builder().url("https://example.invalid/events").build()
        val sources = mutableListOf<FakeEventSource>()

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource =
            FakeEventSource(request, listener).also(sources::add)
    }

    private class FakeEventSource(
        private val request: Request,
        private val listener: EventSourceListener,
    ) : EventSource {
        var cancelled = false

        override fun request(): Request = request
        override fun cancel() { cancelled = true }
        fun event(data: String) = listener.onEvent(this, null, null, data)
        fun closed() = listener.onClosed(this)
        fun failure(error: Throwable, code: Int? = null) = listener.onFailure(this, error, code?.let(::response))
    }

    private companion object {
        fun sseResponse(event: String, socketPolicy: SocketPolicy) = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setChunkedBody("data: $event\n\n", 1)
            .setSocketPolicy(socketPolicy)

        fun response(code: Int) = Response.Builder()
            .request(Request.Builder().url("https://example.invalid/events").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .build()
    }
}
