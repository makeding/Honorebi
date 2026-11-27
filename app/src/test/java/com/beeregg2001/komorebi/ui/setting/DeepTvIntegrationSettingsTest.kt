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
import androidx.compose.ui.test.assertIsEnabled
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
import com.beeregg2001.komorebi.data.BackgroundPlaybackMode
import com.beeregg2001.komorebi.data.DeepTvFeature
import com.beeregg2001.komorebi.data.DeepTvFeatureSettings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    private fun resetSettings() = runBlocking {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        repository.saveBoolean(SettingsRepository.TIF_INTEGRATION_ENABLED, true)
        repository.saveBoolean(SettingsRepository.TIF_HIDE_EMPTY_INPUTS, true)
        repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, TifNoSignalStyle.BANNER.name)
        val current = repository.deepTvFeatureSettings.first()
        DeepTvFeature.entries.forEach { feature ->
            if (!current.isEnabled(feature)) {
                repository.toggleDeepTvFeature(feature)
            }
        }
    }

    @Before
    fun setUp() {
        resetSettings()
    }

    @After
    fun tearDown() {
        resetSettings()
    }

    @Test
    fun defaultEnabledTogglePersistsAndCollapsesDetailRows() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            assertTrue(repository.tifIntegrationEnabled.first())
        }

        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = true,
                        enabled = prefs.tifIntegrationEnabled,
                        lockedByMainSystem = false,
                        hideEmptyInputs = prefs.tifHideEmptyInputs,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = prefs.tifNoSignalStyle,
                        features = prefs.deepTvFeatures,
                        pipSupported = true,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = { feature ->
                            scope.launch { repository.toggleDeepTvFeature(feature) }
                        },
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
        val toggle = row("TV入力（TIF）統合を有効にする")
        val castAccess = row("キャスト・リモート操作の接続許可")
        val showTuners = row("入力ソースにテレビ信号を表示")
        val hideEmpty = row("チャンネル未登録のTV入力を非表示")
        val noSignal = row("TV入力の信号なし表示")
        val channelDebug = row("チャンネル診断")
        val refreshTif = row("TV入力を再読み込み")

        // Expanded focus chain: toggle -> castAccess -> showTuners -> hideEmpty -> noSignal -> チャンネル診断 -> TV入力を再読み込み
        toggle.requestFocus().assertIsFocused().assertTextContains("ON")
        toggle.performKeyInput { pressKey(Key.DirectionDown) }
        castAccess.assertIsFocused()
        castAccess.performKeyInput { pressKey(Key.DirectionDown) }
        showTuners.assertIsFocused()
        showTuners.performKeyInput { pressKey(Key.DirectionDown) }
        hideEmpty.assertIsFocused()
        hideEmpty.performKeyInput { pressKey(Key.DirectionDown) }
        noSignal.assertIsFocused()
        noSignal.performKeyInput { pressKey(Key.DirectionDown) }
        channelDebug.assertIsFocused()
        channelDebug.performKeyInput { pressKey(Key.DirectionDown) }
        refreshTif.assertIsFocused()
        refreshTif.performKeyInput { pressKey(Key.DirectionUp) }
        channelDebug.assertIsFocused()
        channelDebug.performKeyInput { pressKey(Key.DirectionUp) }
        noSignal.assertIsFocused()
        noSignal.performKeyInput { pressKey(Key.DirectionUp) }
        hideEmpty.assertIsFocused()
        hideEmpty.performKeyInput { pressKey(Key.DirectionUp) }
        showTuners.assertIsFocused()
        showTuners.performKeyInput { pressKey(Key.DirectionUp) }
        castAccess.assertIsFocused()
        castAccess.performKeyInput { pressKey(Key.DirectionUp) }
        toggle.assertIsFocused()

        // Toggle OFF -> persists, collapses TIF detail rows, keeps focus on toggle, down moves to castAccess
        toggle.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.waitUntil(3000) { runBlocking { !repository.tifIntegrationEnabled.first() } }
        assertFalse(runBlocking { repository.tifIntegrationEnabled.first() })

        toggle.assertIsFocused().assertTextContains("OFF")
        compose.onNode(hasText("入力ソースにテレビ信号を表示")).assertDoesNotExist()
        compose.onNode(hasText("チャンネル未登録のTV入力を非表示")).assertDoesNotExist()
        compose.onNode(hasText("TV入力の信号なし表示")).assertDoesNotExist()
        compose.onNode(hasText("TV入力を再読み込み")).assertDoesNotExist()

        toggle.performKeyInput { pressKey(Key.DirectionDown) }
        castAccess.assertIsFocused()
        castAccess.performKeyInput { pressKey(Key.DirectionDown) }
        castAccess.assertIsFocused()
    }

    @Test
    fun unsupportedTifStillShowsAndFocusesAllNewFeatureSections() {
        var tifClicked = false
        compose.setContent {
            KomorebiTheme {
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = false,
                        enabled = true,
                        lockedByMainSystem = false,
                        hideEmptyInputs = true,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        features = DeepTvFeatureSettings(),
                        pipSupported = true,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = {},
                        onToggleEnabled = { tifClicked = true },
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val watchNext = row("Watch Next に番組を表示")
        val nextEpisode = row("シリーズ視聴完了時に次のエピソードを提案")
        val previewLive = row("「放送中の番組（ライブ）」チャンネルを公開")
        val previewRec = row("「新着録画」チャンネルを公開")
        val remoteKeys = row("専用キーショートカット（番組表・録画・テレビ）")
        val numberTuning = row("数字キー（0〜9）で直接選局")
        val bgPlayback = row("バックグラウンド再生動作")
        val tifToggle = row("TV入力（TIF）統合を有効にする")

        watchNext.requestFocus().assertIsFocused().assertTextContains("ON")
        watchNext.performKeyInput { pressKey(Key.DirectionDown) }
        nextEpisode.assertIsFocused().assertTextContains("ON")
        nextEpisode.performKeyInput { pressKey(Key.DirectionDown) }
        previewLive.assertIsFocused().assertTextContains("ON")
        previewLive.performKeyInput { pressKey(Key.DirectionDown) }
        previewRec.assertIsFocused().assertTextContains("ON")
        previewRec.performKeyInput { pressKey(Key.DirectionDown) }
        remoteKeys.assertIsFocused().assertTextContains("ON")
        remoteKeys.performKeyInput { pressKey(Key.DirectionDown) }
        numberTuning.assertIsFocused().assertTextContains("ON")
        numberTuning.performKeyInput { pressKey(Key.DirectionDown) }
        bgPlayback.assertIsFocused().assertTextContains(BackgroundPlaybackMode.PIP.label)
        bgPlayback.performKeyInput { pressKey(Key.DirectionDown) }
        tifToggle.assertIsFocused().assertTextContains("非対応")

        compose.onNode(hasText("チャンネル未登録のTV入力を非表示")).assertDoesNotExist()
        tifToggle.performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(tifClicked)
    }

    @Test
    fun watchNextOffDisablesNextEpisodeAndSkipsItInFocusChain() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = false,
                        enabled = true,
                        lockedByMainSystem = false,
                        hideEmptyInputs = true,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        features = prefs.deepTvFeatures,
                        pipSupported = true,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = { feature ->
                            scope.launch { repository.toggleDeepTvFeature(feature) }
                        },
                        onToggleEnabled = {},
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val watchNext = row("Watch Next に番組を表示")
        val previewLive = row("「放送中の番組（ライブ）」チャンネルを公開")

        watchNext.requestFocus().assertIsFocused().assertTextContains("ON")
        watchNext.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(3000) { runBlocking { !repository.deepTvFeatureSettings.first().watchNextEnabled } }
        compose.waitUntil(3000) {
            watchNext.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "OFF" } == true
        }
        assertFalse(runBlocking { repository.deepTvFeatureSettings.first().watchNextEnabled })

        watchNext.assertIsFocused().assertTextContains("OFF")
        compose.onNode(hasText("シリーズ視聴完了時に次のエピソードを提案")).assertIsNotEnabled()

        // DPAD_DOWN from item 0 skips disabled item 1 and lands on item 2
        watchNext.performKeyInput { pressKey(Key.DirectionDown) }
        previewLive.assertIsFocused()
        previewLive.performKeyInput { pressKey(Key.DirectionUp) }
        watchNext.assertIsFocused()
    }

    @Test
    fun pipUnsupportedFallsBackPipLabelToOffAndKeepsBackgroundPlaybackFocusable() {
        var cycleCount = 0
        compose.setContent {
            KomorebiTheme {
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = true,
                        enabled = true,
                        lockedByMainSystem = false,
                        hideEmptyInputs = true,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        features = DeepTvFeatureSettings(),
                        pipSupported = false,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = {},
                        onCycleBackgroundPlaybackMode = { cycleCount++ },
                        onToggleEnabled = {},
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val numberTuning = row("数字キー（0〜9）で直接選局")
        val bgPlayback = row("バックグラウンド再生動作")
        val tifToggle = row("TV入力（TIF）統合を有効にする")

        bgPlayback
            .assertIsEnabled()
            .assertTextContains(BackgroundPlaybackMode.OFF.label)

        numberTuning.requestFocus().assertIsFocused()
        numberTuning.performKeyInput { pressKey(Key.DirectionDown) }
        bgPlayback.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        org.junit.Assert.assertEquals(1, cycleCount)
        bgPlayback.performKeyInput { pressKey(Key.DirectionDown) }
        tifToggle.assertIsFocused()
        tifToggle.performKeyInput { pressKey(Key.DirectionUp) }
        bgPlayback.assertIsFocused()
        bgPlayback.performKeyInput { pressKey(Key.DirectionUp) }
        numberTuning.assertIsFocused()
    }

    @Test
    fun lockedByMainSystemDisablesToggleAndSkipsRow7InFocusChain() {
        compose.setContent {
            KomorebiTheme {
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = true,
                        enabled = true,
                        lockedByMainSystem = true,
                        hideEmptyInputs = true,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        features = DeepTvFeatureSettings(),
                        pipSupported = true,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = {},
                        onToggleEnabled = {},
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val bgPlayback = row("バックグラウンド再生動作")
        val castAccess = row("キャスト・リモート操作の接続許可")
        val showTuners = row("入力ソースにテレビ信号を表示")

        compose.onNode(hasText("TV入力（TIF）統合を有効にする"))
            .assertIsNotEnabled()
            .assertTextContains("ON（メインシステム）")

        bgPlayback.requestFocus().assertIsFocused()
        bgPlayback.performKeyInput { pressKey(Key.DirectionDown) }
        castAccess.assertIsFocused()
        castAccess.performKeyInput { pressKey(Key.DirectionDown) }
        showTuners.assertIsFocused()
        showTuners.performKeyInput { pressKey(Key.DirectionUp) }
        castAccess.assertIsFocused()
        castAccess.performKeyInput { pressKey(Key.DirectionUp) }
        bgPlayback.assertIsFocused()
    }

    @Test
    fun togglingFeaturePersistsThroughSettingsRepository() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    DeepTvIntegrationSettingsContent(
                        supported = false,
                        enabled = false,
                        lockedByMainSystem = false,
                        hideEmptyInputs = true,
                        showTunerInputs = false,
                        onToggleShowTuners = {},
                        noSignalStyle = TifNoSignalStyle.BANNER,
                        features = prefs.deepTvFeatures,
                        pipSupported = true,
                        itemRs = itemRs,
                        sidebarR = sidebarR,
                        onToggleFeature = { feature ->
                            scope.launch { repository.toggleDeepTvFeature(feature) }
                        },
                        onToggleEnabled = {},
                        onToggleHideEmpty = {},
                        onEditNoSignalStyle = {},
                        onRefreshTif = {},
                        onClick = {},
                    )
                }
            }
        }

        val previewLive = compose.onNode(hasText("「放送中の番組（ライブ）」チャンネルを公開") and hasClickAction())
        previewLive.requestFocus().assertIsFocused().assertTextContains("ON")
        previewLive.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(3000) {
            runBlocking { !repository.deepTvFeatureSettings.first().isEnabled(DeepTvFeature.PREVIEW_LIVE) }
        }
        compose.waitUntil(3000) {
            previewLive.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == "OFF" } == true
        }
        previewLive.assertTextContains("OFF")
        assertFalse(runBlocking { repository.deepTvFeatureSettings.first().previewLiveEnabled })
    }

    @Test
    fun noSignalDialogRestoresFocusToRow9() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, TifNoSignalStyle.BANNER.name)
        }

        compose.setContent {
            KomorebiTheme {
                val prefs = rememberSettingPreferences(repository)
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
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
                            showTunerInputs = false,
                            onToggleShowTuners = {},
                            noSignalStyle = prefs.tifNoSignalStyle,
                            features = prefs.deepTvFeatures,
                            pipSupported = true,
                            itemRs = itemRs,
                            sidebarR = sidebarR,
                            onToggleFeature = {},
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
