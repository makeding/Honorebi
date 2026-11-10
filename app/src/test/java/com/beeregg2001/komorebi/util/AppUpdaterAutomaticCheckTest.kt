package com.beeregg2001.komorebi.util

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppUpdaterAutomaticCheckTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("update_install", 0)

    @Test fun automaticCheckCanRetryAfterNetworkFailureAndOnlyReportsTheManifest() = runBlocking {
        preferences.edit().clear().commit()
        MockWebServer().use { server ->
            val updater = AppUpdater(context, server.url("/version.json").toString())
            server.enqueue(MockResponse().setResponseCode(503))
            updater.checkForUpdates(receiveBetaUpdates = false)
            val firstFailure = updater.updateState.value as UpdateState.Error
            assertFalse(firstFailure.installationFailure)

            server.enqueue(MockResponse().setBody("""
                {
                  "versionCode": 999,
                  "versionName": "1.1.8-huggy",
                  "releaseNotes": "Security update",
                  "apkUrl": "https://example.test/app-universal-release.apk"
                }
            """.trimIndent()))
            updater.checkForUpdates(receiveBetaUpdates = false)

            assertEquals(
                UpdateState.UpdateAvailable(
                    "1.1.8-huggy",
                    "Security update",
                    "https://example.test/app-universal-release.apk"
                ),
                updater.updateState.value
            )
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun automaticCheckDoesNotEraseAnInstallerFailure() = runBlocking {
        preferences.edit().clear().putString("error", "Install failed [UPDATE_PREPARE]").commit()
        MockWebServer().use { server ->
            val updater = AppUpdater(context, server.url("/version.json").toString())
            val original = updater.updateState.value as UpdateState.Error
            assertTrue(original.installationFailure)

            updater.checkForUpdates(receiveBetaUpdates = false)

            assertEquals(original, updater.updateState.value)
            assertEquals(0, server.requestCount)
        }
    }
}
