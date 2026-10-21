package com.beeregg2001.komorebi.viewmodel

import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ChaseRecordingLookupTest {
    @Test fun completedLookupDistinguishesRecordingFromEmptyResult() = runTest {
        val program = RecordedProgram(42, "録画中", description = "", startTime = "", endTime = "",
            duration = 60.0, isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(42, "Recording", "", duration = 60.0,
                containerFormat = "MPEG-TS", videoCodec = "H.264", audioCodec = "AAC"))
        assertEquals(ChaseRecordingLookupResult.Found(program), resolveChaseRecordingLookup { program })
        assertEquals(ChaseRecordingLookupResult.Unavailable, resolveChaseRecordingLookup { null })
    }

    @Test fun networkFailureAndTimeoutHaveRecoverableSafeReasons() = runTest {
        val failed = resolveChaseRecordingLookup { throw IOException("secret URL") } as ChaseRecordingLookupResult.Failure
        assertEquals("CHASE_LOOKUP_FAILED_NETWORK", failed.failure.code)
        assertFalse(failed.failure.message.contains("secret"))
        val timeout = resolveChaseRecordingLookup { awaitCancellation() } as ChaseRecordingLookupResult.Failure
        assertEquals("CHASE_LOOKUP_FAILED_TIMEOUT", timeout.failure.code)
    }

    @Test fun cancellationDoesNotBecomeAnEmptyResultOrVisibleFailure() = runTest {
        try {
            resolveChaseRecordingLookup { throw CancellationException("channel changed") }
            fail("Cancelled requests must not produce a result")
        } catch (_: CancellationException) { }
    }
}
