package com.beeregg2001.komorebi.data.api.interceptor

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackendApiClientHeadersTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate).build()

    private fun clientWith(
        configuration: () -> CloudflareAccessConfiguration,
    ): OkHttpClient {
        val access = CloudflareAccessInterceptor(configuration)
        return OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor(BackendOriginInterceptor(configuration))
            .addInterceptor(access)
            .addNetworkInterceptor(access)
            .build()
    }

    @Test
    fun accessHeadersReachTheRewrittenBackendOrigin() {
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        val backend = server.url("/").toString().removeSuffix("/")
        val configuration = CloudflareAccessConfiguration(
            clientId = "id.access",
            clientSecret = "secret",
            allowedOrigins = setOf(CloudflareAccessConfiguration.Origin("localhost", server.port)),
            backendBaseUrl = backend,
        )
        val client = clientWith(configuration = { configuration })
        try {
            client.newCall(
                Request.Builder()
                    .url("https://192-168-11-100.local.konomi.tv:7000/api/channels").build(),
            ).execute().use { assertEquals(200, it.code) }
            val recorded = server.takeRequest()
            assertEquals("/api/channels", recorded.path)
            assertEquals("id.access", recorded.getHeader(CloudflareAccessConfiguration.CLIENT_ID_HEADER))
            assertEquals("secret", recorded.getHeader(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER))
        } finally {
            shutdown(client, server)
        }
    }

    @Test
    fun httpBackendNeverReceivesAccessHeaders() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()
        val backend = server.url("/").toString().removeSuffix("/")
        val configuration = CloudflareAccessConfiguration(
            clientId = "id.access",
            clientSecret = "secret",
            allowedOrigins = setOf(CloudflareAccessConfiguration.Origin("localhost", server.port)),
            backendBaseUrl = backend,
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(BackendOriginInterceptor { configuration })
            .addInterceptor(CloudflareAccessInterceptor { configuration })
            .build()
        try {
            client.newCall(Request.Builder().url("http://192-168-11-100:7000/api/channels").build())
                .execute().use { assertEquals(200, it.code) }
            val recorded = server.takeRequest()
            assertNull(recorded.getHeader(CloudflareAccessConfiguration.CLIENT_ID_HEADER))
            assertNull(recorded.getHeader(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER))
        } finally {
            shutdown(client, server)
        }
    }

    private fun shutdown(client: OkHttpClient, server: MockWebServer) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.close()
    }
}
