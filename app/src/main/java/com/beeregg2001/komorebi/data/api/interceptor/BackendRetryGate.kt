package com.beeregg2001.komorebi.data.api.interceptor

/** One owner per polling loop. Callers retain responsibility for single-flight and cancellation. */
class BackendRetryGate(private val normalDelayMs: Long) {
    private var failures = 0
    private var paused = false
    var delayMs: Long = normalDelayMs
        private set

    fun reset() {
        failures = 0
        paused = false
        delayMs = normalDelayMs
    }

    fun failed(error: Throwable) {
        paused = error.backendApiFailure()?.automaticallyRetryable == false
        failures = (failures + 1).coerceAtMost(8)
        delayMs = (normalDelayMs * (1L shl failures)).coerceAtMost(300_000L)
    }

    fun canAttempt(elapsedMs: Long): Boolean = !paused && elapsedMs >= delayMs
}
