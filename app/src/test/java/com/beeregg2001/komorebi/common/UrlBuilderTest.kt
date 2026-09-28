package com.beeregg2001.komorebi.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlBuilderTest {
    @Test
    fun `formatBaseUrl preserves scheme host port and reverse proxy path`() {
        assertEquals(
            "https://tv.example.test:8443/konomi",
            UrlBuilder.formatBaseUrl("HTTPS://tv.example.test:8443/konomi/", "7000", "http"),
        )
    }

    @Test
    fun `formatBaseUrl applies configured port when URL has no explicit port`() {
        assertEquals(
            "https://tv.example.test:7443/konomi",
            UrlBuilder.formatBaseUrl("https://tv.example.test/konomi", "7443", "http"),
        )
    }

    @Test
    fun `extractBareHost removes scheme path and port for EDCB TCP`() {
        assertEquals("192.168.1.20", UrlBuilder.extractBareHost("HTTPS://192.168.1.20:5510/edcb/"))
        assertEquals("2001:db8::1", UrlBuilder.extractBareHost("http://[2001:db8::1]:5510/"))
    }

    @Test
    fun `jikkyo session URL uses configured HTTPS origin and base path`() {
        assertEquals(
            "https://tv.example.test:7443/konomi/api/channels/GR_27/jikkyo",
            UrlBuilder.getKonomiTvJikkyoWatchSessionUrl("https://tv.example.test/konomi", "7443", "GR_27"),
        )
    }
}
