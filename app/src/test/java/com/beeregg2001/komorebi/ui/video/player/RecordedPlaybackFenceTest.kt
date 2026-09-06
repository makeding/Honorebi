package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.ui.player.RecordedPlaybackToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedPlaybackFenceTest {
    @Test fun `recorded fence rejects a superseded token`() {
        val token = RecordedPlaybackToken(sessionEpoch = 1, attemptId = 2, programId = 3)
        val fence = RecordedPlaybackFence(token, { it == token }, token)
        assertTrue(fence.accepts(token))
        assertFalse(fence.accepts(token.copy(attemptId = 4)))
    }

    @Test fun `smb fence remains valid without a recorded token`() {
        val fence = RecordedPlaybackFence(null, { false }, "smb:path:1")
        assertTrue(fence.accepts())
        assertTrue(fence.accepts(null))
    }
}
