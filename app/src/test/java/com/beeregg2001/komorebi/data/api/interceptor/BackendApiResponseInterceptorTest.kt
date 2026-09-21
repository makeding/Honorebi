package com.beeregg2001.komorebi.data.api.interceptor

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendApiResponseInterceptorTest {
    private fun client(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(BackendApiResponseInterceptor()).build()

    private fun OkHttpClient.shutdown() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }

    private fun withServer(response: MockResponse, block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    @Test
    fun emptySuccessfulBodyIsAccepted() {
        withServer(MockResponse().setResponseCode(200)) { server ->
            val client = client()
            try {
                client.newCall(Request.Builder().url(server.url("/api/videos/1/keep-alive")).build())
                    .execute().use { assertEquals(200, it.code) }
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun htmlBodyOnSuccessIsReportedAsNonJson() {
        val response = MockResponse().setResponseCode(200).setBody("<html>login</html>")
            .setHeader("Content-Type", "text/html; charset=utf-8")
        withServer(response) { server ->
            val client = client()
            try {
                val error = assertThrows(BackendApiException::class.java) {
                    client.newCall(
                        Request.Builder().url("${server.url("/api/channels")}?token=leak").build(),
                    ).execute().close()
                }
                assertEquals("BACKEND_NON_JSON", error.safeCode)
                assertEquals(200, error.status)
                assertFalse(error.automaticallyRetryable)
                assertEquals("text/html; charset=utf-8", error.responseType)
                assertTrue(error.endpoint.endsWith("/api/channels"))
                assertFalse("endpoint must not leak the query string", error.endpoint.contains("token"))
                assertEquals("[BACKEND_NON_JSON / HTTP 200] Access header なし ${error.endpoint}", error.displayText)
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun serverFailureIsMarkedAutomaticallyRetryable() {
        withServer(MockResponse().setResponseCode(503).setBody("down")) { server ->
            val client = client()
            try {
                val error = assertThrows(BackendApiException::class.java) {
                    client.newCall(Request.Builder().url(server.url("/api/channels")).build())
                        .execute().close()
                }
                assertEquals("BACKEND_HTTP_503", error.safeCode)
                assertTrue(error.automaticallyRetryable)
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun authDenialPausesAutomaticRetry() {
        withServer(MockResponse().setResponseCode(403)) { server ->
            val client = client()
            try {
                val error = assertThrows(BackendApiException::class.java) {
                    client.newCall(Request.Builder().url(server.url("/api/channels")).build())
                        .execute().close()
                }
                assertEquals("BACKEND_AUTH_DENIED", error.safeCode)
                assertFalse(error.automaticallyRetryable)
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun accessLoginRedirectIsDetectedBeforeTheFinalBody() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "/cdn-cgi/access/login?redirect_url=%2Fapi%2Fchannels"),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("<html>login</html>")
                .setHeader("Content-Type", "text/html"),
        )
        server.start()
        val client = client()
        try {
            val error = assertThrows(BackendApiException::class.java) {
                client.newCall(Request.Builder().url(server.url("/api/channels")).build())
                    .execute().close()
            }
            assertEquals("ACCESS_LOGIN_REQUIRED", error.safeCode)
            assertFalse(error.automaticallyRetryable)
        } finally {
            client.shutdown()
            server.close()
        }
    }
}
