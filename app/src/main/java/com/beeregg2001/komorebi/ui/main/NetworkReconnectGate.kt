package com.beeregg2001.komorebi.ui.main

/** Remembers a disconnection even when backend settings are not ready yet. */
internal class NetworkReconnectGate {
    private var needsRecheck = false
    private var lastWakeGeneration = 0

    fun shouldRecheck(available: Boolean, settingsInitialized: Boolean, awake: Boolean = true, wakeGeneration: Int = 0): Boolean {
        if (!available || !awake || wakeGeneration != lastWakeGeneration) needsRecheck = true
        lastWakeGeneration = wakeGeneration
        if (!available || !awake || !settingsInitialized || !needsRecheck) return false
        needsRecheck = false
        return true
    }
}

internal enum class OfflinePresentation { CONNECTED, CACHED, CONNECTION_FAILURE }

/** Called only after the current request (including a queued reconnect) has finished. */
internal fun offlinePresentation(
    networkAvailable: Boolean,
    channelError: Boolean,
    offlineMode: Boolean,
    dataReady: Boolean,
): OfflinePresentation = when {
    !networkAvailable -> OfflinePresentation.CACHED
    !channelError -> OfflinePresentation.CONNECTED
    offlineMode || dataReady -> OfflinePresentation.CACHED
    else -> OfflinePresentation.CONNECTION_FAILURE
}
