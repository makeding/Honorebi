package com.beeregg2001.komorebi.ui.home

import org.junit.Assert.*
import org.junit.Test

class InputSourceAvailabilityTest {
    @Test fun hidesOnlyWhenBothRoutesAreAbsent() {
        assertFalse(showInputSourceAction(false, false))
        assertTrue(showInputSourceAction(true, false))
        assertTrue(showInputSourceAction(false, true))
        assertTrue(showInputSourceAction(true, true))
    }
}
