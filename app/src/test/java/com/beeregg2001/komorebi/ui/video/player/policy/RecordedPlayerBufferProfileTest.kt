package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedPlayerBufferProfileTest {
    @Test
    fun selectsRecordedProfileByDefault() {
        assertEquals(
            RecordedPlayerBufferProfile(
                targetBufferBytes = 64 * 1024 * 1024,
                minBufferMs = 30_000,
                maxBufferMs = 90_000,
                bufferForPlaybackMs = 4_000,
                bufferForPlaybackAfterRebufferMs = 8_000,
            ),
            RecordedPlayerBufferProfile.select(false, false),
        )
    }

    @Test
    fun selectsChaseProfileForChasePlayback() {
        assertEquals(
            RecordedPlayerBufferProfile.Chase,
            RecordedPlayerBufferProfile.select(false, true),
        )
    }

    @Test
    fun rawMmtsTakesPrecedenceOverChasePlayback() {
        assertEquals(
            RecordedPlayerBufferProfile.RawMmts,
            RecordedPlayerBufferProfile.select(true, true),
        )
    }
}
