package com.beeregg2001.komorebi.ui.video.player.policy

internal sealed interface SmbPlaybackRecoveryDecision {
    data object Reprepare : SmbPlaybackRecoveryDecision
    data object ShowFailure : SmbPlaybackRecoveryDecision
}

/** Bounds automatic SMB player recovery and fences repeated manual retry input. */
internal class SmbPlaybackRecoveryPolicy(
    private val maxAutomaticRetries: Int = 2,
) {
    private var automaticRetries = 0
    private var manualRetryPending = false

    fun onPlayerError(): SmbPlaybackRecoveryDecision {
        manualRetryPending = false
        if (automaticRetries >= maxAutomaticRetries) {
            return SmbPlaybackRecoveryDecision.ShowFailure
        }
        automaticRetries += 1
        return SmbPlaybackRecoveryDecision.Reprepare
    }

    fun beginManualRetry(): Boolean {
        if (manualRetryPending) return false
        manualRetryPending = true
        automaticRetries = 0
        return true
    }

    fun onReady() {
        manualRetryPending = false
    }
}
