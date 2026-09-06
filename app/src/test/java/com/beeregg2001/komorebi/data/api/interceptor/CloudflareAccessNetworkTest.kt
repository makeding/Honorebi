package com.beeregg2001.komorebi.data.api.interceptor

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.util.playbackHttpDataSourceFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CloudflareAccessNetworkTest {
    private data class Seen(val path: String, val headers: Map<String, String>) {
        operator fun get(name: String) = headers[name.lowercase()]
    }

    /** Real TLS sockets; only the generated localhost certificate is trusted. */
    private fun withServers(
        block: (MockWebServer, MockWebServer, HandshakeCertificates, MutableList<Seen>) -> Unit,
    ) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val secure = MockWebServer()
        val outside = MockWebServer()
        val seen = Collections.synchronizedList(mutableListOf<Seen>())

        fun record(request: RecordedRequest) {
            seen += Seen(
                path = request.path?.substringBefore('?').orEmpty(),
                headers = request.headers.names().associate { name ->
                    name.lowercase() to request.headers.values(name).joinToString()
                },
            )
        }
        fun response(request: RecordedRequest, redirect: String? = null): MockResponse {
            record(request)
            if (redirect != null) {
                return MockResponse().setResponseCode(302).setHeader("Location", redirect)
            }
            val partial = request.headers["Range"] == "bytes=2-5"
            val responseBody = if (partial) "2345" else "0123456789"
            return MockResponse().setResponseCode(if (partial) 206 else 200).apply {
                if (partial) setHeader("Content-Range", "bytes 2-5/10")
                setBody(responseBody)
            }
        }

        secure.useHttps(serverTls.sslSocketFactory(), false)
        secure.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(
                request,
                redirect = if (request.path?.substringBefore('?') == "/redirect") {
                    outside.url("/next").toString()
                } else null,
            )
        }
        outside.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response(
                request,
                redirect = if (request.path?.substringBefore('?') == "/next") "/final" else null,
            )
        }
        secure.start()
        outside.start()
        try {
            block(secure, outside, clientTls, seen)
        } finally {
            secure.close()
            outside.close()
        }
    }

    @Test
    fun okhttpAndMedia3UseCurrentCredentialsPreserveRangeAndStripRedirectHeaders() =
        withServers { secure, _, tls, seen ->
            val base = secure.url("/").toString().removeSuffix("/")
            var configuration = CloudflareAccessConfiguration(
                "id", "secret", setOf(CloudflareAccessConfiguration.Origin("localhost", secure.port)),
            )
            val access = CloudflareAccessInterceptor { configuration }
            val client = OkHttpClient.Builder()
                .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
                .addInterceptor(access)
                .addNetworkInterceptor(access)
                .build()
            try {
                client.newCall(
                    Request.Builder().url("$base/redirect")
                        .header("Authorization", "Bearer retained-only-at-origin").build(),
                ).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("0123456789", it.body.string())
                }
                assertEquals("secret", seen[0][CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
                for (request in seen.drop(1)) {
                    assertNull(request[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
                    assertNull(request["Authorization"])
                }

                val source = playbackHttpDataSourceFactory(client).createDataSource()
                try {
                    source.open(
                        DataSpec.Builder().setUri(Uri.parse("$base/segment")).setPosition(2).setLength(4)
                            .setHttpRequestHeaders(mapOf("X-Playback" to "retained")).build(),
                    )
                    val bytes = ByteArray(4)
                    assertEquals(4, source.read(bytes, 0, 4))
                    assertEquals("2345", String(bytes))
                    assertEquals("bytes=2-5", seen.last()["Range"])
                    assertEquals("retained", seen.last()["X-Playback"])
                    assertEquals("secret", seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
                } finally {
                    source.close()
                }
                configuration = configuration.copy(clientSecret = "changed")
                client.newCall(Request.Builder().url("$base/updated").build()).execute().close()
                assertEquals("changed", seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
                configuration = CloudflareAccessConfiguration()
                client.newCall(Request.Builder().url("$base/cleared").build()).execute().close()
                assertNull(seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    @Test
    fun urlConnectionRechecksEveryRedirectWithoutReintroducingOriginalCredentials() =
        withServers { secure, outside, tls, seen ->
            val configuration = CloudflareAccessConfiguration(
                "id", "secret", setOf(CloudflareAccessConfiguration.Origin("localhost", secure.port)),
            )
            val headers = mapOf(
                "Authorization" to "Bearer origin",
                "Cookie" to "session=origin",
                "Range" to "bytes=2-5",
                CloudflareAccessConfiguration.CLIENT_SECRET_HEADER to "stale",
                CloudflareAccessConfiguration.CLIENT_ID_HEADER to "stale",
            )
            fun open(url: String) = CloudflareAccessUrlConnection.open(url, { configuration }, headers) {
                connectTimeout = 5_000
                readTimeout = 5_000
                if (this is HttpsURLConnection) sslSocketFactory = tls.sslSocketFactory()
            }
            val connection = open(secure.url("/redirect").toString())
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("2345", connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
            assertEquals("secret", seen.first()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
            assertEquals("Bearer origin", seen.first()["Authorization"])
            for (request in seen.drop(1)) {
                assertNull(request[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
                assertNull(request[CloudflareAccessConfiguration.CLIENT_ID_HEADER])
                assertNull(request["Authorization"])
                assertNull(request["Cookie"])
                assertEquals("bytes=2-5", request["Range"])
            }
            val direct = open(outside.url("/direct").toString())
            try {
                assertEquals(206, direct.responseCode)
            } finally {
                direct.disconnect()
            }
            assertNull(seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
        }
}
