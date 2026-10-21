package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.PlayerClockMode
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class PlayerClockSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clockSelectionPersistsAndReturnsFocusWithoutMovingRows() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking { repository.saveString(SettingsRepository.PLAYER_CLOCK_MODE, PlayerClockMode.ALWAYS_ON.name) }
        compose.setContent {
            KomorebiTheme {
                val preferences = rememberSettingPreferences(repository)
                val requesters = remember { List(17) { FocusRequester() } }
                val clockR = remember { FocusRequester() }
                var restore by remember { mutableStateOf<FocusRequester?>(null) }
                var dialog by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(dialog) { if (!dialog) restore?.requestFocus() }
                Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DisplaySettingsContent(
                        isDarkMode = true,
                        themeSeason = "DEFAULT",
                        preferences = preferences,
                        modeR = requesters[0], colorR = requesters[1], timeFormatR = requesters[2],
                        hideSubChannelsR = requesters[3], sidebarR = requesters[4],
                        onMode = {}, onColor = {}, onEditTimeFormat = {}, onToggleHideSubChannels = {},
                        screensaverEnabled = true,
                        screensaverTimeoutMinutes = 5,
                        screensaverPlayerTimeoutMinutes = 10,
                        screensaverPlayerStyle = "SLIDESHOW",
                        playerEndSleepMinutes = 60,
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
                        onCycleScreensaverTimeout = {},
                        onCycleScreensaverPlayerTimeout = {},
                        onCycleScreensaverPlayerStyle = {},
                        onCyclePlayerEndSleep = {},
                        onCycleScreensaverInterval = {}, onCycleScreensaverTransition = {},
                        onPickScreensaverImages = {},
                        onPreviewScreensaver = {},
                        onClick = { restore = it },
                        playerClockModeR = clockR,
                        onEditPlayerClockMode = { dialog = true },
                        screensaverPlayerPreviewR = requesters[14],
                        onPreviewScreensaverPlayer = {},
                        screensaverGalleryR = requesters[15],
                        screensaverUpcomingR = requesters[16],
                    )
                }
                if (dialog) SelectionDialog("プレイヤーの時計",
                    PlayerClockMode.entries.map { it.label to it.name }, preferences.playerClockMode.name,
                    onDismiss = { dialog = false },
                    onSelect = { value -> scope.launch {
                        repository.saveString(SettingsRepository.PLAYER_CLOCK_MODE, value)
                        dialog = false
                    } })
                }
            }
        }
        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val clock = row("プレイヤーの時計")
        clock.requestFocus().assertIsFocused()
        clock.performKeyInput { pressKey(Key.DirectionUp) }
        row("時刻の表示形式").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        clock.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        row("サブチャンネルを非表示にする").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        val bounds = clock.getUnclippedBoundsInRoot()
        for (mode in PlayerClockMode.entries) {
            clock.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.mainClock.advanceTimeBy(300)
            val current = runBlocking { repository.playerClockMode.first() }
            compose.onAllNodes(hasText(current.label) and hasClickAction()).onLast().assertIsFocused()
            compose.onAllNodes(hasText(mode.label) and hasClickAction()).onLast().requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            clock.assertIsFocused().assertTextContains(mode.label)
            assertEquals(mode, runBlocking { repository.playerClockMode.first() })
            assertEquals(bounds, clock.getUnclippedBoundsInRoot())
        }
        clock.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        row("キャンセル").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        clock.assertIsFocused().assertTextContains("Always off")
    }
}
