package com.beeregg2001.komorebi.ui.player

internal enum class PlayerRecoveryResumeDecision {
    ResumeNow,
    ResumeOnStart,
    Drop,
}

/** Keeps a delayed player recovery inside its runtime generation and foreground lifecycle. */
internal class PlayerRecoveryLifecycleGate(initiallyForeground: Boolean) {
    private var foreground = initiallyForeground
    private var generation = 0L
    private var pendingResume = false
    private var retired = false

    @Synchronized
    fun beginRecovery(): Long = generation

    @Synchronized
    fun finishDelay(token: Long, stillCurrent: Boolean): PlayerRecoveryResumeDecision {
        if (retired || token != generation || !stillCurrent) return PlayerRecoveryResumeDecision.Drop
        if (foreground) return PlayerRecoveryResumeDecision.ResumeNow
        pendingResume = true
        return PlayerRecoveryResumeDecision.ResumeOnStart
    }

    @Synchronized
    fun onStart(): Boolean {
        if (retired) return false
        foreground = true
        return pendingResume.also { pendingResume = false }
    }

    @Synchronized
    fun onStop() {
        if (!retired) foreground = false
    }

    @Synchronized
    fun retire() {
        retired = true
        foreground = false
        pendingResume = false
        generation += 1L
    }
}
