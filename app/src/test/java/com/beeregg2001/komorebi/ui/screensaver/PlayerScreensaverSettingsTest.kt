package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.setting.DisplaySettingsContent
import com.beeregg2001.komorebi.ui.setting.rememberSettingPreferences
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-land")
class PlayerScreensaverSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repositoryDefaultsAndIndependentPauseSettingsPersist() = runBlocking {
        // Device-protected storage gives this repository test its own real DataStore file.
        val context = ApplicationProvider.getApplicationContext<Application>().createDeviceProtectedStorageContext()
        val repository = SettingsRepository(context)

        assertEquals(5, repository.screensaverTimeoutMinutes.first())
        assertEquals(10, repository.screensaverPlayerTimeoutMinutes.first())
        assertEquals("SLIDESHOW", repository.screensaverPlayerStyle.first())
        assertEquals(60, repository.playerEndSleepMinutes.first())

        repository.saveInt(SettingsRepository.SCREENSAVER_TIMEOUT_MINUTES, 2)
        repository.saveInt(SettingsRepository.SCREENSAVER_PLAYER_TIMEOUT_MINUTES, 15)
        repository.saveString(SettingsRepository.SCREENSAVER_PLAYER_STYLE, "PLAYER")
        repository.saveInt(SettingsRepository.PLAYER_END_SLEEP_MINUTES, 120)

        val reloaded = SettingsRepository(context)
        assertEquals(2, reloaded.screensaverTimeoutMinutes.first())
        assertEquals(15, reloaded.screensaverPlayerTimeoutMinutes.first())
        assertEquals("PLAYER", reloaded.screensaverPlayerStyle.first())
        assertEquals(120, reloaded.playerEndSleepMinutes.first())
    }

    @Test fun displayRowsKeepDpadOrderBoundsAndSeparatePreviews() {
        var normalPreviewClicks = 0
        var pausePreviewClicks = 0

        compose.setContent {
            KomorebiTheme {
                val repository = remember {
                    SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
                }
                val preferences = rememberSettingPreferences(repository)
                val requesters = remember { List(17) { FocusRequester() } }
                var ordinaryTimeout by remember { mutableIntStateOf(5) }
                var pauseTimeout by remember { mutableIntStateOf(10) }
                var pauseStyle by remember { mutableStateOf("SLIDESHOW") }
                var endHold by remember { mutableIntStateOf(60) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DisplaySettingsContent(
                        isDarkMode = true,
                        themeSeason = "DEFAULT",
                        preferences = preferences,
                        modeR = requesters[0], colorR = requesters[1], timeFormatR = requesters[2],
                        hideSubChannelsR = requesters[3], sidebarR = requesters[4],
                        onMode = {}, onColor = {}, onEditTimeFormat = {}, onToggleHideSubChannels = {},
                        screensaverEnabled = true,
                        screensaverTimeoutMinutes = ordinaryTimeout,
                        screensaverPlayerTimeoutMinutes = pauseTimeout,
                        screensaverPlayerStyle = pauseStyle,
                        playerEndSleepMinutes = endHold,
                        screensaverIntervalSeconds = 30,
                        screensaverTransition = "FADE",
                        screensaverImageCount = 1,
                        screensaverEnabledR = requesters[5],
                        screensaverTimeoutR = requesters[6],
                        screensaverPlayerTimeoutR = requesters[7],
                        screensaverPlayerStyleR = requesters[8],
                        playerEndSleepR = requesters[9],
                        screensaverIntervalR = requesters[10],
                        screensaverTransitionR = requesters[11],
                        screensaverImagesR = requesters[12],
                        screensaverPreviewR = requesters[13],
                        onToggleScreensaver = {},
                        onCycleScreensaverTimeout = { ordinaryTimeout = 10 },
                        onCycleScreensaverPlayerTimeout = { pauseTimeout = 15 },
                        onCycleScreensaverPlayerStyle = { pauseStyle = "PLAYER" },
                        onCyclePlayerEndSleep = { endHold = 120 },
                        onCycleScreensaverInterval = {}, onCycleScreensaverTransition = {},
                        onPickScreensaverImages = {},
                        onPreviewScreensaver = { normalPreviewClicks++ },
                        onClick = {},
                        screensaverPlayerPreviewR = requesters[14],
                        onPreviewScreensaverPlayer = { pausePreviewClicks++ },
                        screensaverGalleryR = requesters[15],
                        screensaverUpcomingR = requesters[16],
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val interval = row("画像の切り替え間隔")
        row("通常の待ち時間").requestFocus().assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        row("一時停止中の待ち時間").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        row("一時停止中のスタイル").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        row("再生終了後の画面保持").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        interval.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        row("再生終了後の画面保持").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        row("一時停止中のスタイル").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        row("一時停止中の待ち時間").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        row("通常の待ち時間").assertIsFocused()

        interval.requestFocus()
        val intervalBounds = interval.getUnclippedBoundsInRoot()
        row("通常の待ち時間").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        row("一時停止中の待ち時間").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        row("一時停止中のスタイル").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        row("再生終了後の画面保持").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        row("再生終了後の画面保持").performScrollTo()
        row("再生終了後の画面保持").assert(hasText("120 分"))
        interval.requestFocus()
        assertEquals(intervalBounds, interval.getUnclippedBoundsInRoot())
        compose.onNode(hasText("再生終了後、保持時間を過ぎるとテレビのスリープ設定に従います。")).assertExists()
        row("一時停止中のスタイル").assert(hasText("プレーヤーと時計"))

        row("プレビュー").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, normalPreviewClicks)
        assertEquals(0, pausePreviewClicks)
        row("一時停止中のプレビュー").performScrollTo().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, normalPreviewClicks)
        assertEquals(1, pausePreviewClicks)
    }
}
