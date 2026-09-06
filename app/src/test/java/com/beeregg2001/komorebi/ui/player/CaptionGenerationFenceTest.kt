package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionGenerationFenceTest {
    @Test
    fun `source replacement rejects queued results before the old source is disposed`() {
        var sessionCurrent = true
        val fence = CaptionGenerationFence { sessionCurrent }
        val queued = fence.token()

        assertTrue(fence.accepts(queued))
        fence.reset()
        assertFalse(fence.accepts(queued))
        assertTrue(fence.accepts(fence.token()))

        sessionCurrent = false
        assertFalse(fence.accepts())
    }

    @Test
    fun `retired fence never accepts work even if its slot becomes current again`() {
        val fence = CaptionGenerationFence { true }
        val queued = fence.token()
        fence.retire()

        assertFalse(fence.accepts(queued))
        assertFalse(fence.accepts(fence.token()))
    }
}
