package com.beeregg2001.komorebi.data.jikkyo

import okhttp3.Request
import org.json.JSONArray
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class JikkyoClientTest {
    @Test
    fun sessionsReuseThePurposeScopedClient() {
        val sharedClient = JikkyoNetworkResources.client

        assertSame(sharedClient, JikkyoNetworkResources.client)
        assertEquals(0, sharedClient.readTimeoutMillis)

        JikkyoClient("wss://jikkyo.example.test/unused").stop()
        assertFalse(sharedClient.dispatcher.executorService.isShutdown)
    }

    @Test
    fun stoppingOneSessionCancelsOnlyItsOwnedSocket() {
        val factory = FakeWebSocketFactory()
        val first = JikkyoClient("wss://jikkyo.example.test/first", factory)
        val second = JikkyoClient("wss://jikkyo.example.test/second", factory)

        first.start {}
        second.start {}
        factory.awaitCount(2)

        val firstSocket = factory.entries.first { it.socket.request().url.encodedPath == "/first" }.socket
        val secondSocket = factory.entries.first { it.socket.request().url.encodedPath == "/second" }.socket
        first.stop()

        firstSocket.awaitCancelled()
        assertTrue(firstSocket.cancelled)
        assertFalse(secondSocket.cancelled)

        second.stop()
        secondSocket.awaitCancelled()
    }

    @Test
    fun stoppedSessionRejectsLateCommentsAndCannotReconnect() {
        val factory = FakeWebSocketFactory()
        val received = mutableListOf<String>()
        val client = JikkyoClient("wss://jikkyo.example.test/watch", factory)
        client.start(received::add)
        factory.awaitCount(1)

        val watch = factory.entries.single()
        watch.listener.onMessage(
            watch.socket,
            """{"type":"room","data":{"threadId":"thread","yourPostKey":"key","messageServer":{"uri":"wss://jikkyo.example.test/comments"}}}""",
        )
        factory.awaitCount(2)
        val comments = factory.entries.last()
        comments.listener.onMessage(comments.socket, "before-stop")

        client.stop()
        comments.listener.onMessage(comments.socket, "after-stop")
        watch.listener.onMessage(
            watch.socket,
            """{"type":"room","data":{"threadId":"late","yourPostKey":"late","messageServer":{"uri":"wss://jikkyo.example.test/late"}}}""",
        )

        assertEquals(listOf("before-stop"), received)
        assertEquals(2, factory.entries.size)
        assertTrue(watch.socket.cancelled)
        assertTrue(comments.socket.cancelled)
    }

    @Test
    fun chaseOpensLiveAndBoundedHistoryThenPagesAtRfBoundary() {
        val factory = FakeWebSocketFactory()
        val received = mutableListOf<String>()
        val client = JikkyoClient(
            "wss://jikkyo.example.test/watch",
            factory,
            reconnectDelayMillis = 0,
            delayForRetry = {},
        )
        client.startChase(100.0) { received += it }
        factory.awaitCount(1)

        val watch = factory.entries.single()
        watch.listener.onMessage(
            watch.socket,
            """{"type":"room","data":{"threadId":"thread","yourPostKey":"key","messageServer":{"uri":"wss://jikkyo.example.test/comments"}}}""",
        )
        factory.awaitCount(3)
        val live = factory.entries[1]
        val history = factory.entries[2]
        live.listener.onOpen(live.socket, response())
        history.listener.onOpen(history.socket, response())

        assertEquals(-20, JSONArray(live.socket.sent.single()).getJSONObject(2).getJSONObject("thread").getInt("res_from"))
        assertEquals(-500, JSONArray(history.socket.sent.single()).getJSONObject(2).getJSONObject("thread").getInt("res_from"))
        history.listener.onMessage(history.socket, """{"chat":{"date":150,"date_usec":1,"content":"history"}}""")
        history.listener.onMessage(history.socket, """{"ping":{"content":"rf:0"}}""")

        assertEquals(listOf("""{"chat":{"date":150,"date_usec":1,"content":"history"}}"""), received)
        client.stop()
    }

    private fun response(): okhttp3.Response = okhttp3.Response.Builder()
        .request(Request.Builder().url("wss://jikkyo.example.test/response").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private class FakeWebSocketFactory : WebSocket.Factory {
        data class Entry(val socket: FakeWebSocket, val listener: WebSocketListener)

        private val monitor = Object()
        val entries = mutableListOf<Entry>()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request).also { socket ->
                synchronized(monitor) {
                    entries += Entry(socket, listener)
                    monitor.notifyAll()
                }
            }

        fun awaitCount(expected: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            synchronized(monitor) {
                while (entries.size < expected) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Timed out waiting for $expected sockets; got ${entries.size}" }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                }
            }
        }
    }

    private class FakeWebSocket(private val ownedRequest: Request) : WebSocket {
        private val monitor = Object()
        val sent = mutableListOf<String>()

        @Volatile
        var cancelled = false
            private set

        override fun request(): Request = ownedRequest
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (cancelled) return false
            sent += text
            return true
        }
        override fun send(bytes: ByteString): Boolean = !cancelled
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            synchronized(monitor) {
                cancelled = true
                monitor.notifyAll()
            }
        }

        fun awaitCancelled() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            synchronized(monitor) {
                while (!cancelled) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Timed out waiting for socket cancellation" }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                }
            }
        }
    }
}
