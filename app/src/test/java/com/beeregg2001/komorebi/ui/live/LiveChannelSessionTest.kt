package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelSessionTest {
    @Test
    fun mainAndDualSlotsKeepIndependentCurrentSessions() {
        val coordinator = LiveChannelSessionCoordinator()
        val main = coordinator.begin(LivePlaybackSlot.MAIN, "main")
        val dual = coordinator.begin(LivePlaybackSlot.DUAL, "dual")

        assertEquals(main, coordinator.current(LivePlaybackSlot.MAIN))
        assertEquals(dual, coordinator.current(LivePlaybackSlot.DUAL))
        assertTrue(coordinator.isCurrent(main))
        assertTrue(coordinator.isCurrent(dual))
    }

    @Test
    fun rapidChannelChangesInvalidateOlderTokens() {
        val coordinator = LiveChannelSessionCoordinator()
        val first = coordinator.begin(LivePlaybackSlot.MAIN, "A")
        val second = coordinator.begin(LivePlaybackSlot.MAIN, "B")
        val third = coordinator.begin(LivePlaybackSlot.MAIN, "C")

        assertFalse(coordinator.isCurrent(first))
        assertFalse(coordinator.isCurrent(second))
        assertTrue(coordinator.isCurrent(third))
        assertEquals(third, coordinator.current(LivePlaybackSlot.MAIN))
    }

    @Test
    fun endInvalidatesTheCurrentToken() {
        val coordinator = LiveChannelSessionCoordinator()
        val token = coordinator.begin(LivePlaybackSlot.DUAL, "dual")

        coordinator.end(LivePlaybackSlot.DUAL)

        assertFalse(coordinator.isCurrent(token))
        assertNull(coordinator.current(LivePlaybackSlot.DUAL))
    }

    @Test
    fun epochsIncreaseWithoutReuseAcrossEnds() {
        val coordinator = LiveChannelSessionCoordinator()
        val first = coordinator.begin(LivePlaybackSlot.MAIN, "A")
        val second = coordinator.begin(LivePlaybackSlot.MAIN, "B")
        coordinator.end(LivePlaybackSlot.MAIN)
        val third = coordinator.begin(LivePlaybackSlot.MAIN, "C")

        assertEquals(1L, first.epoch)
        assertEquals(2L, second.epoch)
        assertEquals(3L, third.epoch)
    }

    @Test
    fun dataBroadcastingCallbackRejectsEventsFromAnOldSession() {
        val coordinator = LiveChannelSessionCoordinator()
        val token = coordinator.begin(LivePlaybackSlot.MAIN, "A")
        var resets = 0
        val callback = LiveSessionDataBroadcastingCallback(
            token,
            coordinator,
            object : B60DataBroadcastingCallback {
                override fun onApplicationResourcesReset() {
                    resets++
                }
            }
        )

        callback.onApplicationResourcesReset()
        coordinator.begin(LivePlaybackSlot.MAIN, "B")
        callback.onApplicationResourcesReset()

        assertEquals(1, resets)
    }
}
