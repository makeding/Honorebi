package com.beeregg2001.komorebi.ui.video.player.policy

/** Pure recorded-HLS retry rules; the Media3 adapter supplies its default delay. */
object RecordedLoadRetryPolicy {
    private const val RetryDelayMs = 1_000L
    private const val MaxRetryDelayMs = 8_000L

    fun minimumLoadableRetryCount(dataType: RecordedLoadDataType, fallback: Int): Int = when (dataType) {
        RecordedLoadDataType.Manifest -> 4
        RecordedLoadDataType.Media, RecordedLoadDataType.MediaInitialization -> 7
        else -> fallback
    }

    fun retryDecision(
        networkAvailable: Boolean,
        isHttp422: Boolean,
        errorCount: Int,
        media3CanRetry: Boolean,
    ): RecordedLoadRetryDecision {
        if (!networkAvailable || isHttp422 || !media3CanRetry) {
            return RecordedLoadRetryDecision.DoNotRetry
        }
        return if (errorCount <= 2) {
            RecordedLoadRetryDecision.RetryAfter(0L)
        } else {
            RecordedLoadRetryDecision.RetryAfter(
                ((errorCount - 2) * RetryDelayMs).coerceAtMost(MaxRetryDelayMs),
            )
        }
    }
}

enum class RecordedLoadDataType {
    Manifest,
    Media,
    MediaInitialization,
    Other,
}

sealed interface RecordedLoadRetryDecision {
    data object DoNotRetry : RecordedLoadRetryDecision
    data class RetryAfter(val delayMs: Long) : RecordedLoadRetryDecision
}
