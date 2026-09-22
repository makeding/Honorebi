package com.beeregg2001.komorebi.ui.live

/**
 * Keeps a live slot parked while Android reports that the device has no active network.
 * A false -> true edge releases one fresh stream request; offline callbacks never spend
 * the media recovery budget.
 */
internal class LiveNetworkRecoveryGate(initiallyAvailable: Boolean) {
    private var networkAvailable = initiallyAvailable
    private var retryPending = false

    fun onRecoveryNeeded(currentlyAvailable: Boolean): LiveNetworkRecoveryDecision {
        if (!currentlyAvailable) {
            networkAvailable = false
            retryPending = true
            return LiveNetworkRecoveryDecision.WaitForNetwork
        }

        // A synchronous availability check can observe the restored network before its
        // callback. This request already tests that network, so it owns normal recovery.
        networkAvailable = true
        retryPending = false
        return LiveNetworkRecoveryDecision.RecoverNow
    }

    fun onNetworkChanged(available: Boolean): LiveNetworkRecoveryDecision {
        if (!available) {
            networkAvailable = false
            return LiveNetworkRecoveryDecision.None
        }
        if (networkAvailable) return LiveNetworkRecoveryDecision.None

        networkAvailable = true
        if (!retryPending) return LiveNetworkRecoveryDecision.None
        retryPending = false
        return LiveNetworkRecoveryDecision.RecoverNow
    }

    fun onPlaybackStarted() {
        retryPending = false
    }
}

internal enum class LiveNetworkRecoveryDecision { None, WaitForNetwork, RecoverNow }
