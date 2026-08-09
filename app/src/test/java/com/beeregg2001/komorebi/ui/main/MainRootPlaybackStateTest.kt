package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRootPlaybackStateTest {

    @Test
    fun enterTransitions_keepExactlyOneTargetAndUpdateReturnState() {
        val state = MainRootPlaybackState()
        val channel = channel()
        val recording = recording()

        state.enterLive(channel, baseballMode = true)
        assertEquals(PlaybackTarget.Live(channel), state.playbackTarget)
        assertTrue(state.isBaseballMode)
        assertEquals(channel.id, state.lastSelectedChannelId)

        state.enterRecorded(recording, initialPositionMs = 12_345L)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackTarget)
        assertEquals(12_345L, state.initialPlaybackPositionMs)
        assertEquals(recording.id, state.lastPlayedRecordingId)
        assertFalse(state.isMiniPlayerMode)
    }

    @Test
    fun resetPlayback_onlyResetsPlaybackOwnedState() {
        val state = MainRootPlaybackState()
        state.enterLive(channel(), baseballMode = true)
        state.initialPlaybackPositionMs = 7_000L
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

        state.resetPlayback()

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
}
