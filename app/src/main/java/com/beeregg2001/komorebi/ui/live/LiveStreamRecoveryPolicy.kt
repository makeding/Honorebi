package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.StreamSource

/** Retry only the KonomiTV tuner and invalid-media failures that can clear server-side. */
internal object LiveStreamRecoveryPolicy {
    private const val INITIAL_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L

    fun shouldRetryIndefinitely(
        source: StreamSource,
        errorCodeName: String,
        causes: Sequence<Pair<String, String?>>,
    ): Boolean {
        if (source != StreamSource.KONOMITV) return false
        return causes.any { (type, message) ->
            type.substringAfterLast('.').contains("UnrecognizedInputFormatException") ||
                message?.contains("E-01M", ignoreCase = true) == true
        } || errorCodeName.contains("UNRECOGNIZED", ignoreCase = true)
    }

    fun retryDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 20)
        return (INITIAL_DELAY_MS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MS)
    }
}
