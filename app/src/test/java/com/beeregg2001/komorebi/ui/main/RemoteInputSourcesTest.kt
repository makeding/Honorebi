package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputSourcesTest {
    @Test fun tunerVisibilityDistinguishesEmptyFromMissingPermission() {
        assertFalse(showRemoteTuner(false, true, false, 1))
        assertFalse(showRemoteTuner(true, true, true, 0))
        assertTrue(showRemoteTuner(true, false, true, 0))
        assertTrue(showRemoteTuner(true, true, true, 1))
        assertTrue(showRemoteTuner(true, true, false, 0))
    }
}
