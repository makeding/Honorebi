package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.player.PlaybackMediaInfo
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.ui.player.PlayerProgramPanel
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
    // Match the other Robolectric suites; mixed native runtimes collide on the font ZIP filesystem.
    sdk = [28],
    qualifiers = "w960dp-h540dp-land-mdpi"
)
class RecordedPlayerLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun recordedChannelCard_keepsTimeBelowLiveStyleChannel_whenChannelIsMissing() {
        val program = mutableStateOf(testProgram())
        composeRule.setContent {
            KomorebiTheme {
                RecordedProgramStatus(program.value, "24H", RecordedProgramPresentation())
            }
        }
        composeRule.onNodeWithText("地デジ011").assertIsDisplayed()
        val card = composeRule.onNodeWithTag("recorded-channel-status").getUnclippedBoundsInRoot()
        val time = composeRule.onNodeWithTag("recorded-status-time").getUnclippedBoundsInRoot()
        assertEquals(androidx.compose.ui.unit.Dp(48f), (card.bottom - card.top))
        assertEquals(card.bottom, time.top)
        composeRule.onNodeWithText(formatBroadcastTime(program.value.startTime, program.value.endTime, "24H")!!)
            .assertIsDisplayed()
        composeRule.runOnIdle { program.value = program.value.copy(channel = null) }
        assertEquals(time, composeRule.onNodeWithTag("recorded-status-time").getUnclippedBoundsInRoot())
        val missingCard = composeRule.onNodeWithTag("recorded-channel-status").getUnclippedBoundsInRoot()
        assertEquals(card.bottom - card.top, missingCard.bottom - missingCard.top)
    }

    @Test
    fun quickMenu_defaultsAndReturnsToProgramInfo_andOpensItFromTheTile() {
        val visible = mutableStateOf(true)
        var opened = 0
        composeRule.setContent {
            KomorebiTheme {
                menu(
                    isVisible = visible.value,
                    onProgramInfo = { opened += 1 },
                    onCloseMenu = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("番組情報").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.runOnIdle { assertEquals(1, opened) }

        composeRule.runOnIdle { visible.value = false }
        composeRule.runOnIdle { visible.value = true }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("番組情報").assertIsFocused()
    }

    @Test
    fun smbMenu_keepsCommonTilesInTheRecordedGeometry_andDisablesProviderOnlyActions() {
        composeRule.setContent {
            KomorebiTheme {
                menu(
                    mediaInfo = PlaybackMediaInfo(
                        stableId = "smb:/media/test.ts",
                        title = "local.ts",
                    ),
                    currentProgram = null,
                    capabilities = PlaybackUiCapabilities.Smb,
                )
            }
        }

        val titles = listOf("クイック選局", "サムネイル", "音声切替", "番組情報", "画質", "再生速度", "字幕", "L字クロップ", "CMスキップ", "実況コメント", "HDR 表示", "データ放送")
        // The row remains scrollable at TV widths: composition and enabled state must be
        // stable even for tiles initially outside the viewport.
        titles.forEach { title -> composeRule.onNodeWithText(title).assertExists() }
        val bounds = listOf("クイック選局", "サムネイル", "音声切替", "番組情報", "画質")
            .map { title -> composeRule.onNodeWithText(title).assertIsDisplayed().getUnclippedBoundsInRoot() }
        assertEquals(bounds.first().bottom - bounds.first().top, bounds[3].bottom - bounds[3].top)
        assertEquals(bounds.first().bottom - bounds.first().top, bounds.last().bottom - bounds.last().top)
        composeRule.onNodeWithText("クイック選局").assertIsNotEnabled()
        composeRule.onNodeWithText("サムネイル").assertIsNotEnabled()
        composeRule.onNodeWithText("画質").assertIsNotEnabled()
        composeRule.onNodeWithText("CMスキップ").assertIsNotEnabled()
        composeRule.onNodeWithText("実況コメント").assertIsNotEnabled()
    }

    @Test
    fun quickMenu_keepsTheRequiredTileOrder_forHorizontalRemoteNavigation() {
        composeRule.setContent {
            KomorebiTheme { menu() }
        }

        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("番組情報").assertIsFocused()
        composeRule.onNodeWithText("番組情報").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("画質").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("再生速度").assertIsFocused()
        composeRule.onNodeWithText("再生速度").performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("画質").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("番組情報").assertIsFocused()
        composeRule.onNodeWithText("番組情報").performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("音声切替").assertIsFocused()
        composeRule.onNodeWithText("音声切替").performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("サムネイル").assertIsFocused()
        composeRule.onNodeWithText("サムネイル").performKeyInput {
            keyDown(Key.DirectionLeft)
            keyUp(Key.DirectionLeft)
        }
        composeRule.onNodeWithText("クイック選局").assertIsFocused()

        composeRule.onNodeWithText("クイック選局").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("サムネイル").assertIsFocused()
        composeRule.onNodeWithText("サムネイル").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("音声切替").assertIsFocused()
        composeRule.onNodeWithText("音声切替").performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        composeRule.onNodeWithText("番組情報").assertIsFocused()
    }

    @Test
    fun dedicatedQuickEntry_focusesAQuickRecordingAndSelectsItWithConfirm() {
        val selected = mutableStateOf<RecordedProgram?>(null)
        val quickRecording = testProgram(title = "専用入口番組")
        composeRule.setContent {
            KomorebiTheme {
                menu(
                    quickPrograms = listOf(quickRecording),
                    openQuickVideosInitially = true,
                    onVideoSelect = { selected.value = it }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithText("専用入口番組").assertIsFocused().performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        composeRule.runOnIdle { assertEquals(quickRecording.id, selected.value?.id) }
    }

    @Test
    fun programPanel_keepsItsPanelAndFeedbackBoundsForMissingAndLongContent() {
        val missing = mutableStateOf(false)
        composeRule.setContent {
            KomorebiTheme {
                PlayerProgramPanel(
                    channelName = "NHK総合",
                    logoUrl = "",
                    shouldCropLogo = false,
                    title = if (missing.value) "番組情報なし" else "長い番組タイトル".repeat(16),
                    description = if (missing.value) null else "本文".repeat(600),
                    detail = if (missing.value) null else mapOf("出演者" to "出演者情報".repeat(200)),
                    metadata = listOf("放送日時" to "2026/08/10(月) 23:55 - 2026/08/11(火) 00:25"),
                    scrollState = rememberScrollState()
                )
            }
        }

        composeRule.onNodeWithTag("program-panel").assertIsDisplayed()
        val readyPanelBounds = composeRule.onNodeWithTag("program-panel").getUnclippedBoundsInRoot()
        val readyFeedbackBounds = composeRule.onNodeWithTag("program-feedback").getUnclippedBoundsInRoot()

        composeRule.runOnIdle { missing.value = true }

        assertEquals(readyPanelBounds, composeRule.onNodeWithTag("program-panel").getUnclippedBoundsInRoot())
        assertEquals(readyFeedbackBounds, composeRule.onNodeWithTag("program-feedback").getUnclippedBoundsInRoot())
        composeRule.onNodeWithText("番組の紹介は保存されていません。").assertIsDisplayed()
        composeRule.onNodeWithText("詳細な番組情報は保存されていません。").assertIsDisplayed()
    }

    @Test
    fun programDetailLoadingErrorAndRetry_keepBodyAndFeedbackGeometryStable() {
        val request = mutableStateOf(ProgramDetailRequest(programId = 1, loading = true))
        var retries = 0
        composeRule.setContent {
            KomorebiTheme {
                ProgramInfoOverlay(
                    program = testProgram(),
                    timeFormat = "24H",
                    presentation = RecordedProgramPresentation(
                        request = request.value,
                        retry = { retries += 1 }
                    ),
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("番組情報を読み込んでいます…").assertIsDisplayed()
        composeRule.onNodeWithText("再読込").assertDoesNotExist()
        val loadingBodyBounds = composeRule.onNodeWithTag("program-body").getUnclippedBoundsInRoot()
        val loadingFeedbackBounds = composeRule.onNodeWithTag("program-feedback").getUnclippedBoundsInRoot()

        composeRule.runOnIdle {
            request.value = ProgramDetailRequest(programId = 1, error = "NETWORK")
        }

        assertEquals(loadingBodyBounds, composeRule.onNodeWithTag("program-body").getUnclippedBoundsInRoot())
        assertEquals(loadingFeedbackBounds, composeRule.onNodeWithTag("program-feedback").getUnclippedBoundsInRoot())
        composeRule.onNodeWithText("番組情報の取得に失敗しました。NETWORK").assertIsDisplayed()
        composeRule.onNodeWithText("再試行").assertIsDisplayed().requestFocus().assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        composeRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun controlsShowCompactTitleAndKeepTimesAndTrackBoundsAcrossPlayingPausedAndSeekingPreview_inBothStyles() {
        val modern = mutableStateOf(true)
        val playing = mutableStateOf(true)
        val seekingPreview = mutableStateOf(false)
        val title = mutableStateOf("番組")
        composeRule.setContent {
            KomorebiTheme {
                controls(
                    modern = modern.value,
                    playing = playing.value,
                    seekingPreview = seekingPreview.value,
                    title = title.value,
                )
            }
        }

        val modernPlaying = controlBounds()
        composeRule.onNodeWithText("番組").assertIsDisplayed()
        val titleBounds = composeRule.onNodeWithTag("recorded-title").getUnclippedBoundsInRoot()
        assertEquals(androidx.compose.ui.unit.Dp(24f), titleBounds.bottom - titleBounds.top)
        composeRule.runOnIdle { title.value = "長い番組タイトル".repeat(30) }
        composeRule.onNodeWithTag("recorded-title").assertIsDisplayed()
        assertEquals(modernPlaying, controlBounds())
        composeRule.runOnIdle {
            playing.value = false
            seekingPreview.value = true
        }
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(modernPlaying, controlBounds())

        composeRule.runOnIdle {
            modern.value = false
            playing.value = true
            seekingPreview.value = false
        }
        composeRule.mainClock.advanceTimeBy(500)
        val legacyPlaying = controlBounds()
        composeRule.runOnIdle { title.value = "" }
        composeRule.onNodeWithText("タイトルなし").assertIsDisplayed()
        assertEquals(legacyPlaying, controlBounds())
        composeRule.runOnIdle {
            playing.value = false
            seekingPreview.value = true
        }
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(legacyPlaying, controlBounds())
    }

    @Test
    fun controlsProgress_updatesOnlyTheControlsAndDoesNotPollWhileHidden() {
        val visible = mutableStateOf(false)
        var hostCompositions = 0
        var positionReads = 0
        var bufferedReads = 0
        var sourcePositionMs = 120_000L
        composeRule.setContent {
            hostCompositions++
            KomorebiTheme {
                controls(
                    modern = false,
                    playing = true,
                    seekingPreview = false,
                    isVisible = visible.value,
                    positionProvider = { positionReads++; sourcePositionMs },
                    bufferedProvider = { bufferedReads++; 180_000L },
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(750)
        assertEquals(0, positionReads)
        assertEquals(0, bufferedReads)

        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        val compositionsAfterShowing = hostCompositions
        sourcePositionMs = 122_000L
        composeRule.mainClock.advanceTimeBy(750)

        assertEquals(compositionsAfterShowing, hostCompositions)
        composeRule.onNodeWithText("02:02").assertIsDisplayed()

        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        val positionReadsAfterHide = positionReads
        val bufferedReadsAfterHide = bufferedReads
        val hiddenBounds = controlBounds()
        composeRule.mainClock.advanceTimeBy(750)

        assertEquals(positionReadsAfterHide, positionReads)
        assertEquals(bufferedReadsAfterHide, bufferedReads)
        assertEquals(hiddenBounds, controlBounds())
    }

    @androidx.compose.runtime.Composable
    private fun menu(
        isVisible: Boolean = true,
        onProgramInfo: () -> Unit = {},
        onCloseMenu: () -> Unit = {},
        quickPrograms: List<RecordedProgram> = emptyList(),
        openQuickVideosInitially: Boolean = false,
        onVideoSelect: (RecordedProgram) -> Unit = {},
        mediaInfo: PlaybackMediaInfo = PlaybackMediaInfo.recorded(testProgram()),
        currentProgram: RecordedProgram? = testProgram(),
        capabilities: PlaybackUiCapabilities = PlaybackUiCapabilities.Recorded,
    ) {
        VideoTopSubMenuUI(
            mediaInfo = mediaInfo,
            currentProgram = currentProgram,
            seriesPrograms = emptyList(),
            quickPrograms = quickPrograms,
            backendType = "KonomiTV",
            konomiIp = "127.0.0.1",
            konomiPort = "7000",
            currentAudioMode = AudioMode.MAIN,
            currentSpeed = 1f,
            isSubtitleEnabled = true,
            subtitleLanguages = emptyList(),
            currentSubtitleLanguageId = 1,
            currentQuality = StreamQuality("1080p", "1080p"),
            isCommentEnabled = false,
            isLCropEnabled = false,
            cmSkipMode = CmSkipMode.OFF,
            hdrRenderMode = "None",
            isHdrRenderModeSupported = false,
            isDataBroadcastingAvailable = false,
            isDataBroadcastingActive = false,
            availableQualities = listOf(StreamQuality("1080p", "1080p")),
            focusRequester = FocusRequester(),
            onAudioToggle = {},
            onSpeedToggle = {},
            onProgramInfo = onProgramInfo,
            onSubtitleToggle = {},
            onSubtitleLanguageToggle = {},
            onQualitySelect = {},
            onCommentToggle = {},
            onLCropToggle = {},
            onCmSkipModeToggle = {},
            onHdrRenderModeToggle = {},
            onDataBroadcastingToggle = {},
            onVideoSelect = onVideoSelect,
            openQuickVideosInitially = openQuickVideosInitially,
            isVisible = isVisible,
            onCloseMenu = onCloseMenu,
            capabilities = capabilities,
        )
    }

    @androidx.compose.runtime.Composable
    private fun controls(
        modern: Boolean,
        playing: Boolean,
        seekingPreview: Boolean,
        isVisible: Boolean = true,
        positionProvider: () -> Long = { 120_000L },
        bufferedProvider: () -> Long = { 180_000L },
        title: String = "番組",
    ) {
        PlayerControls(
            mediaInfo = PlaybackMediaInfo.recorded(testProgram(title)),
            timeFormat = "24H",
            allComments = emptyList(),
            tiledThumbnailUrl = null,
            isVisible = isVisible,
            isSeekingPreviewVisible = seekingPreview,
            isModernUi = modern,
            isPlaying = playing,
            hasChapters = false,
            initialPositionMs = 120_000L,
            totalDurationMs = 1_800_000L,
            initialBufferedPositionMs = 180_000L,
            displayPositionMsProvider = positionProvider,
            displayBufferedPositionMsProvider = bufferedProvider,
            controlsFocusRequester = remember { FocusRequester() },
            onSeekBarFocusChanged = {},
            onPlayPauseToggle = {},
            onSeekBack = {},
            onSeekForward = {},
            onSeekRequested = {},
            onChapterListToggle = {},
            onInfoToggle = {},
            onSettingsToggle = {}
        )
    }

    private fun controlBounds() = listOf(
        composeRule.onNodeWithTag("recorded-title").getUnclippedBoundsInRoot(),
        composeRule.onNodeWithTag("recorded-controls").getUnclippedBoundsInRoot(),
        composeRule.onNodeWithTag("playback-time").getUnclippedBoundsInRoot(),
        composeRule.onNodeWithTag("playback-duration").getUnclippedBoundsInRoot(),
        composeRule.onNodeWithTag("playback-track").getUnclippedBoundsInRoot()
    )

    private fun testProgram(title: String = "番組") = RecordedProgram(
        id = 1,
        title = title,
        description = "説明",
        startTime = "2026-08-10T13:05:00+09:00",
        endTime = "2026-08-10T13:35:00+09:00",
        duration = 1_800.0,
        isPartiallyRecorded = false,
        channel = RecordedChannel("1", displayChannelId = "GR011", type = "GR", name = "NHK総合", channelNumber = "011"),
        recordedVideo = RecordedVideo(
            id = 1,
            status = "Recorded",
            filePath = "/recorded.ts",
            duration = 1_800.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC"
        )
    )
}
