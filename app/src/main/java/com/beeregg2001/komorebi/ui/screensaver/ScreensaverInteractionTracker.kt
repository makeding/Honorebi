package com.beeregg2001.komorebi.ui.screensaver

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-wide user-idle clock shared by the main UI and its screensaver overlay. */
object ScreensaverInteractionTracker {
    private val _lastInteractionMillis = MutableStateFlow(SystemClock.elapsedRealtime())
    val lastInteractionMillis = _lastInteractionMillis.asStateFlow()

    private val playbackOwners = mutableSetOf<Any>()
    private val _playbackActive = MutableStateFlow(false)
    val playbackActive = _playbackActive.asStateFlow()

    fun onUserInteraction() {
        _lastInteractionMillis.value = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun setPlaybackActive(owner: Any, active: Boolean) {
        val wasActive = playbackOwners.isNotEmpty()
        if (active) playbackOwners.add(owner) else playbackOwners.remove(owner)
        val isActive = playbackOwners.isNotEmpty()
        if (wasActive != isActive) {
            _playbackActive.value = isActive
            // Pausing/ending playback starts a fresh idle window.
            _lastInteractionMillis.value = SystemClock.elapsedRealtime()
        }
    }
}
