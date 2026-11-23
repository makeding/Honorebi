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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class TifNoSignalStyleSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun grantManifestReceiverPermission() {
        val context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context).grantPermissions(
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
    }

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
        compose.onNode(hasText("TV入力（TIF）統合を有効にする") and hasClickAction()).assertExists()
    }

    @Test
    fun previewOverlayNavigatesWrapsConfirmsAndCancelsWithFocusRestore() {
        val repository = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            repository.saveString(SettingsRepository.TIF_NO_SIGNAL_STYLE, TifNoSignalStyle.BANNER.name)
        }
        compose.mainClock.autoAdvance = false

        compose.setContent {
            KomorebiTheme {
                val preferences = rememberSettingPreferences(repository)
                val itemRs = remember { List(DeepTvItemIndex.COUNT) { FocusRequester() } }
                val sidebarR = remember { FocusRequester() }
                var restore by remember { mutableStateOf<FocusRequester?>(null) }
                var previewOpen by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(previewOpen) {
                    if (!previewOpen) restore?.safeRequestFocusWithRetry()
                }
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        DeepTvIntegrationSettingsContent(
                            supported = true,
                            enabled = true,
                            lockedByMainSystem = false,
                            hideEmptyInputs = preferences.tifHideEmptyInputs,
                            showTunerInputs = false,
                            onToggleShowTuners = {},
                            noSignalStyle = preferences.tifNoSignalStyle,
                            itemRs = itemRs,
                            sidebarR = sidebarR,
                            onToggleEnabled = {},
                            onToggleHideEmpty = {},
                            onEditNoSignalStyle = { previewOpen = true },
                            onRefreshTif = {},
                            onClick = { restore = it },
                        )
                    }
                    if (previewOpen) {
                        TifNoSignalStylePreviewOverlay(
                            currentStyle = preferences.tifNoSignalStyle,
                            screensaverImageUris = emptyList(),
                            screensaverIntervalSeconds = 5,
                            screensaverTransition = "FADE",
                            onDismiss = { previewOpen = false },
                            onSelect = { selected ->
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.TIF_NO_SIGNAL_STYLE,
                                        selected.name,
                                    )
                                    previewOpen = false
                                }
                            },
                        )
                    }
                }
            }
        }

        fun advanceAndSettle(ms: Long = 300L) {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(ms)
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
        }

        fun row(title: String) = compose.onNode(hasText(title) and hasClickAction())
        val noSignalRow = row("TV入力の信号なし表示")
        val hideEmptyRow = row("チャンネル未登録のTV入力を非表示")
        val overlay = compose.onNodeWithTag(TIF_NO_SIGNAL_PREVIEW_OVERLAY_TAG)
        val previewLabel = compose.onNodeWithTag(TIF_NO_SIGNAL_PREVIEW_LABEL_TAG, useUnmergedTree = true)
        val previewCounter = compose.onNodeWithTag(TIF_NO_SIGNAL_PREVIEW_COUNTER_TAG, useUnmergedTree = true)
        val appliedBadge = compose.onNodeWithTag(TIF_NO_SIGNAL_PREVIEW_APPLIED_BADGE_TAG, useUnmergedTree = true)

        advanceAndSettle()

        // Focus navigation to origin row
        hideEmptyRow.requestFocus().assertIsFocused()
        hideEmptyRow.performKeyInput { pressKey(Key.DirectionDown) }
        advanceAndSettle(100)
        noSignalRow.assertIsFocused()
        noSignalRow.performKeyInput { pressKey(Key.DirectionUp) }
        advanceAndSettle(100)
        hideEmptyRow.assertIsFocused()
        hideEmptyRow.performKeyInput { pressKey(Key.DirectionDown) }
        advanceAndSettle(100)
        noSignalRow.assertIsFocused()

        // 1. Open preview from "TV入力の信号なし表示" row
        val styles = TifNoSignalStyle.entries
        noSignalRow.performKeyInput { pressKey(Key.DirectionCenter) }
        advanceAndSettle()
        overlay.assertExists().assertIsFocused()
        previewLabel.assertTextEquals(styles.first().label)
        previewCounter.assertTextEquals("1 / ${styles.size}")
        appliedBadge.assertExists()

        // 2. Wrap-around Left from first -> last
        overlay.performKeyInput { pressKey(Key.DirectionLeft) }
        advanceAndSettle()
        previewLabel.assertTextEquals(styles.last().label)
        previewCounter.assertTextEquals("${styles.size} / ${styles.size}")
        appliedBadge.assertDoesNotExist()

        // 3. Wrap-around Right from last -> first
        overlay.performKeyInput { pressKey(Key.DirectionRight) }
        advanceAndSettle()
        previewLabel.assertTextEquals(styles.first().label)
        previewCounter.assertTextEquals("1 / ${styles.size}")
        appliedBadge.assertExists()

        // 4. Navigate Right to ARIB_COLOR_BARS (index 4) and confirm with DirectionCenter
        val targetStyle = TifNoSignalStyle.ARIB_COLOR_BARS
        val targetIndex = styles.indexOf(targetStyle)
        repeat(targetIndex) {
            overlay.performKeyInput { pressKey(Key.DirectionRight) }
            advanceAndSettle()
        }
        previewLabel.assertTextEquals(targetStyle.label)
        previewCounter.assertTextEquals("${targetIndex + 1} / ${styles.size}")

        overlay.performKeyInput { pressKey(Key.DirectionCenter) }
        advanceAndSettle()
        compose.waitUntil(3000) {
            compose.mainClock.advanceTimeBy(50)
            noSignalRow.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        overlay.assertDoesNotExist()
        noSignalRow.assertIsFocused().assertTextContains(targetStyle.label)
        assertEquals(targetStyle, runBlocking { repository.tifNoSignalStyle.first() })

        // 5. Re-open, navigate Right (to ANALOG_STATIC), then cancel with Key.Back -> discards change & restores focus
        noSignalRow.performKeyInput { pressKey(Key.DirectionCenter) }
        advanceAndSettle()
        overlay.assertExists().assertIsFocused()
        previewLabel.assertTextEquals(targetStyle.label)
        appliedBadge.assertExists()

        overlay.performKeyInput { pressKey(Key.DirectionRight) }
        advanceAndSettle()
        previewLabel.assertTextEquals(styles[targetIndex + 1].label)
        appliedBadge.assertDoesNotExist()

        overlay.performKeyInput { pressKey(Key.Back) }
        advanceAndSettle()
        compose.waitUntil(3000) {
            compose.mainClock.advanceTimeBy(50)
            noSignalRow.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
        }
        overlay.assertDoesNotExist()
        noSignalRow.assertIsFocused().assertTextContains(targetStyle.label)
        assertEquals(targetStyle, runBlocking { repository.tifNoSignalStyle.first() })
    }
}
