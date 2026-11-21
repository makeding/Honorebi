package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuPlaybackClockTest {
    @Test
    fun idleFramesKeepTimeAndPausesDoNot() {
        var nanos = 0L
        val clock = DanmakuPlaybackClock { nanos }
        clock.resume()
        nanos = 10_500_750_000L
        assertEquals(10_500L, clock.positionMs())
        clock.pause()
        nanos += 1_000_000_000L
        assertEquals(10_500L, clock.positionMs())
        clock.resume()
        nanos += 500_000L
        assertEquals(10_501L, clock.positionMs())
    }
}
