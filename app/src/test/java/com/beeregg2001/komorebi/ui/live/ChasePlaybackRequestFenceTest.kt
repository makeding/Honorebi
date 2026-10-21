package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChasePlaybackRequestFenceTest {
    @Test fun lateResultIsDiscardedAfterChannelChanges() {
        val fence = ChasePlaybackRequestFence()
        val request = fence.begin("gr011")

        fence.invalidate()

        assertFalse(fence.isCurrent(request, "gr011"))
    }

    @Test fun resultOnlyAppliesToTheRequestingChannel() {
        val fence = ChasePlaybackRequestFence()
        val request = fence.begin("gr011")

        assertTrue(fence.isCurrent(request, "gr011"))
        assertFalse(fence.isCurrent(request, "gr012"))
    }
}
