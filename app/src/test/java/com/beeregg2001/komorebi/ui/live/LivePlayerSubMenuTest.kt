package com.beeregg2001.komorebi.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
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
class LivePlayerSubMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleDisplayMode_defaultsToAudioTile_andExternalFocusRequesterStaysOnAudioTile() {
        val subMenuFocusRequester = FocusRequester()
        composeRule.setContent {
            KomorebiTheme {
                liveSubMenu(
                    isDualDisplayMode = false,
                    focusRequester = subMenuFocusRequester,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("音声切替").assertIsFocused()
        composeRule.onNodeWithText("クイック選局").assertIsNotFocused()

        composeRule.runOnIdle { subMenuFocusRequester.requestFocus() }
        composeRule.onNodeWithText("音声切替").assertIsFocused()

        composeRule.onNodeWithText("音声切替").performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("ミニプレイヤー").assertIsFocused()

        composeRule.onNodeWithText("ミニプレイヤー").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("音声切替").assertIsFocused()

        composeRule.onNodeWithText("音声切替").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("信号情報").assertIsFocused()
    }

    @Test
    fun dualDisplayMode_defaultsToQuickChannelTile_andExternalFocusRequesterTargetsIt() {
        val subMenuFocusRequester = FocusRequester()
        composeRule.setContent {
            KomorebiTheme {
                liveSubMenu(
                    isDualDisplayMode = true,
                    focusRequester = subMenuFocusRequester,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("クイック選局").assertIsFocused()

        composeRule.onNodeWithText("クイック選局").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("二画面").assertIsFocused()

        composeRule.runOnIdle { subMenuFocusRequester.requestFocus() }
        composeRule.onNodeWithText("クイック選局").assertIsFocused()
    }

    @Test
    fun hdrTileRemoval_restoresFocusToAudioTile() {
        val isHdrSupported = mutableStateOf(true)
        composeRule.setContent {
            KomorebiTheme {
                liveSubMenu(
                    isDualDisplayMode = false,
                    isHdrToSdrToneMappingSupported = isHdrSupported.value,
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("HDR 表示").requestFocus()
        composeRule.onNodeWithText("HDR 表示").assertIsFocused()

        composeRule.runOnIdle { isHdrSupported.value = false }
        composeRule.onNodeWithText("HDR 表示").assertDoesNotExist()
        composeRule.onNodeWithText("音声切替").assertIsFocused()
    }

    @Composable
    private fun liveSubMenu(
        isDualDisplayMode: Boolean,
        isHdrToSdrToneMappingSupported: Boolean = false,
        focusRequester: FocusRequester = FocusRequester(),
    ) {
        LiveTopSubMenuUI(
            mainBackendType = "KONOMITV",
            currentStreamSource = StreamSource.KONOMITV,
            isEdcbDirect = false,
            availableSources = listOf(StreamSource.KONOMITV),
            currentAudioMode = AudioMode.MAIN,
            isSubtitleEnabled = true,
            subtitleLanguages = emptyList(),
            currentSubtitleLanguageId = 1,
            currentQuality = StreamQuality("1080p", "1080p"),
            isCommentEnabled = false,
            isLCropEnabled = false,
            isRecording = false,
            canStartChasePlayback = false,
            isChasePlaybackResolving = false,
            isRecordActionSubmitting = false,
            isSignalInfoVisible = false,
            hdrRenderMode = "ORIGINAL",
            isHdrToSdrToneMappingSupported = isHdrToSdrToneMappingSupported,
            isDualDisplayMode = isDualDisplayMode,
            isDataBroadcastingAvailable = false,
            groupedChannels = emptyMap(),
            currentChannelId = "gr011",
            onMiniPlayerSelectionRequested = {},
            onDualDisplayToggle = {},
            onSwapScreens = {},
            onChannelSelect = {},
            onChasePlayback = {},
            onRecordToggle = {},
            onSignalInfoToggle = {},
            onHdrRenderModeToggle = {},
            onDataBroadcastingToggle = {},
            focusRequester = focusRequester,
            logoUrls = emptyMap(),
            shouldCropLogo = false,
            onSourceSelect = { _, _ -> },
            onAudioToggle = {},
            onSubtitleToggle = {},
            onSubtitleLanguageToggle = {},
            onQualitySelect = {},
            onCommentToggle = {},
            onLCropToggle = {},
            onCloseMenu = {},
        )
    }
}
