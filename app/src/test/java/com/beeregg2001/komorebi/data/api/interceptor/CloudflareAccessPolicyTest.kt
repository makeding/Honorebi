package com.beeregg2001.komorebi.data.api.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareAccessPolicyTest {
    private val configuration = CloudflareAccessConfiguration(
        clientId = "client-id",
        clientSecret = "client-secret",
        allowedOrigins = setOf(CloudflareAccessConfiguration.Origin("tv.example.test", 443)),
    )

    @Test fun `adds both headers only for configured HTTPS origin`() {
        assertEquals(
            mapOf(
                CloudflareAccessConfiguration.CLIENT_ID_HEADER to "client-id",
                CloudflareAccessConfiguration.CLIENT_SECRET_HEADER to "client-secret",
            ),
            configuration.headersFor("https://tv.example.test/api/v1/channels"),
        )
    }

    @Test fun `does not leak credentials to redirect destination or near-match origin`() {
        assertTrue(configuration.headersFor("https://images.example.test/logo.png").isEmpty())
        assertTrue(configuration.headersFor("https://tv.example.test.evil.test/redirect").isEmpty())
        assertTrue(configuration.headersFor("https://tv.example.test:8443/redirect").isEmpty())
        assertTrue(configuration.headersFor("http://tv.example.test/api").isEmpty())
    }

    @Test fun `incomplete configuration is disabled`() {
        assertTrue(
            CloudflareAccessConfiguration(
                clientId = "client-id",
                allowedOrigins = configuration.allowedOrigins,
            ).headersFor("https://tv.example.test/api").isEmpty(),
        )
    }

    @Test fun `origin parser rejects HTTP and preserves configured HTTPS port`() {
        assertEquals(
            CloudflareAccessConfiguration.Origin("tv.example.test", 8443),
            CloudflareAccessConfiguration.origin("https://tv.example.test:8443", "7000"),
        )
        assertEquals(null, CloudflareAccessConfiguration.origin("http://tv.example.test", "7000"))
    }
}
