package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    application = android.app.Application::class,
    sdk = [28],
    qualifiers = "w960dp-h540dp-land-mdpi",
)
class ModernVideoSettingsOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun qualitySubmenu_backAndSelectRestoreFocusToMainSettingsList() {
        var selectedQuality = StreamQuality.DEFAULT_QUALITIES.first()
        var closedCount = 0
        composeRule.setContent {
            KomorebiTheme {
                AnimatedVisibility(visible = true) {
                    ModernVideoSettingsOverlay(
                        currentAudioMode = AudioMode.MAIN,
                        currentSpeed = 1.0f,
                        isSubtitleEnabled = true,
                        currentQuality = selectedQuality,
                        isCommentEnabled = false,
                        isLCropEnabled = false,
                        cmSkipMode = CmSkipMode.OFF,
                        availableQualities = StreamQuality.DEFAULT_QUALITIES,
                        onAudioToggle = {},
                        onSpeedToggle = {},
                        onSubtitleToggle = {},
                        onQualitySelect = { selectedQuality = it },
                        onCommentToggle = {},
                        onLCropToggle = {},
                        onCmSkipModeToggle = {},
                        capabilities = PlaybackUiCapabilities.Recorded,
                        onClose = { closedCount++ },
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("音声切替").assertIsFocused()

        // 画質サブメニューを開く
        composeRule.onNodeWithText("画質").requestFocus()
        composeRule.onNodeWithText("画質").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()

        val firstQualityLabel = StreamQuality.DEFAULT_QUALITIES.first().label
        composeRule.onNodeWithText(firstQualityLabel).assertIsFocused()

        // 戻るキーでメイン設定一覧に戻り、フォーカスが復元されること
        composeRule.onNodeWithText(firstQualityLabel).performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("音声切替").assertIsFocused()
        assertEquals(0, closedCount)

        // もう一度戻るキーで閉じること
        composeRule.onNodeWithText("音声切替").performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }
        composeRule.waitForIdle()
        assertEquals(1, closedCount)
    }

    @Test
    fun audioDisabled_focusesNextEnabledRow() {
        val noAudioCapabilities = PlaybackUiCapabilities.Smb.copy(audio = false)
        composeRule.setContent {
            KomorebiTheme {
                AnimatedVisibility(visible = true) {
                    ModernVideoSettingsOverlay(
                        currentAudioMode = AudioMode.MAIN,
                        currentSpeed = 1.0f,
                        isSubtitleEnabled = true,
                        currentQuality = StreamQuality.DEFAULT_QUALITIES.first(),
                        isCommentEnabled = false,
                        isLCropEnabled = false,
                        cmSkipMode = CmSkipMode.OFF,
                        availableQualities = emptyList(),
                        onAudioToggle = {},
                        onSpeedToggle = {},
                        onSubtitleToggle = {},
                        onQualitySelect = {},
                        onCommentToggle = {},
                        onLCropToggle = {},
                        onCmSkipModeToggle = {},
                        capabilities = noAudioCapabilities,
                        onClose = {},
                    )
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("再生速度").assertIsFocused()
    }
}
