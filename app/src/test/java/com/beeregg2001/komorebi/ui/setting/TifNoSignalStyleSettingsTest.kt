package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class TifNoSignalStyleSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unknownPreferenceFallsBackToBanner() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, "UNKNOWN_LEGACY_MODE")
            assertEquals(TifNoSignalStyle.BANNER, repository.tifNoSignalStyle.first())
        }
        assertEquals(TifNoSignalStyle.BANNER, TifNoSignalStyle.fromPreference(null))
        assertEquals(TifNoSignalStyle.BANNER, TifNoSignalStyle.fromPreference("INVALID"))
    }

    @Test
    fun rowIsHiddenWhenTifNotSupported() {
        compose.setContent {
            KomorebiTheme {
                val itemRs = remember { List(3) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = false,
                        enabled = true,
                        lockedByMainSystem = false,
                        hideEmptyInputs = true,
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleEnabled = {},
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        compose.onNode(hasText("TV入力の信号なし表示")).assertDoesNotExist()
        compose.onNode(hasText("ディープTV統合を有効にする") and hasClickAction()).assertExists()
    }

    @Test
    fun selectionPersistsRoundTripsAndRestoresFocusToOriginRow() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, TifNoSignalStyle.BANNER.name)
        }

        compose.setContent {
            KomorebiTheme {
                val preferences = rememberSettingPreferences(repository)
                val itemRs = remember { List(3) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                var restore by remember { mutableStateOf<FocusRequester?>(null) }
                var dialog by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(dialog) {
                    if (!dialog) restore?.safeRequestFocusWithRetry()
                }
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        DeepTvIntegrationSettingsContent(
                            supported = true,
                            enabled = true,
                            lockedByMainSystem = false,
                            hideEmptyInputs = preferences.tifHideEmptyInputs,
                            noSignalStyle = preferences.tifNoSignalStyle,
                            itemRs = itemRs,
                            sidebarR = sidebarR,
                            onToggleEnabled = {},
                            onToggleHideEmpty = {},
                            onEditNoSignalStyle = { dialog = true },
                            onRefreshTif = {},
                            onClick = { restore = it },
                        )
                    }
                    if (dialog) {
                        SelectionDialog(
                            title = "TV入力の信号なし表示",
                            options = TifNoSignalStyle.entries.map { it.label to it.name },
                            current = preferences.tifNoSignalStyle.name,
                            onDismiss = { dialog = false },
                            onSelect = { value ->
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.TIF_NO_SIGNAL_STYLE,
                                        TifNoSignalStyle.fromPreference(value).name,
                                    )
                                    dialog = false
                                }
                            },
                        )
                    }
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val noSignalRow = row("TV入力の信号なし表示")
        val hideEmptyRow = row("チャンネル未登録のTV入力を非表示")

        hideEmptyRow.requestFocus().assertIsFocused()
        hideEmptyRow.performKeyInput { pressKey(Key.DirectionDown) }
        noSignalRow.assertIsFocused()
        noSignalRow.performKeyInput { pressKey(Key.DirectionUp) }
        hideEmptyRow.assertIsFocused()
        hideEmptyRow.performKeyInput { pressKey(Key.DirectionDown) }
        noSignalRow.assertIsFocused()

        for (style in TifNoSignalStyle.entries) {
            noSignalRow.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.mainClock.advanceTimeBy(300)
            compose.onAllNodes(hasText(style.label) and hasClickAction())
                .onLast()
                .requestFocus()
                .performKeyInput { pressKey(Key.DirectionCenter) }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.waitUntil(3000) {
                noSignalRow.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
            }
            noSignalRow.assertIsFocused().assertTextContains(style.label)
            assertEquals(style, runBlocking { repository.tifNoSignalStyle.first() })
        }

        // Cancel also restores focus to origin row
        noSignalRow.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        row("キャンセル").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.waitUntil(3000) {
            noSignalRow.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        noSignalRow.assertIsFocused().assertTextContains(TifNoSignalStyle.SCREENSAVER.label)
    }
}
