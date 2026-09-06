package com.beeregg2001.komorebi.data.api.interceptor

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSpec
import com.beeregg2001.komorebi.util.playbackHttpDataSourceFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.Collections
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CloudflareAccessNetworkTest {
    private data class Seen(val path: String, val headers: Map<String, String>) {
        operator fun get(name: String) = headers[name.lowercase()]
    }

    /** Real TLS sockets; only the generated test certificate is trusted. No verifier bypass. */
    private fun withServers(block: (HttpsServer, HttpServer, HandshakeCertificates, MutableList<Seen>) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val secure = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverTls.sslContext())
        }
        val outside = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val seen = Collections.synchronizedList(mutableListOf<Seen>())
        fun handle(exchange: HttpExchange) {
            seen.add(Seen(exchange.requestURI.path, exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString() }))
            val redirect = when (exchange.requestURI.path) {
                "/redirect" -> "http://localhost:${outside.address.port}/next"
                "/next" -> "/final"
                else -> null
            }
            if (redirect != null) {
                exchange.responseHeaders.set("Location", redirect)
                exchange.sendResponseHeaders(302, -1)
            } else {
                val bytes = if (exchange.requestHeaders.getFirst("Range") == "bytes=2-5") "2345".toByteArray() else "0123456789".toByteArray()
                val partial = bytes.size == 4
                if (partial) exchange.responseHeaders.set("Content-Range", "bytes 2-5/10")
                exchange.sendResponseHeaders(if (partial) 206 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        secure.createContext("/", ::handle)
        outside.createContext("/", ::handle)
        secure.start(); outside.start()
        try { block(secure, outside, clientTls, seen) }
        finally { secure.stop(0); outside.stop(0) }
    }

    @Test fun okhttpAndMedia3UseCurrentCredentialsPreserveRangeAndStripRedirectHeaders() = withServers { secure, _, tls, seen ->
        val base = "https://localhost:${secure.address.port}"
        var configuration = CloudflareAccessConfiguration("id", "secret", setOf(CloudflareAccessConfiguration.Origin("localhost", secure.address.port)))
        val access = CloudflareAccessInterceptor { configuration }
        val client = OkHttpClient.Builder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
            .addInterceptor(access).addNetworkInterceptor(access).build()
        try {
            client.newCall(Request.Builder().url("$base/redirect").header("Authorization", "Bearer retained-only-at-origin").build()).execute().use {
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
                source.open(DataSpec.Builder().setUri(Uri.parse("$base/segment")).setPosition(2).setLength(4)
                    .setHttpRequestHeaders(mapOf("X-Playback" to "retained")).build())
                val bytes = ByteArray(4)
                assertEquals(4, source.read(bytes, 0, 4))
                assertEquals("2345", String(bytes))
                assertEquals("bytes=2-5", seen.last()["Range"])
                assertEquals("retained", seen.last()["X-Playback"])
                assertEquals("secret", seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
            } finally { source.close() }
            configuration = configuration.copy(clientSecret = "changed")
            client.newCall(Request.Builder().url("$base/updated").build()).execute().close()
            assertEquals("changed", seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
            configuration = CloudflareAccessConfiguration()
            client.newCall(Request.Builder().url("$base/cleared").build()).execute().close()
            assertNull(seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun urlConnectionRechecksEveryRedirectWithoutReintroducingOriginalCredentials() = withServers { secure, outside, tls, seen ->
        val configuration = CloudflareAccessConfiguration("id", "secret", setOf(CloudflareAccessConfiguration.Origin("localhost", secure.address.port)))
        val headers = mapOf("Authorization" to "Bearer origin", "Cookie" to "session=origin", "Range" to "bytes=2-5",
            CloudflareAccessConfiguration.CLIENT_SECRET_HEADER to "stale", CloudflareAccessConfiguration.CLIENT_ID_HEADER to "stale")
        fun open(url: String) = CloudflareAccessUrlConnection.open(url, { configuration }, headers) {
            connectTimeout = 5000; readTimeout = 5000
            if (this is HttpsURLConnection) sslSocketFactory = tls.sslSocketFactory()
        }
        val connection = open("https://localhost:${secure.address.port}/redirect")
        try { assertEquals(206, connection.responseCode); assertEquals("2345", connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
        assertEquals("secret", seen.first()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
        assertEquals("Bearer origin", seen.first()["Authorization"])
        for (request in seen.drop(1)) {
            assertNull(request[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
            assertNull(request[CloudflareAccessConfiguration.CLIENT_ID_HEADER])
            assertNull(request["Authorization"]); assertNull(request["Cookie"])
            assertEquals("bytes=2-5", request["Range"])
        }
        open("http://localhost:${outside.address.port}/direct").disconnect()
        assertNull(seen.last()[CloudflareAccessConfiguration.CLIENT_SECRET_HEADER])
    }
}
