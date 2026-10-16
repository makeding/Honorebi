package com.beeregg2001.komorebi.ui.screensaver

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlayerIdleTrackingTest {
    private val info = PausedPlayback("番組", "チャンネル")

    @Test fun readyPauseBufferingEndAndErrorAreDistinct() {
        assertEquals(PlayerIdleSession(false, info), playerIdleSession(Player.STATE_READY, false, false, false, info))
        assertEquals(PlayerIdleSession(false, info), playerIdleSession(Player.STATE_BUFFERING, false, false, false, info))
        assertEquals(PlayerIdleSession(true), playerIdleSession(Player.STATE_BUFFERING, true, false, false, info))
        assertEquals(PlayerIdleSession(true), playerIdleSession(Player.STATE_READY, true, true, false, info))
        assertEquals(PlayerIdleSession(false, ended = true), playerIdleSession(Player.STATE_ENDED, true, false, false, info))
        assertEquals(PlayerIdleSession(false), playerIdleSession(Player.STATE_IDLE, false, false, false, info))
        assertEquals(PlayerIdleSession(false), playerIdleSession(Player.STATE_READY, false, false, true, info))
        assertEquals(PlayerIdleSession(false), playerIdleSession(Player.STATE_ENDED, true, false, true, info))
    }
}
