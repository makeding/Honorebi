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
internal enum class OfflineUiAction { SHOW_CONNECTED, SHOW_CACHED, SHOW_CONNECTION_GUIDE, KEEP_CONNECTION_GUIDE }

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

/** A shown failure guide is retired only by success or an explicit user action. */
internal fun offlineUiAction(
    presentation: OfflinePresentation,
    connectionGuideVisible: Boolean,
): OfflineUiAction = when (presentation) {
    OfflinePresentation.CONNECTED -> OfflineUiAction.SHOW_CONNECTED
    OfflinePresentation.CONNECTION_FAILURE -> OfflineUiAction.SHOW_CONNECTION_GUIDE
    OfflinePresentation.CACHED -> if (connectionGuideVisible) {
        OfflineUiAction.KEEP_CONNECTION_GUIDE
    } else {
        OfflineUiAction.SHOW_CACHED
    }
}
