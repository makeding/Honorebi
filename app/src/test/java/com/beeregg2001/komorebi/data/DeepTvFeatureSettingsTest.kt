package com.beeregg2001.komorebi.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DeepTvFeatureSettingsTest {

    @Test
    fun defaultsAreAllEnabledOnEmptyStore() = runBlocking {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        val settings = repository.deepTvFeatureSettings.first()

        assertEquals(DeepTvFeatureSettings(), settings)
        assertTrue(settings.watchNextEnabled)
        assertTrue(settings.watchNextNextEpisodeEnabled)
        assertTrue(settings.previewLiveEnabled)
        assertTrue(settings.previewRecordingsEnabled)
        assertTrue(settings.remoteShortcutKeysEnabled)
        assertTrue(settings.remoteNumberTuningEnabled)
        assertTrue(settings.osPipAutoEnterEnabled)
        assertTrue(settings.nextEpisodeActive)
        DeepTvFeature.entries.forEach { feature ->
            assertTrue("Expected $feature default to be true", settings.isEnabled(feature))
        }
    }

    @Test
    fun toggleDeepTvFeaturePersistsAndFlipsBack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)

        for (feature in DeepTvFeature.entries) {
            repository.toggleDeepTvFeature(feature)
            val afterDisable = repository.deepTvFeatureSettings.first()
            assertFalse("Expected $feature to be false after first toggle", afterDisable.isEnabled(feature))
            DeepTvFeature.entries.filter { it != feature }.forEach { other ->
                assertTrue("Expected unrelated $other to remain true", afterDisable.isEnabled(other))
            }

            // Re-read via a separate SettingsRepository instance sharing the same DataStore.
            val reloaded = SettingsRepository(context).deepTvFeatureSettings.first()
            assertFalse("Expected $feature false to persist across repository instances", reloaded.isEnabled(feature))

            repository.toggleDeepTvFeature(feature)
            val afterReenable = repository.deepTvFeatureSettings.first()
            assertTrue("Expected $feature to flip back to true", afterReenable.isEnabled(feature))
        }
    }

    @Test
    fun nextEpisodeActiveRequiresMasterWatchNextEnabled() = runBlocking {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())

        assertTrue(repository.deepTvFeatureSettings.first().nextEpisodeActive)

        // Turning off master disables effective nextEpisodeActive even when child stays true.
        repository.toggleDeepTvFeature(DeepTvFeature.WATCH_NEXT)
        val masterOff = repository.deepTvFeatureSettings.first()
        assertFalse(masterOff.watchNextEnabled)
        assertTrue(masterOff.watchNextNextEpisodeEnabled)
        assertFalse(masterOff.nextEpisodeActive)

        // Turning master back on restores nextEpisodeActive.
        repository.toggleDeepTvFeature(DeepTvFeature.WATCH_NEXT)
        assertTrue(repository.deepTvFeatureSettings.first().nextEpisodeActive)

        // Turning off child disables nextEpisodeActive while master remains on.
        repository.toggleDeepTvFeature(DeepTvFeature.WATCH_NEXT_NEXT_EPISODE)
        val childOff = repository.deepTvFeatureSettings.first()
        assertTrue(childOff.watchNextEnabled)
        assertFalse(childOff.watchNextNextEpisodeEnabled)
        assertFalse(childOff.nextEpisodeActive)
    }
}
