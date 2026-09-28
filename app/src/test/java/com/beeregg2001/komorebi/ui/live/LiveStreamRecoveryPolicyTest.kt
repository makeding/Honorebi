package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamRecoveryPolicyTest {
    @Test fun token30To32FailureSequenceKeepsSchedulingAutomaticReopens() {
        val failures = listOf(
            "UnrecognizedInputFormatException" to "Invalid stream", // token 30
            "LiveStreamStatusException" to "E-01M", // token 31
            "UnrecognizedInputFormatException" to "Invalid stream", // token 32
        )
        failures.forEach { (type, message) ->
            assertTrue(LiveStreamRecoveryPolicy.shouldRetryIndefinitely(
                StreamSource.KONOMITV, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
                sequenceOf(type to message),
            ))
        }
        assertEquals(8_000L, LiveStreamRecoveryPolicy.retryDelayMs(failures.size))
    }

    @Test fun tunerAndInvalidMediaErrorsRetryOnlyForKonomiTv() {
        assertTrue(LiveStreamRecoveryPolicy.shouldRetryIndefinitely(
            StreamSource.KONOMITV, "ERROR_CODE_UNSPECIFIED", sequenceOf("LiveStreamStatusException" to "E-01M"),
        ))
        assertTrue(LiveStreamRecoveryPolicy.shouldRetryIndefinitely(
            StreamSource.KONOMITV, "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
            sequenceOf("UnrecognizedInputFormatException" to "Invalid stream"),
        ))
        assertFalse(LiveStreamRecoveryPolicy.shouldRetryIndefinitely(
            StreamSource.EDCB, "ERROR_CODE_UNSPECIFIED", sequenceOf("Exception" to "E-01M"),
        ))
        assertFalse(LiveStreamRecoveryPolicy.shouldRetryIndefinitely(
            StreamSource.KONOMITV, "ERROR_CODE_DECODING_FAILED", sequenceOf("MediaCodec" to "unsupported"),
        ))
    }

    @Test fun retryDelayGrowsAndCaps() {
        assertEquals(2_000L, LiveStreamRecoveryPolicy.retryDelayMs(1))
        assertEquals(4_000L, LiveStreamRecoveryPolicy.retryDelayMs(2))
        assertEquals(16_000L, LiveStreamRecoveryPolicy.retryDelayMs(4))
        assertEquals(30_000L, LiveStreamRecoveryPolicy.retryDelayMs(5))
        assertEquals(30_000L, LiveStreamRecoveryPolicy.retryDelayMs(20))
    }
}
