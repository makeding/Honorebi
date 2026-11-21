package com.beeregg2001.komorebi.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SettingsRepositoryAccessSnapshotTest {

    @Test
    fun firstSynchronousReadReturnsPersistedCredentialsAndOrigins() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val writer = SettingsRepository(context)
        writer.saveString(SettingsRepository.KONOMI_IP, "https://tv.example.test")
        writer.saveString(SettingsRepository.KONOMI_PORT, "8443")
        writer.saveString(SettingsRepository.EDCB_IP, "https://edcb.example.test")
        writer.saveString(SettingsRepository.EDCB_HTTP_PORT, "5511")
        writer.saveCloudflareAccessCredentials("access-client-id", "access-client-secret")

        val reader = SettingsRepository(context)
        val snapshot = reader.cloudflareAccessConfiguration.value

        assertEquals("access-client-id", snapshot.clientId)
        assertEquals("access-client-secret", snapshot.clientSecret)
        assertEquals(
            setOf(
                CloudflareAccessConfiguration.Origin("tv.example.test", 8443),
                CloudflareAccessConfiguration.Origin("edcb.example.test", 5511),
            ),
            snapshot.allowedOrigins,
        )
        assertEquals("https://tv.example.test:8443", snapshot.backendBaseUrl)
        assertEquals(
            mapOf(
                CloudflareAccessConfiguration.CLIENT_ID_HEADER to "access-client-id",
                CloudflareAccessConfiguration.CLIENT_SECRET_HEADER to "access-client-secret",
            ),
            snapshot.headersFor("https://tv.example.test:8443/api/channels"),
        )

        reader.saveCloudflareAccessCredentials("updated-client-id", "updated-client-secret")
        assertEquals("updated-client-id", reader.cloudflareAccessConfiguration.value.clientId)
        assertEquals("updated-client-secret", reader.cloudflareAccessConfiguration.value.clientSecret)
    }
}
