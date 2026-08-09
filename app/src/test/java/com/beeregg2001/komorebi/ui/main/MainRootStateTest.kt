package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRootStateTest {

    @Test
    fun playbackTarget_startsNone_andEachEnterOperationReplacesThePreviousKind() {
        val state = MainRootState()
        val channel = channel()
        val recording = recording()
        val smbItem = smbItem()

        assertEquals(PlaybackTarget.None, state.playbackTarget)
        assertFalse(state.isPlaybackActive)

        state.enterLive(channel)
        assertEquals(PlaybackTarget.Live(channel), state.playbackTarget)
        assertTrue(state.isPlaybackActive)
        assertEquals(channel, state.livePlayback?.channel)
        assertNull(state.recordedPlayback)
        assertNull(state.smbPlayback)

        state.enterRecorded(recording)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackTarget)
        assertNull(state.livePlayback)
        assertEquals(recording, state.recordedPlayback?.program)
        assertNull(state.smbPlayback)

        state.enterSmb(smbItem)
        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackTarget)
        assertNull(state.livePlayback)
        assertNull(state.recordedPlayback)
        assertEquals(smbItem, state.smbPlayback?.item)
    }

    @Test
    fun enterRecorded_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val recording = recording()
        state.enterLive(channel())
        state.isMiniPlayerMode = true
        state.showPlayerControls = false

        state.enterRecorded(recording, initialPositionMs = 12_345L)

        assertEquals(PlaybackTarget.Recorded(recording), state.playbackTarget)
        assertNull(state.livePlayback)
        assertFalse(state.isMiniPlayerMode)
        assertTrue(state.showPlayerControls)
        assertEquals(12_345L, state.initialPlaybackPositionMs)
        assertEquals(recording.id.toString(), state.lastSelectedProgramId)
        assertNull(state.lastSelectedChannelId)
    }

    @Test
    fun enterSmb_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val smbItem = smbItem()
        state.enterLive(channel())
        state.isMiniPlayerMode = true
        state.showPlayerControls = false

        state.enterSmb(smbItem, initialPositionMs = 54_321L)

        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackTarget)
        assertNull(state.livePlayback)
        assertFalse(state.isMiniPlayerMode)
        assertTrue(state.showPlayerControls)
        assertEquals(54_321L, state.initialPlaybackPositionMs)
        assertEquals(smbItem.path, state.lastPlayedSmbPath)
    }

    @Test
    fun enterLive_fromRecorded_replacesRecordedAndClearsRecordingSelectionHistory() {
        val state = MainRootState()
        val channel = channel()
        state.enterRecorded(recording(), initialPositionMs = 7_000L)
        state.isMiniPlayerMode = true

        state.enterLive(channel, baseballMode = true)

        assertEquals(PlaybackTarget.Live(channel), state.playbackTarget)
        assertEquals(channel, state.livePlayback?.channel)
        assertNull(state.recordedPlayback)
        assertFalse(state.isMiniPlayerMode)
        assertTrue(state.isBaseballMode)
        assertEquals(channel.id, state.lastSelectedChannelId)
        assertNull(state.lastSelectedProgramId)
    }

    @Test
    fun leavePlayback_clearsTargetAndMarksReturnToLauncher() {
        val state = MainRootState()
        state.enterSmb(smbItem())
        state.isMiniPlayerMode = true
        state.showPlayerControls = false

        state.leavePlayback()

        assertEquals(PlaybackTarget.None, state.playbackTarget)
        assertFalse(state.isPlaybackActive)
        assertFalse(state.isMiniPlayerMode)
        assertTrue(state.showPlayerControls)
        assertTrue(state.isReturningFromPlayer)
    }

    @Test
    fun resetForLauncherHome_clearsTargetAndRestoresPlaybackUiDefaults() {
        val state = MainRootState()
        val previousFocusTick = state.launcherHomeFocusTick
        state.enterLive(channel(), baseballMode = true)
        state.initialPlaybackPositionMs = 9_999L
        state.isMiniPlayerMode = true
        state.isPlayerMiniListOpen = true
        state.playerShowOverlay = true
        state.playerIsManualOverlay = true
        state.playerIsPinnedOverlay = true
        state.playerIsSubMenuOpen = true
        state.isPlayerSubMenuOpen = true
        state.isPlayerSceneSearchOpen = true
        state.showPlayerControls = false
        state.isReturningFromPlayer = true
        state.triggerHomeBack = true

        state.resetForLauncherHome()

        assertEquals(PlaybackTarget.None, state.playbackTarget)
        assertEquals(0L, state.initialPlaybackPositionMs)
        assertFalse(state.isMiniPlayerMode)
        assertFalse(state.isPlayerMiniListOpen)
        assertFalse(state.playerShowOverlay)
        assertFalse(state.playerIsManualOverlay)
        assertFalse(state.playerIsPinnedOverlay)
        assertFalse(state.playerIsSubMenuOpen)
        assertFalse(state.isPlayerSubMenuOpen)
        assertFalse(state.isPlayerSceneSearchOpen)
        assertTrue(state.showPlayerControls)
        assertFalse(state.isReturningFromPlayer)
        assertFalse(state.isBaseballMode)
        assertEquals(previousFocusTick + 1, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    private fun channel() = Channel(
        id = "gr-test",
        displayChannelId = "gr011",
        name = "Test Channel",
        channelNumber = "011",
        networkId = 1,
        serviceId = 1,
        type = "GR",
        isWatchable = true,
        isDisplay = true,
        programPresent = null,
        programFollowing = null,
        remocon_Id = 1,
    )

    private fun recording() = RecordedProgram(
        id = 42,
        title = "Test Recording",
        description = "",
        startTime = "2026-08-09T00:00:00+09:00",
        endTime = "2026-08-09T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = 42,
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
