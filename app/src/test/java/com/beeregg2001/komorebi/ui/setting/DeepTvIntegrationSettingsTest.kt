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
import androidx.compose.ui.test.assertIsNotEnabled
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class DeepTvIntegrationSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun defaultEnabledTogglePersistsAndCollapsesDetailRows() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            assertTrue(repository.tifIntegrationEnabled.first())
        }

        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
                val itemRs = remember { List(3) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = true,
                        enabled = prefs.tifIntegrationEnabled,
                        lockedByMainSystem = false,
                        hideEmptyInputs = prefs.tifHideEmptyInputs,
                        noSignalStyle = prefs.tifNoSignalStyle,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleEnabled = {
                            scope.launch {
                                repository.saveBoolean(
                                    SettingsRepository.TIF_INTEGRATION_ENABLED,
                                    !prefs.tifIntegrationEnabled,
                                )
                            }
                        },
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val toggle = row("ディープTV統合を有効にする")
        val hideEmpty = row("チャンネル未登録のTV入力を非表示")
        val noSignal = row("TV入力の信号なし表示")
        val refreshTif = row("TV入力を再読み込み")

        // Expanded focus chain: row0 -> row1 -> row2 -> TV入力を再読み込み
        toggle.requestFocus().assertIsFocused().assertTextContains("ON")
        toggle.performKeyInput { pressKey(Key.DirectionDown) }
        hideEmpty.assertIsFocused()
        hideEmpty.performKeyInput { pressKey(Key.DirectionDown) }
        noSignal.assertIsFocused()
        noSignal.performKeyInput { pressKey(Key.DirectionDown) }
        refreshTif.assertIsFocused()
        refreshTif.performKeyInput { pressKey(Key.DirectionUp) }
        noSignal.assertIsFocused()
        noSignal.performKeyInput { pressKey(Key.DirectionUp) }
        hideEmpty.assertIsFocused()
        hideEmpty.performKeyInput { pressKey(Key.DirectionUp) }
        toggle.assertIsFocused()

        // Toggle OFF -> persists, collapses rows 1-2 & TifInputSettings, keeps focus on row0, down = Cancel
        toggle.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.waitUntil(3000) { runBlocking { !repository.tifIntegrationEnabled.first() } }
        assertFalse(runBlocking { repository.tifIntegrationEnabled.first() })

        toggle.assertIsFocused().assertTextContains("OFF")
        compose.onNode(hasText("チャンネル未登録のTV入力を非表示")).assertDoesNotExist()
        compose.onNode(hasText("TV入力の信号なし表示")).assertDoesNotExist()
        compose.onNode(hasText("TV入力を再読み込み")).assertDoesNotExist()

        toggle.performKeyInput { pressKey(Key.DirectionDown) }
        toggle.assertIsFocused()
    }

    @Test
    fun unsupportedShowsNotSupportedAndRowRemainsFocusable() {
        var clicked = false
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
                        onToggleEnabled = { clicked = true },
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        val toggle = compose.onNode(hasText("ディープTV統合を有効にする") and hasClickAction())
        toggle.requestFocus().assertIsFocused().assertTextContains("非対応")
        compose.onNode(hasText("チャンネル未登録のTV入力を非表示")).assertDoesNotExist()
        toggle.performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(clicked)
    }

    @Test
    fun lockedByMainSystemDisablesToggleAndShowsMainSystemValue() {
        compose.setContent {
            KomorebiTheme {
                val itemRs = remember { List(3) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = true,
                        enabled = true,
                        lockedByMainSystem = true,
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

        val toggle = compose.onNode(hasText("ディープTV統合を有効にする"))
        toggle.assertIsNotEnabled().assertTextContains("ON（メインシステム）")
        compose.onNode(hasText("チャンネル未登録のTV入力を非表示") and hasClickAction())
            .requestFocus()
            .assertIsFocused()
    }

    @Test
    fun noSignalDialogRestoresFocusToRow2() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, TifNoSignalStyle.BANNER.name)
        }

        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
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
                            hideEmptyInputs = prefs.tifHideEmptyInputs,
                            noSignalStyle = prefs.tifNoSignalStyle,
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
                            current = prefs.tifNoSignalStyle.name,
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

        val noSignalRow = compose.onNode(hasText("TV入力の信号なし表示") and hasClickAction())
        noSignalRow.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        compose.onAllNodes(hasText(TifNoSignalStyle.DVD_BOUNCE.label) and hasClickAction())
            .onLast()
            .requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.waitUntil(3000) {
            noSignalRow.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        noSignalRow.assertIsFocused().assertTextContains(TifNoSignalStyle.DVD_BOUNCE.label)
        assertEquals(TifNoSignalStyle.DVD_BOUNCE, runBlocking { repository.tifNoSignalStyle.first() })
    }
}
