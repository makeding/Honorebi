package com.beeregg2001.komorebi.ui.live

internal enum class LiveBroadcastStreamAction {
    NONE, PAUSE, PLAY,
    /** A server-declared Restart followed by ONAir needs a fresh live timeline. */
    REOPEN_AFTER_RESTART,
    /** An established stream went offline; only a new media request can start it again. */
    RECOVER_OFFLINE,
    /** A locally ended/errored player needs the bounded media-error recovery path. */
    RECOVER_INVALID_MEDIA,
}

/** Kept for the entire playback generation, including SSE reconnections. */
internal class LiveBroadcastStreamState {
    private var restartPending = false
    private var hasBeenOnAir = false
    private var offlineRecoveryRequested = false

    fun onStatus(status: String, mediaInvalid: Boolean): LiveBroadcastStreamAction = when (status) {
        "Restart" -> { restartPending = true; LiveBroadcastStreamAction.PAUSE }
        "Standby" -> LiveBroadcastStreamAction.PAUSE
        "Offline" -> {
            if (hasBeenOnAir && !offlineRecoveryRequested) {
                offlineRecoveryRequested = true
                LiveBroadcastStreamAction.RECOVER_OFFLINE
            } else LiveBroadcastStreamAction.PAUSE
        }
        "ONAir" -> {
            val restarted = restartPending
            restartPending = false
            hasBeenOnAir = true
            offlineRecoveryRequested = false
            when {
                restarted -> LiveBroadcastStreamAction.REOPEN_AFTER_RESTART
                mediaInvalid -> LiveBroadcastStreamAction.RECOVER_INVALID_MEDIA
                else -> LiveBroadcastStreamAction.PLAY
            }
        }
        else -> LiveBroadcastStreamAction.NONE
    }
}
