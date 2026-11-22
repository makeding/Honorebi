package com.beeregg2001.komorebi.ui.main

/**
 * Pure policy governing OS-level Picture-in-Picture entry and dismissal detection.
 */
object OsPipPolicy {
    const val MIN_PIP_SDK_INT = 26
    const val INTERNAL_LAUNCH_GRACE_MS = 2_000L

    fun shouldEnter(
        sdkInt: Int,
        hasPipFeature: Boolean,
        eligible: Boolean,
        alreadyInPip: Boolean,
        msSinceInternalLaunch: Long?,
    ): Boolean {
        if (sdkInt < MIN_PIP_SDK_INT) return false
        if (!hasPipFeature || !eligible || alreadyInPip) return false
        if (msSinceInternalLaunch != null && msSinceInternalLaunch <= INTERNAL_LAUNCH_GRACE_MS) {
            return false
        }
        return true
    }

    fun isDismissal(
        stillInPip: Boolean,
        atLeastStarted: Boolean,
        stoppedWhileInPip: Boolean,
    ): Boolean = !stillInPip && (!atLeastStarted || stoppedWhileInPip)
}
