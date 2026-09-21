package com.beeregg2001.komorebi.data

import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareAccessConfigurationMappingTest {
    private fun config(ip: String, port: String) = preferencesOf(
        SettingsRepository.KONOMI_IP to ip,
        SettingsRepository.KONOMI_PORT to port,
        SettingsRepository.CF_ACCESS_CLIENT_ID to "id.access",
        SettingsRepository.CF_ACCESS_CLIENT_SECRET to "secret",
    ).toCloudflareAccessConfiguration()

    @Test
    fun `https konomi host attaches headers to api and media urls`() {
        val configuration = config("https://tv.example.test", "7000")
        assertEquals("https://tv.example.test:7000", configuration.backendBaseUrl)
        assertTrue(configuration.headersFor("https://tv.example.test:7000/api/channels").isNotEmpty())
        assertTrue(configuration.headersFor("https://tv.example.test/api/channels").isNotEmpty())
        assertTrue(configuration.headersFor("https://tv.example.test:7000/api/streams/live/x/events").isNotEmpty())
    }

    @Test
    fun `bare konomi host still resolves to https and attaches headers`() {
        val configuration = config("tv.example.test", "7000")
        assertEquals("https://tv.example.test:7000", configuration.backendBaseUrl)
        assertTrue(configuration.headersFor("https://tv.example.test:7000/api/channels").isNotEmpty())
    }
}
