package com.beeregg2001.komorebi.ui.live

internal enum class LiveBroadcastStreamAction { NONE, PAUSE, PLAY, REOPEN }

/** Kept for the entire playback generation, including SSE reconnections. */
internal class LiveBroadcastStreamState {
    private var restartPending = false

    fun onStatus(status: String, mediaInvalid: Boolean): LiveBroadcastStreamAction = when (status) {
        "Restart" -> { restartPending = true; LiveBroadcastStreamAction.PAUSE }
        "Standby", "Offline" -> LiveBroadcastStreamAction.PAUSE
        "ONAir" -> {
            val reopen = restartPending || mediaInvalid
            restartPending = false
            if (reopen) LiveBroadcastStreamAction.REOPEN else LiveBroadcastStreamAction.PLAY
        }
        else -> LiveBroadcastStreamAction.NONE
    }
}
