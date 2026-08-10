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

        state.enterLive(channel)

        assertEquals(PlaybackTarget.Live(channel), state.playbackTarget)
        assertEquals(channel, state.livePlayback?.channel)
        assertNull(state.recordedPlayback)
        assertFalse(state.isMiniPlayerMode)
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
    fun recordedSwitch_isAvailableThroughTheRootCompatibilityFacade() {
        val state = MainRootState()
        val first = recording()
        val next = recording(id = 43)
        state.enterRecorded(first)
        val session = requireNotNull(state.playbackSession)

        assertTrue(state.beginRecordedSwitch(next, reason = PlaybackSwitchReason.NextEpisode))
        assertEquals(PlaybackTarget.Recorded(first), state.playbackTarget)
        assertEquals(PlaybackTarget.Recorded(next), state.renderPlaybackTarget)
        assertEquals(PlaybackPhase.Switching::class, state.playbackPhase::class)
        assertTrue(state.commitRecordedSwitch(requireNotNull(state.recordedSwitchToken)))

        assertEquals(PlaybackTarget.Recorded(next), state.playbackTarget)
        assertEquals(session, state.playbackSession)
    }

    @Test
    fun enterMiniPlayer_withoutPlaybackTarget_returnsFalseAndKeepsItClosed() {
        val state = MainRootState()

        assertFalse(state.enterMiniPlayer())

        assertEquals(PlaybackTarget.None, state.playbackTarget)
        assertFalse(state.isMiniPlayerMode)
    }

    @Test
    fun enterMiniPlayer_withAnyPlaybackTarget_returnsTrue() {
        val cases = listOf<(MainRootState) -> Unit>(
            { it.enterLive(channel()) },
            { it.enterRecorded(recording()) },
            { it.enterSmb(smbItem()) },
        )

        cases.forEach { enterPlayback ->
            val state = MainRootState()
            enterPlayback(state)

            assertTrue(state.enterMiniPlayer())
            assertTrue(state.isMiniPlayerMode)
            assertTrue(state.isPlaybackActive)
        }
    }

    @Test
    fun exitMiniPlayer_preservesPlaybackTarget() {
        val state = MainRootState()
        val recording = recording()
        state.enterRecorded(recording)
        assertTrue(state.enterMiniPlayer())

        state.exitMiniPlayer()

        assertFalse(state.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackTarget)
        assertEquals(recording, state.recordedPlayback?.program)
    }

    @Test
    fun enterLive_withExitMiniPlayerFalse_keepsMiniModeWhileSwitchingChannel() {
        val state = MainRootState()
        val nextChannel = channel(id = "gr-next")
        state.enterLive(channel())
        assertTrue(state.enterMiniPlayer())

        state.enterLive(nextChannel, exitMiniPlayer = false)

        assertTrue(state.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Live(nextChannel), state.playbackTarget)
        assertEquals(nextChannel.id, state.lastSelectedChannelId)
    }

    @Test
    fun resetForLauncherHome_clearsTargetAndRestoresPlaybackUiDefaults() {
        val state = MainRootState()
        val previousFocusTick = state.launcherHomeFocusTick
        state.enterLive(channel())
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
