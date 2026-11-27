package com.beeregg2001.komorebi.ui.main

import android.app.Application
import android.view.KeyEvent
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MainRootRemoteKeyControllerTest {

    @Test
    fun digitInput_appendsRollsOverBuildsPreviewAndCommitsLiveChannel() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val ch1 = channel(id = "gr-1", displayChannelId = "gr011", name = "Channel 1", channelNumber = "011", remoconId = 1)
        val ch4 = channel(id = "gr-4", displayChannelId = "gr041", name = "Channel 4", channelNumber = "041", remoconId = 4)
        val channels = listOf(ch1, ch4)
        val tabs = state.getVisibleTabs()
        val ctx = controller.buildRemoteKeyContext(
            isInOsPip = false,
            showMainContent = true,
            isUpgradeTourActive = false,
            hostModals = RootHostModalState(),
        )
        val saved = mutableListOf<Channel>()

        fun pressDigit(keyCode: Int) {
            assertTrue(
                controller.handleKeyEvent(
                    nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                    remoteShortcutKeysEnabled = true,
                    remoteNumberTuningEnabled = true,
                    remoteKeyContext = ctx,
                    tabs = tabs,
                    tifActive = false,
                    lastChannels = emptyList(),
                    tunableChannels = channels,
                    defaultSystemChannel = ch1,
                    isChannelExcluded = { false },
                    onDismissRootOverlays = {},
                    onEnsureEpgLoaded = {},
                    onSaveLastChannel = { saved.add(it) },
                ),
            )
            assertTrue(
                controller.handleKeyEvent(
                    nativeEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode),
                    remoteShortcutKeysEnabled = true,
                    remoteNumberTuningEnabled = true,
                    remoteKeyContext = ctx,
                    tabs = tabs,
                    tifActive = false,
                    lastChannels = emptyList(),
                    tunableChannels = channels,
                    defaultSystemChannel = ch1,
                    isChannelExcluded = { false },
                    onDismissRootOverlays = {},
                    onEnsureEpgLoaded = {},
                    onSaveLastChannel = { saved.add(it) },
                ),
            )
        }

        pressDigit(KeyEvent.KEYCODE_0)
        assertEquals("0", controller.channelNumberBuffer)
        assertEquals(1, controller.channelNumberInputTick)

        pressDigit(KeyEvent.KEYCODE_1)
        assertEquals("01", controller.channelNumberBuffer)
        assertEquals(2, controller.channelNumberInputTick)

        pressDigit(KeyEvent.KEYCODE_1)
        assertEquals("011", controller.channelNumberBuffer)
        assertEquals(3, controller.channelNumberInputTick)
        assertEquals(
            ChannelNumberOsdState(inputBuffer = "011", matchedChannelName = "Channel 1", notFoundInput = null),
            controller.buildOsdState(channels, currentLiveType = null),
        )

        // 4th digit rolls over buffer to that digit
        pressDigit(KeyEvent.KEYCODE_4)
        assertEquals("4", controller.channelNumberBuffer)
        assertEquals("Channel 4", controller.buildOsdState(channels, currentLiveType = null).matchedChannelName)

        // Commit when idle -> exitMiniPlayer = true (fullscreen live)
        controller.commitChannelNumber(channels, currentLiveType = null) { saved.add(it) }
        assertEquals("", controller.channelNumberBuffer)
        assertEquals(PlaybackTarget.Live(ch4), state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(listOf(ch4), saved)

        // Commit when already in mini live playback -> exitMiniPlayer = false (stays in mini player)
        assertTrue(state.playbackState.enterMiniPlayer())
        pressDigit(KeyEvent.KEYCODE_1)
        controller.commitChannelNumber(channels, currentLiveType = "GR") { saved.add(it) }
        assertEquals(PlaybackTarget.Live(ch1), state.playbackState.playbackTarget)
        assertTrue(state.playbackState.isMiniPlayerMode)
        assertEquals(listOf(ch4, ch1), saved)
    }

    @Test
    fun commitChannelNumber_setsNotFoundAndIncrementsTickWhenUnmatched() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val ch1 = channel(id = "gr-1", channelNumber = "011", remoconId = 1)
        var savedCalls = 0

        controller.channelNumberBuffer = "999"
        controller.commitChannelNumber(listOf(ch1), currentLiveType = "GR") { savedCalls++ }

        assertEquals("", controller.channelNumberBuffer)
        assertEquals("999", controller.channelNumberNotFound)
        assertEquals(1, controller.channelNumberNotFoundTick)
        assertEquals(0, savedCalls)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(
            ChannelNumberOsdState(inputBuffer = "", matchedChannelName = null, notFoundInput = "999"),
            controller.buildOsdState(listOf(ch1), currentLiveType = "GR"),
        )

        controller.clearNotFound()
        assertNull(controller.channelNumberNotFound)
    }

    @Test
    fun osdActive_backCancelsAndEnterCommitsWhileConsumingMatchingKeyUp() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val ch1 = channel(id = "gr-1", channelNumber = "011", remoconId = 1)
        val saved = mutableListOf<Channel>()

        // Back cancels numeric buffer and consumes matching KeyUp
        controller.channelNumberBuffer = "01"
        controller.channelNumberNotFound = "999"
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                cmPanicZapEnabled = false,
                tunableChannels = listOf(ch1),
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertEquals("", controller.channelNumberBuffer)
        assertNull(controller.channelNumberNotFound)
        assertEquals(KeyEvent.KEYCODE_BACK, controller.consumedOsdActionKeyCode)

        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
                cmPanicZapEnabled = false,
                tunableChannels = listOf(ch1),
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertNull(controller.consumedOsdActionKeyCode)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)

        // DPAD_CENTER commits numeric buffer and consumes matching KeyUp
        controller.channelNumberBuffer = "011"
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER),
                cmPanicZapEnabled = false,
                tunableChannels = listOf(ch1),
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertEquals("", controller.channelNumberBuffer)
        assertEquals(PlaybackTarget.Live(ch1), state.playbackState.playbackTarget)
        assertEquals(listOf(ch1), saved)
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, controller.consumedOsdActionKeyCode)

        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER),
                cmPanicZapEnabled = false,
                tunableChannels = listOf(ch1),
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertNull(controller.consumedOsdActionKeyCode)
    }

    @Test
    fun cmPanicZap_yellowKeyEscapesToHottestChannelAndSecondPressReturnsToOriginal() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val ch1 = channel(id = "gr-1", name = "Original", jikkyoForce = 10)
        val ch2 = channel(id = "gr-2", name = "Warm", jikkyoForce = 80)
        val ch3 = channel(id = "gr-3", name = "Hottest", jikkyoForce = 420)
        val channels = listOf(ch1, ch2, ch3)
        val saved = mutableListOf<Channel>()

        state.playbackState.enterLive(ch1)

        // Repeat count > 0 is ignored
        val repeatYellow = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW, 1)
        assertFalse(
            controller.handlePreviewKeyEvent(
                nativeEvent = repeatYellow,
                cmPanicZapEnabled = true,
                tunableChannels = channels,
                onSaveLastChannel = { saved.add(it) },
            ),
        )

        // First press escapes to hottest channel
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW),
                cmPanicZapEnabled = true,
                tunableChannels = channels,
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertEquals(PlaybackTarget.Live(ch3), state.playbackState.playbackTarget)
        assertEquals(ch1, controller.cmPanicReturnChannel)
        assertEquals("CM緊急退避 → Hottest（黄ボタンで復帰）", state.toastMessage)
        assertEquals(listOf(ch3), saved)

        // Matching KeyUp consumed
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW),
                cmPanicZapEnabled = true,
                tunableChannels = channels,
                onSaveLastChannel = { saved.add(it) },
            ),
        )

        // Second press returns to original channel
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW),
                cmPanicZapEnabled = true,
                tunableChannels = channels,
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertEquals(PlaybackTarget.Live(ch1), state.playbackState.playbackTarget)
        assertNull(controller.cmPanicReturnChannel)
        assertEquals("元のチャンネルへ復帰: Original", state.toastMessage)
        assertEquals(listOf(ch3, ch1), saved)

        // No candidate with jikkyoForce > 0
        val noForceChannels = listOf(ch1, ch2.copy(jikkyoForce = 0))
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW),
                cmPanicZapEnabled = true,
                tunableChannels = noForceChannels,
                onSaveLastChannel = { saved.add(it) },
            ),
        )
        assertEquals(PlaybackTarget.Live(ch1), state.playbackState.playbackTarget)
        assertEquals("退避先の実況チャンネルが見つかりません", state.toastMessage)

        // Ending live playback clears cmPanicReturnChannel
        controller.cmPanicReturnChannel = ch2
        controller.onLivePlaybackChanged(null)
        assertNull(controller.cmPanicReturnChannel)
    }

    @Test
    fun remoteShortcuts_guideAndDvrEnterMiniPlayerSwitchTabOrShowUnsupportedBackendToast() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val ch1 = channel(id = "gr-1")
        val konomiTabs = state.getVisibleTabs()
        var dismissCalls = 0
        var ensureEpgCalls = 0

        state.playbackState.enterLive(ch1)
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.GUIDE,
            tabs = konomiTabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = emptyList(),
            tunableChannels = listOf(ch1),
            defaultSystemChannel = ch1,
            isChannelExcluded = { false },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = { ensureEpgCalls++ },
            onSaveLastChannel = {},
        )
        assertEquals(1, dismissCalls)
        assertEquals(1, ensureEpgCalls)
        assertTrue(state.playbackState.isMiniPlayerMode)
        assertEquals(konomiTabs.indexOf("番組表"), state.currentTabIndex)

        state.playbackState.exitMiniPlayer()
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.DVR,
            tabs = konomiTabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = emptyList(),
            tunableChannels = listOf(ch1),
            defaultSystemChannel = ch1,
            isChannelExcluded = { false },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = { ensureEpgCalls++ },
            onSaveLastChannel = {},
        )
        assertEquals(2, dismissCalls)
        assertTrue(state.playbackState.isMiniPlayerMode)
        assertEquals(konomiTabs.indexOf("ビデオ"), state.currentTabIndex)
        assertTrue(state.isRecordListOpen)

        // Tabs without ビデオ -> shows unsupported DVR backend toast
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.DVR,
            tabs = listOf("ホーム", "ライブ", "番組表"),
            hasRootOverlay = false,
            tifActive = true,
            lastChannels = emptyList(),
            tunableChannels = listOf(ch1),
            defaultSystemChannel = ch1,
            isChannelExcluded = { false },
            onDismissRootOverlays = {},
            onEnsureEpgLoaded = {},
            onSaveLastChannel = {},
        )
        assertEquals(
            "録画リストを開けません：現在の接続先は録画リストに対応していません。接続設定でメインシステムを確認してください。",
            state.toastMessage,
        )

        // Tabs without 番組表 -> shows unsupported GUIDE toast
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.GUIDE,
            tabs = listOf("ホーム", "ライブ"),
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = emptyList(),
            tunableChannels = listOf(ch1),
            defaultSystemChannel = ch1,
            isChannelExcluded = { false },
            onDismissRootOverlays = {},
            onEnsureEpgLoaded = {},
            onSaveLastChannel = {},
        )
        assertEquals(
            "番組表を開けません：現在の接続先は番組表に対応していません。接続設定でメインシステムを確認してください。",
            state.toastMessage,
        )
    }

    @Test
    fun remoteShortcut_tvShowsOverlayInFullscreenExitsMiniPlayerOrTunesLastNonExcludedChannel() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        val nhk = channel(id = "gr-nhk", displayChannelId = "gr011", name = "NHK")
        val tifCh = channel(id = "tif:ch1", displayChannelId = "tif001", name = "TIF Ch")
        val ch2 = channel(id = "gr-2", displayChannelId = "gr021", name = "Ch 2")
        val ch2Fresh = ch2.copy(name = "Ch 2 Fresh")
        val tabs = state.getVisibleTabs()
        var dismissCalls = 0
        val saved = mutableListOf<Channel>()

        // Branch 1: Fullscreen live with no overlay/settings -> shows playerShowOverlay
        state.playbackState.enterLive(ch2)
        state.playbackState.playerShowOverlay = false
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.TV,
            tabs = tabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = listOf(ch2),
            tunableChannels = listOf(ch2),
            defaultSystemChannel = ch2,
            isChannelExcluded = { false },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = {},
            onSaveLastChannel = { saved.add(it) },
        )
        assertTrue(state.playbackState.playerShowOverlay)
        assertEquals(0, dismissCalls)

        // Branch 2: Mini player live -> dismisses root overlays and exits mini player
        assertTrue(state.playbackState.enterMiniPlayer())
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.TV,
            tabs = tabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = listOf(ch2),
            tunableChannels = listOf(ch2),
            defaultSystemChannel = ch2,
            isChannelExcluded = { false },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = {},
            onSaveLastChannel = { saved.add(it) },
        )
        assertEquals(1, dismissCalls)
        assertFalse(state.playbackState.isMiniPlayerMode)

        // Branch 3: Idle -> skips excluded channel and skips tif: channel when tifActive = false
        state.playbackState.resetPlayback()
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.TV,
            tabs = tabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = listOf(nhk, tifCh, ch2),
            tunableChannels = listOf(ch2Fresh),
            defaultSystemChannel = nhk,
            isChannelExcluded = { it.id == nhk.id },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = {},
            onSaveLastChannel = { saved.add(it) },
        )
        assertEquals(2, dismissCalls)
        assertEquals(PlaybackTarget.Live(ch2Fresh), state.playbackState.playbackTarget)
        assertEquals(listOf(ch2Fresh), saved)

        // Branch 3b: Idle with no last/default eligible channel -> switches to ライブ tab
        state.playbackState.resetPlayback()
        state.currentTabIndex = 0
        controller.handleRemoteShortcut(
            shortcut = RemoteShortcut.TV,
            tabs = tabs,
            hasRootOverlay = false,
            tifActive = false,
            lastChannels = listOf(nhk, tifCh),
            tunableChannels = emptyList(),
            defaultSystemChannel = nhk,
            isChannelExcluded = { it.id == nhk.id },
            onDismissRootOverlays = { dismissCalls++ },
            onEnsureEpgLoaded = {},
            onSaveLastChannel = { saved.add(it) },
        )
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(tabs.indexOf("ライブ"), state.currentTabIndex)
    }

    @Test
    fun previewKeyEvent_passesCenterKeyThroughToChildrenWhenPlayerUiVisible() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state, aiFeaturesEnabled = true)
        val longPressCenter = KeyEvent(
            0L,
            0L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            1,
            0,
            0,
            0,
            KeyEvent.FLAG_LONG_PRESS,
        )

        // Recorded playback with player controls visible -> pass through
        state.playbackState.enterRecorded(recording())
        state.playbackState.showPlayerControls = true
        assertFalse(
            controller.handlePreviewKeyEvent(
                nativeEvent = longPressCenter,
                cmPanicZapEnabled = false,
                tunableChannels = emptyList(),
                onSaveLastChannel = {},
            ),
        )
        assertFalse(state.isAiConciergeOpen)

        // SMB playback with player controls visible -> pass through
        state.playbackState.enterSmb(smbItem())
        state.playbackState.showPlayerControls = true
        assertFalse(
            controller.handlePreviewKeyEvent(
                nativeEvent = longPressCenter,
                cmPanicZapEnabled = false,
                tunableChannels = emptyList(),
                onSaveLastChannel = {},
            ),
        )
        assertFalse(state.isAiConciergeOpen)

        // Live playback with overlay visible -> pass through
        state.playbackState.enterLive(channel())
        state.playbackState.playerShowOverlay = true
        assertFalse(
            controller.handlePreviewKeyEvent(
                nativeEvent = longPressCenter,
                cmPanicZapEnabled = false,
                tunableChannels = emptyList(),
                onSaveLastChannel = {},
            ),
        )
        assertFalse(state.isAiConciergeOpen)

        // Live playback with overlay hidden -> long-press opens AI concierge when enabled
        state.playbackState.playerShowOverlay = false
        assertTrue(
            controller.handlePreviewKeyEvent(
                nativeEvent = longPressCenter,
                cmPanicZapEnabled = false,
                tunableChannels = emptyList(),
                onSaveLastChannel = {},
            ),
        )
        assertTrue(state.isAiConciergeOpen)
    }

    @Test
    fun onOsPipChanged_clearsNumericBufferNotFoundAndConsumedKeys() {
        val state = MainRootState()
        val controller = MainRootRemoteKeyController(state)
        controller.channelNumberBuffer = "01"
        controller.channelNumberNotFound = "999"
        controller.consumedShortcutKeyCode = KeyEvent.KEYCODE_GUIDE
        controller.consumedOsdActionKeyCode = KeyEvent.KEYCODE_DPAD_CENTER

        controller.onOsPipChanged(false)
        assertEquals("01", controller.channelNumberBuffer)
        assertEquals("999", controller.channelNumberNotFound)
        assertEquals(KeyEvent.KEYCODE_GUIDE, controller.consumedShortcutKeyCode)
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, controller.consumedOsdActionKeyCode)

        controller.onOsPipChanged(true)
        assertEquals("", controller.channelNumberBuffer)
        assertNull(controller.channelNumberNotFound)
        assertNull(controller.consumedShortcutKeyCode)
        assertNull(controller.consumedOsdActionKeyCode)
    }

    private fun channel(
        id: String = "gr-test",
        displayChannelId: String = "gr011",
        name: String = "Test Channel",
        channelNumber: String = "011",
        remoconId: Int? = 1,
        jikkyoForce: Int? = null,
    ) = Channel(
        id = id,
        displayChannelId = displayChannelId,
        name = name,
        channelNumber = channelNumber,
        networkId = 1,
        serviceId = 1,
        type = "GR",
        isWatchable = true,
        isDisplay = true,
        programPresent = null,
        programFollowing = null,
        remocon_Id = remoconId,
        jikkyoForce = jikkyoForce,
    )

    private fun recording(id: Int = 42) = RecordedProgram(
        id = id,
        title = "Test Recording",
        description = "",
        startTime = "2026-08-09T00:00:00+09:00",
        endTime = "2026-08-09T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = id,
            status = "Recorded",
            filePath = "/recordings/test.ts",
            duration = 3600.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC-LC",
        ),
    )

    private fun smbItem() = SmbItem(
        name = "test.mp4",
        path = "smb://server/share/test.mp4",
        isDirectory = false,
        size = 1024L,
        lastModified = 0L,
    )
}
