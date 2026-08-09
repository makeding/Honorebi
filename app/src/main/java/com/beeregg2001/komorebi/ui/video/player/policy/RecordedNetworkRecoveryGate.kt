package com.beeregg2001.komorebi.ui.video.player.policy

/** The smallest safe operation to retry after Android reports a network again. */
enum class RecordedNetworkRetry {
    ResolveInitialUrl,
    RenewStreamSession,
    RepreparePlayer,
}

sealed interface RecordedNetworkRecoveryDecision {
    /** Connectivity is present, so the caller may use its normal error policy. */
    data object ContinueOnline : RecordedNetworkRecoveryDecision

    /** Keep the current playback/session state and wait for a network edge. */
    data object WaitForNetwork : RecordedNetworkRecoveryDecision

    /** Execute exactly one recovery for the new online edge. */
    data class Retry(val operation: RecordedNetworkRetry) : RecordedNetworkRecoveryDecision

    data object None : RecordedNetworkRecoveryDecision
}

/**
 * Per-player connectivity gate. Offline failures never consume URL, 422, or
 * player-error budgets; a false -> true edge releases at most one retry.
 */
class RecordedNetworkRecoveryGate(initiallyAvailable: Boolean) {
    private var isAvailable = initiallyAvailable
    private var pendingRetry: RecordedNetworkRetry? = null

    fun onFailure(
        retry: RecordedNetworkRetry,
        networkAvailable: Boolean,
    ): RecordedNetworkRecoveryDecision {
        if (!networkAvailable) {
            isAvailable = false
            pendingRetry = mergePendingRetry(pendingRetry, retry)
            return RecordedNetworkRecoveryDecision.WaitForNetwork
        }

        // The Android callback has become available but its Compose event has
        // not released the pending action yet. Keep the failure parked so the
        // online edge remains the single owner of recovery.
        if (!isAvailable && pendingRetry != null) {
            return RecordedNetworkRecoveryDecision.WaitForNetwork
        }
        isAvailable = true
        return RecordedNetworkRecoveryDecision.ContinueOnline
    }

    fun onNetworkChanged(networkAvailable: Boolean): RecordedNetworkRecoveryDecision {
        if (!networkAvailable) {
            isAvailable = false
            return RecordedNetworkRecoveryDecision.None
        }
        if (isAvailable) return RecordedNetworkRecoveryDecision.None

        isAvailable = true
        val retry = pendingRetry ?: return RecordedNetworkRecoveryDecision.None
        pendingRetry = null
        return RecordedNetworkRecoveryDecision.Retry(retry)
    }

    fun onReady() {
        pendingRetry = null
    }

    private fun mergePendingRetry(
        current: RecordedNetworkRetry?,
        incoming: RecordedNetworkRetry,
    ): RecordedNetworkRetry = when {
        current == RecordedNetworkRetry.ResolveInitialUrl ||
            incoming == RecordedNetworkRetry.ResolveInitialUrl -> RecordedNetworkRetry.ResolveInitialUrl
        current == RecordedNetworkRetry.RenewStreamSession ||
            incoming == RecordedNetworkRetry.RenewStreamSession -> RecordedNetworkRetry.RenewStreamSession
        else -> RecordedNetworkRetry.RepreparePlayer
    }
}
