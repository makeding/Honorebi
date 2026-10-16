package com.beeregg2001.komorebi.ui.screensaver

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PausedPlayback(val title: String, val subtitle: String = "")

internal data class PlayerIdleSession(
    val active: Boolean,
    val paused: PausedPlayback? = null,
    val ended: Boolean = false,
)

/** Activity-wide user-idle clock shared by the main UI and its screensaver overlay. */
object ScreensaverInteractionTracker {
    private val _lastInteractionMillis = MutableStateFlow(SystemClock.elapsedRealtime())
    val lastInteractionMillis = _lastInteractionMillis.asStateFlow()

    private val _playbackActive = MutableStateFlow(false)
    val playbackActive = _playbackActive.asStateFlow()
    private val sessions = linkedMapOf<Any, PlayerIdleSession>()
    private val _pausedPlayback = MutableStateFlow<PausedPlayback?>(null)
    val pausedPlayback = _pausedPlayback.asStateFlow()
    private val _playbackEnded = MutableStateFlow(false)
    val playbackEnded = _playbackEnded.asStateFlow()
    private val _systemSleepAllowed = MutableStateFlow(false)
    val systemSleepAllowed = _systemSleepAllowed.asStateFlow()

    internal fun allowSystemSleep(allowed: Boolean) { _systemSleepAllowed.value = allowed }

    fun onUserInteraction() {
        _systemSleepAllowed.value = false
        _lastInteractionMillis.value = SystemClock.elapsedRealtime()
    }

    @Synchronized
    internal fun updatePlayer(owner: Any, session: PlayerIdleSession?) {
        if (session == null) sessions.remove(owner) else sessions[owner] = session
        publish()
    }

    private fun publish() {
        val active = sessions.values.any { it.active }
        val paused = if (active) null else sessions.values.firstNotNullOfOrNull { it.paused }
        val ended = !active && paused == null && sessions.isNotEmpty() && sessions.values.all { it.ended }
        if (!ended) _systemSleepAllowed.value = false
        if (_playbackActive.value != active || (_pausedPlayback.value != null) != (paused != null) ||
            _playbackEnded.value != ended) onUserInteraction()
        _playbackActive.value = active
        _pausedPlayback.value = paused
        _playbackEnded.value = ended
    }
}
