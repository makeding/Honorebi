package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkReconnectGateTest {
    @Test fun offlineStartupAndEachReconnectRequestExactlyOneRecheck() {
        val gate = NetworkReconnectGate()
        assertFalse(gate.shouldRecheck(false, true))
        assertTrue(gate.shouldRecheck(true, true))
        repeat(3) { assertFalse(gate.shouldRecheck(true, true)) }
        assertFalse(gate.shouldRecheck(false, true))
        assertTrue(gate.shouldRecheck(true, true))
    }

    @Test fun reconnectBeforeSettingsReadyIsNotLost() {
        val gate = NetworkReconnectGate()
        assertFalse(gate.shouldRecheck(false, false))
        assertFalse(gate.shouldRecheck(true, false))
        assertTrue(gate.shouldRecheck(true, true))
        assertFalse(gate.shouldRecheck(true, true))
    }

    @Test fun onlineStartupUsesExistingInitialRequest() {
        assertFalse(NetworkReconnectGate().shouldRecheck(true, true))
    }

    @Test fun disconnectedDeviceCannotBeClearedByAnOldSuccessfulResponse() {
        assertEquals(OfflinePresentation.CACHED, offlinePresentation(false, false, true, true))
    }

    @Test fun networkAloneDoesNotClearOfflineButSuccessfulRecheckDoes() {
        assertEquals(OfflinePresentation.CACHED, offlinePresentation(true, true, true, true))
        assertEquals(OfflinePresentation.CONNECTED, offlinePresentation(true, false, true, true))
    }

    @Test fun startupFailureHasRecoveryActionsWhileBackgroundFailureKeepsContent() {
        assertEquals(OfflinePresentation.CONNECTION_FAILURE, offlinePresentation(true, true, false, false))
        assertEquals(OfflinePresentation.CACHED, offlinePresentation(true, true, false, true))
    }
}
