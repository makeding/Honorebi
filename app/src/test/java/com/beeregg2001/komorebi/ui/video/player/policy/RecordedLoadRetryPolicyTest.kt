package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedLoadRetryPolicyTest {
    @Test
    fun keepsMedia3RetryCountsExceptForRecordedHlsDataTypes() {
        assertEquals(4, RecordedLoadRetryPolicy.minimumLoadableRetryCount(RecordedLoadDataType.Manifest, 9))
        assertEquals(7, RecordedLoadRetryPolicy.minimumLoadableRetryCount(RecordedLoadDataType.Media, 9))
        assertEquals(7, RecordedLoadRetryPolicy.minimumLoadableRetryCount(RecordedLoadDataType.MediaInitialization, 9))
        assertEquals(9, RecordedLoadRetryPolicy.minimumLoadableRetryCount(RecordedLoadDataType.Other, 9))
    }

    @Test
    fun offlineAndExpiredSessionsDoNotConsumeMedia3Retries() {
        assertEquals(
            RecordedLoadRetryDecision.DoNotRetry,
            RecordedLoadRetryPolicy.retryDecision(false, false, 1, true),
        )
        assertEquals(
            RecordedLoadRetryDecision.DoNotRetry,
            RecordedLoadRetryPolicy.retryDecision(true, true, 1, true),
        )
    }

    @Test
    fun preservesImmediateThenBoundedBackoff() {
        assertEquals(RecordedLoadRetryDecision.RetryAfter(0L), RecordedLoadRetryPolicy.retryDecision(true, false, 1, true))
        assertEquals(RecordedLoadRetryDecision.RetryAfter(0L), RecordedLoadRetryPolicy.retryDecision(true, false, 2, true))
        assertEquals(RecordedLoadRetryDecision.RetryAfter(1_000L), RecordedLoadRetryPolicy.retryDecision(true, false, 3, true))
        assertEquals(RecordedLoadRetryDecision.RetryAfter(8_000L), RecordedLoadRetryPolicy.retryDecision(true, false, 99, true))
    }

    @Test
    fun preservesMedia3TerminalDecision() {
        assertEquals(
            RecordedLoadRetryDecision.DoNotRetry,
            RecordedLoadRetryPolicy.retryDecision(true, false, 3, false),
        )
    }
}
