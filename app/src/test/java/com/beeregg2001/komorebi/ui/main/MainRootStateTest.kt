package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.ui.player.*

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

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isPlaybackActive)

        state.playbackState.enterLive(channel)
        assertEquals(PlaybackTarget.Live(channel), state.playbackState.playbackTarget)
        assertTrue(state.playbackState.isPlaybackActive)
        assertEquals(channel, state.playbackState.livePlayback?.channel)
        assertNull(state.playbackState.recordedPlayback)
        assertNull(state.playbackState.smbPlayback)

        state.playbackState.enterRecorded(recording)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertEquals(recording, state.playbackState.recordedPlayback?.program)
        assertNull(state.playbackState.smbPlayback)

        state.playbackState.enterSmb(smbItem)
        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertNull(state.playbackState.recordedPlayback)
        assertEquals(smbItem, state.playbackState.smbPlayback?.item)
    }

    @Test
    fun enterRecorded_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val recording = recording()
        state.playbackState.enterLive(channel())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.enterRecorded(recording, initialPositionMs = 12_345L)

        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertEquals(12_345L, state.playbackState.initialPlaybackPositionMs)
        assertEquals(recording.id.toString(), state.playbackState.lastSelectedProgramId)
        assertNull(state.playbackState.lastSelectedChannelId)
    }

    @Test
    fun enterSmb_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val smbItem = smbItem()
        state.playbackState.enterLive(channel())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.enterSmb(smbItem, initialPositionMs = 54_321L)

        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertEquals(54_321L, state.playbackState.initialPlaybackPositionMs)
        assertEquals(smbItem.path, state.playbackState.lastPlayedSmbPath)
    }

    @Test
    fun enterLive_fromRecorded_replacesRecordedAndClearsRecordingSelectionHistory() {
        val state = MainRootState()
        val channel = channel()
        state.playbackState.enterRecorded(recording(), initialPositionMs = 7_000L)
        state.playbackState.isMiniPlayerMode = true

        state.playbackState.enterLive(channel)

        assertEquals(PlaybackTarget.Live(channel), state.playbackState.playbackTarget)
        assertEquals(channel, state.playbackState.livePlayback?.channel)
        assertNull(state.playbackState.recordedPlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(channel.id, state.playbackState.lastSelectedChannelId)
        assertNull(state.playbackState.lastSelectedProgramId)
    }

    @Test
    fun leavePlayback_clearsTargetAndMarksReturnToLauncher() {
        val state = MainRootState()
        state.playbackState.enterSmb(smbItem())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.leavePlayback()

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isPlaybackActive)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertTrue(state.playbackState.isReturningFromPlayer)
    }

    @Test
    fun recordedSwitch_isAvailableThroughTheRootCompatibilityFacade() {
        val state = MainRootState()
        val first = recording()
        val next = recording(id = 43)
        state.playbackState.enterRecorded(first)
        val session = requireNotNull(state.playbackState.playbackSession)

        assertTrue(state.playbackState.beginRecordedSwitch(next, reason = PlaybackSwitchReason.NextEpisode))
        assertEquals(PlaybackTarget.Recorded(first), state.playbackState.playbackTarget)
        assertEquals(PlaybackTarget.Recorded(next), state.playbackState.renderPlaybackTarget)
        assertEquals(PlaybackPhase.Switching::class, state.playbackState.playbackPhase::class)
        assertTrue(state.playbackState.commitRecordedSwitch(state.playbackState.recordedPlaybackToken))

        assertEquals(PlaybackTarget.Recorded(next), state.playbackState.playbackTarget)
        assertEquals(session, state.playbackState.playbackSession)
    }

    @Test
    fun enterMiniPlayer_withoutPlaybackTarget_returnsFalseAndKeepsItClosed() {
        val state = MainRootState()

        assertFalse(state.playbackState.enterMiniPlayer())

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isMiniPlayerMode)
    }

    @Test
    fun enterMiniPlayer_withAnyPlaybackTarget_returnsTrue() {
        val cases = listOf<(MainRootState) -> Unit>(
            { it.playbackState.enterLive(channel()) },
            { it.playbackState.enterRecorded(recording()) },
            { it.playbackState.enterSmb(smbItem()) },
        )

        cases.forEach { enterPlayback ->
            val state = MainRootState()
            enterPlayback(state)

            assertTrue(state.playbackState.enterMiniPlayer())
            assertTrue(state.playbackState.isMiniPlayerMode)
            assertTrue(state.playbackState.isPlaybackActive)
        }
    }

    @Test
    fun exitMiniPlayer_preservesPlaybackTarget() {
        val state = MainRootState()
        val recording = recording()
        state.playbackState.enterRecorded(recording)
        assertTrue(state.playbackState.enterMiniPlayer())

        state.playbackState.exitMiniPlayer()

        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertEquals(recording, state.playbackState.recordedPlayback?.program)
    }

    @Test
    fun enterLive_withExitMiniPlayerFalse_keepsMiniModeWhileSwitchingChannel() {
        val state = MainRootState()
        val nextChannel = channel(id = "gr-next")
        state.playbackState.enterLive(channel())
        assertTrue(state.playbackState.enterMiniPlayer())

        state.playbackState.enterLive(nextChannel, exitMiniPlayer = false)

        assertTrue(state.playbackState.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Live(nextChannel), state.playbackState.playbackTarget)
        assertEquals(nextChannel.id, state.playbackState.lastSelectedChannelId)
    }

    @Test
    fun resetForLauncherHome_clearsTargetAndRestoresPlaybackUiDefaults() {
        val state = MainRootState()
        val previousFocusTick = state.launcherHomeFocusTick
        state.playbackState.enterLive(channel())
        state.playbackState.initialPlaybackPositionMs = 9_999L
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.isPlayerMiniListOpen = true
        state.playbackState.playerShowOverlay = true
        state.playbackState.playerIsManualOverlay = true
        state.playbackState.playerIsPinnedOverlay = true
        state.playbackState.playerIsSubMenuOpen = true
        state.playbackState.isPlayerSubMenuOpen = true
        state.playbackState.isPlayerSceneSearchOpen = true
        state.playbackState.showPlayerControls = false
        state.playbackState.isReturningFromPlayer = true
        state.triggerHomeBack = true

        state.resetForLauncherHome()

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(0L, state.playbackState.initialPlaybackPositionMs)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertFalse(state.playbackState.isPlayerMiniListOpen)
        assertFalse(state.playbackState.playerShowOverlay)
        assertFalse(state.playbackState.playerIsManualOverlay)
        assertFalse(state.playbackState.playerIsPinnedOverlay)
        assertFalse(state.playbackState.playerIsSubMenuOpen)
        assertFalse(state.playbackState.isPlayerSubMenuOpen)
        assertFalse(state.playbackState.isPlayerSceneSearchOpen)
        assertTrue(state.playbackState.showPlayerControls)
        assertFalse(state.playbackState.isReturningFromPlayer)
        assertEquals(previousFocusTick + 1, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    private fun channel(id: String = "gr-test") = Channel(
        id = id,
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
