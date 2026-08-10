package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainRootPlaybackStateTest {

    @Test
    fun enterTransitions_keepExactlyOneTargetAndUpdateReturnState() {
        val state = MainRootPlaybackState()
        val channel = channel()
        val recording = recording()

        state.enterLive(channel)
        assertEquals(PlaybackTarget.Live(channel), state.playbackTarget)
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
        state.enterLive(channel())
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
    }

    @Test
    fun recordedSwitch_keepsTheSessionAndNeverClearsTheCurrentTarget() {
        val state = MainRootPlaybackState()
        val first = recording(id = 42)
        val next = recording(id = 43)
        state.enterRecorded(first, initialPositionMs = 1_000L)
        val session = requireNotNull(state.playbackSession)

        assertTrue(state.beginRecordedSwitch(next, initialPositionMs = 2_000L, reason = PlaybackSwitchReason.NextEpisode))

        assertEquals(PlaybackTarget.Recorded(first), state.playbackTarget)
        assertEquals(PlaybackTarget.Recorded(next), state.renderPlaybackTarget)
        assertEquals(2_000L, state.renderInitialPlaybackPositionMs)
        assertSame(session, state.playbackSession)
        assertEquals(
            PlaybackPhase.Switching(
                from = PlaybackTarget.Recorded(first),
                to = PlaybackTarget.Recorded(next),
                reason = PlaybackSwitchReason.NextEpisode,
                initialPositionMs = 2_000L,
                token = requireNotNull(state.recordedSwitchToken),
            ),
            state.playbackPhase,
        )
        assertTrue(state.commitRecordedSwitch(requireNotNull(state.recordedSwitchToken)))
        assertEquals(PlaybackTarget.Recorded(next), state.playbackTarget)
        assertEquals(PlaybackPhase.Playing(PlaybackTarget.Recorded(next)), state.playbackPhase)
        assertSame(session, state.playbackSession)
        assertEquals(2_000L, state.initialPlaybackPositionMs)
    }

    @Test
    fun failedRecordedSwitch_restoresThePreviousRecordingAndSession() {
        val state = MainRootPlaybackState()
        val first = recording(id = 42)
        state.enterRecorded(first, initialPositionMs = 1_000L)
        val session = requireNotNull(state.playbackSession)

        assertTrue(state.beginRecordedSwitch(recording(id = 43), initialPositionMs = 2_000L, reason = PlaybackSwitchReason.QuickSelect))
        assertTrue(state.failRecordedSwitch(requireNotNull(state.recordedSwitchToken)))

        assertEquals(PlaybackTarget.Recorded(first), state.playbackTarget)
        assertEquals(PlaybackTarget.Recorded(first), state.renderPlaybackTarget)
        assertEquals(1_000L, state.renderInitialPlaybackPositionMs)
        assertEquals(PlaybackPhase.Playing(PlaybackTarget.Recorded(first)), state.playbackPhase)
        assertEquals(1_000L, state.initialPlaybackPositionMs)
        assertSame(session, state.playbackSession)
    }

    @Test
    fun recordedSwitch_rejectsTheCurrentProgram() {
        val state = MainRootPlaybackState()
        val current = recording(id = 42)
        state.enterRecorded(current)

        assertFalse(
            state.beginRecordedSwitch(
                current,
                reason = PlaybackSwitchReason.QuickSelect,
            )
        )
        assertEquals(PlaybackPhase.Playing(PlaybackTarget.Recorded(current)), state.playbackPhase)
    }

    @Test
    fun recordedSwitch_ignoresReadyAndFailureFromAnotherAttemptOrSession() {
        val state = MainRootPlaybackState()
        val first = recording(id = 42)
        state.enterRecorded(first)
        assertTrue(state.beginRecordedSwitch(recording(id = 43), reason = PlaybackSwitchReason.NextEpisode))
        val token = requireNotNull(state.recordedSwitchToken)
        val staleAttempt = token.copy(attemptId = token.attemptId - 1)
        val staleEpoch = token.copy(sessionEpoch = token.sessionEpoch + 1)

        assertFalse(state.commitRecordedSwitch(staleAttempt))
        assertFalse(state.failRecordedSwitch(staleEpoch))
        assertEquals(PlaybackPhase.Switching::class, state.playbackPhase::class)
        assertTrue(state.failRecordedSwitch(token))
        assertEquals(PlaybackPhase.Playing(PlaybackTarget.Recorded(first)), state.playbackPhase)
    }

    @Test
    fun leavePlayback_endsTheSessionAndReturnsToIdle() {
        val state = MainRootPlaybackState()
        state.enterRecorded(recording())

        state.leavePlayback()

        assertEquals(PlaybackTarget.None, state.playbackTarget)
        assertEquals(PlaybackPhase.Idle, state.playbackPhase)
        assertNull(state.playbackSession)
    }

    @Test
    fun playbackSession_livesUntilLeaveEvenWhenOrdinaryEnterChangesTarget() {
        val state = MainRootPlaybackState()
        state.enterLive(channel())
        val firstSession = requireNotNull(state.playbackSession)

        state.enterLive(channel(id = "gr-next"))

        assertSame(firstSession, state.playbackSession)
        assertEquals(PlaybackTarget.Live(channel(id = "gr-next")), state.playbackTarget)

        state.leavePlayback()
        state.enterLive(channel(id = "gr-after-leave"))

        val secondSession = requireNotNull(state.playbackSession)
        assertFalse(firstSession == secondSession)
        assertTrue(secondSession.epoch > firstSession.epoch)
        assertEquals(PlaybackPhase.Playing(PlaybackTarget.Live(channel(id = "gr-after-leave"))), state.playbackPhase)
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
}
