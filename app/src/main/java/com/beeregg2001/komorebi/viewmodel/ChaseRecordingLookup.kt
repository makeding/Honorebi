package com.beeregg2001.komorebi.viewmodel

import android.util.Log
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.common.userFacingFailure
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

sealed interface ChaseRecordingLookupResult {
    data class Found(val program: RecordedProgram) : ChaseRecordingLookupResult
    data object Unavailable : ChaseRecordingLookupResult
    data class Failure(val failure: UserFacingFailure) : ChaseRecordingLookupResult
}

/** An empty completed lookup is distinct from a failed request and from cancellation. */
internal suspend fun resolveChaseRecordingLookup(
    lookup: suspend () -> RecordedProgram?,
): ChaseRecordingLookupResult = try {
    withTimeout(15_000L) { lookup() }?.let(ChaseRecordingLookupResult::Found)
        ?: ChaseRecordingLookupResult.Unavailable
} catch (error: TimeoutCancellationException) {
    ChaseRecordingLookupResult.Failure(userFacingFailure(error, "CHASE_LOOKUP_FAILED"))
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Log.e("ChaseRecordingLookup", "Failed to resolve chase playback recording", error)
    ChaseRecordingLookupResult.Failure(userFacingFailure(error, "CHASE_LOOKUP_FAILED"))
}
