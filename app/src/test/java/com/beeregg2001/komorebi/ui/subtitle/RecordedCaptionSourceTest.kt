package com.beeregg2001.komorebi.ui.subtitle

import org.junit.Assert.*
import org.junit.Test

class RecordedCaptionSourceTest {
    @Test fun `switch rejects old source before disposal and after returning to same quality`() {
        var current = true
        val old = RecordedCaptionSource { current }
        val task = old.token()
        assertTrue(old.accepts(task))
        current = false
        assertFalse(old.accepts(task))
        old.retire()
        current = true
        assertFalse(old.accepts(task))
        assertTrue(RecordedCaptionSource { true }.accepts())
    }

    @Test fun `seek discards queued and late async results but accepts new work`() {
        val source = RecordedCaptionSource { true }
        val queued = source.token()
        source.reset()
        assertFalse(source.accepts(queued))
        assertTrue(source.accepts(source.token()))
    }
}
