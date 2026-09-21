package com.beeregg2001.komorebi.data.api.interceptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackendRetryGateTest {
    @Test
    fun transientFailureBacksOffAndStaysBounded() {
        val gate = BackendRetryGate(1_000)
        gate.failed(IOException("timeout"))
        assertEquals(2_000, gate.delayMs)
        assertTrue(gate.canAttempt(2_000))
        assertFalse(gate.canAttempt(1_999))
        repeat(20) { gate.failed(IOException("timeout")) }
        assertEquals(256_000, gate.delayMs)

        val capped = BackendRetryGate(10_000)
        repeat(20) { capped.failed(IOException("timeout")) }
        assertEquals(300_000, capped.delayMs)
    }

    @Test
    fun nonRetryableBackendFailurePausesUntilReset() {
        val gate = BackendRetryGate(1_000)
        gate.failed(
            BackendApiException(
                "BACKEND_AUTH_DENIED", 403, "https://host/api", "application/json", false, "denied",
            ),
        )
        assertFalse(gate.canAttempt(Long.MAX_VALUE))
        gate.reset()
        assertEquals(1_000, gate.delayMs)
        assertTrue(gate.canAttempt(1_000))
    }
}
