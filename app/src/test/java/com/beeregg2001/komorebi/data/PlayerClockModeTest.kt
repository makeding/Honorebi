package com.beeregg2001.komorebi.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PlayerClockModeTest {
    @Test fun modesPersistAndUnknownValuesUseAlwaysOn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
            .createDeviceProtectedStorageContext()
        val repository = SettingsRepository(context)
        assertEquals(PlayerClockMode.ALWAYS_ON, PlayerClockMode.fromPreference(null))
        for (mode in PlayerClockMode.entries) {
            repository.saveString(SettingsRepository.PLAYER_CLOCK_MODE, mode.name)
            assertEquals(mode, SettingsRepository(context).playerClockMode.first())
        }
        repository.saveString(SettingsRepository.PLAYER_CLOCK_MODE, "unknown")
        assertEquals(PlayerClockMode.ALWAYS_ON, SettingsRepository(context).playerClockMode.first())
    }

    @Test fun onlyNewsRequiresCurrentMajorGenre() {
        for (genres in listOf(null, emptyList(), listOf("スポーツ"))) {
            assertTrue(PlayerClockMode.ALWAYS_ON.showsCurrentTime(genres))
            assertFalse(PlayerClockMode.ONLY_NEWS.showsCurrentTime(genres))
            assertFalse(PlayerClockMode.ALWAYS_OFF.showsCurrentTime(genres))
        }
        assertTrue(PlayerClockMode.ONLY_NEWS.showsCurrentTime(listOf("情報・ワイドショー", "ニュース・報道")))
        assertFalse(PlayerClockMode.ALWAYS_OFF.showsCurrentTime(listOf("ニュース・報道")))
    }
}
