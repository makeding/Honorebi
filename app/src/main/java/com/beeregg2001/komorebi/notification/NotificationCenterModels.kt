package com.beeregg2001.komorebi.notification

import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.runtime.Immutable

const val ERROR_CODE_NOTIFICATION_ACCESS_REQUIRED = "NOTIFICATION_ACCESS_REQUIRED"
const val ERROR_CODE_NOTIFICATION_SETTINGS_UNAVAILABLE = "NOTIFICATION_SETTINGS_UNAVAILABLE"

enum class NotificationAccessStatus {
    GRANTED,
    REQUIRED,
    SETTINGS_UNAVAILABLE,
}

enum class NotificationSettingsOpenResult {
    OPENED_LISTENER_SETTINGS,
    OPENED_SYSTEM_SETTINGS,
    UNAVAILABLE,
}

enum class MediaTransportAction {
    PLAY,
    PAUSE,
    PLAY_PAUSE,
    SKIP_TO_NEXT,
    SKIP_TO_PREVIOUS,
    STOP,
}

@Immutable
data class SystemNotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTimeMs: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent? = null,
    val appIcon: Bitmap? = null,
)

@Immutable
data class ExternalMediaSessionState(
    val sessionToken: MediaSession.Token? = null,
    val sessionKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val subtitle: String,
    val playbackState: Int,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isActiveForStrip: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float,
    val lastPositionUpdateTimeMs: Long,
    val canPlay: Boolean,
    val canPause: Boolean,
    val canSkipToNext: Boolean,
    val canSkipToPrevious: Boolean,
    val canStop: Boolean,
    val artwork: Bitmap? = null,
    val artworkUri: String? = null,
    val sessionActivity: PendingIntent? = null,
) {
    fun computeLivePositionMs(nowElapsedRealtimeMs: Long): Long =
        computeLivePlaybackPositionMs(
            positionMs = positionMs,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            lastUpdateElapsedMs = lastPositionUpdateTimeMs,
            nowElapsedMs = nowElapsedRealtimeMs,
            isPlaying = isPlaying,
        )
}

@Immutable
data class InternalNowPlayingState(
    val title: String,
    val subtitle: String = "",
    val badgeLabel: String = "再生中",
    val isPlaying: Boolean = true,
)

@Immutable
sealed interface LauncherMediaPillState {
    data object None : LauncherMediaPillState

    data class InternalMiniPlayer(
        val label: String = "再生中",
    ) : LauncherMediaPillState

    data class ExternalMedia(
        val sessionKey: String,
        val packageName: String,
        val title: String,
        val appLabel: String,
        val isPlaying: Boolean,
    ) : LauncherMediaPillState {
        val displayLabel: String
            get() = title.ifBlank { appLabel }
    }
}

@Immutable
data class NotificationCenterUiState(
    val accessStatus: NotificationAccessStatus = NotificationAccessStatus.REQUIRED,
    val isAccessGranted: Boolean = false,
    val notifications: List<SystemNotificationItem> = emptyList(),
    val externalMediaSessions: List<ExternalMediaSessionState> = emptyList(),
    val adbGrantCommand: String = "",
) {
    val notificationCount: Int
        get() = notifications.size

    val clearableCount: Int
        get() = notifications.count { it.isClearable }

    val activeExternalMedia: ExternalMediaSessionState?
        get() = externalMediaSessions.firstOrNull { it.isActiveForStrip }

    fun resolveMediaPillState(internalNowPlaying: InternalNowPlayingState?): LauncherMediaPillState =
        resolveLauncherMediaPillState(internalNowPlaying, externalMediaSessions)
}

fun resolveLauncherMediaPillState(
    internalNowPlaying: InternalNowPlayingState?,
    externalSessions: List<ExternalMediaSessionState>,
): LauncherMediaPillState {
    if (internalNowPlaying != null) {
        return LauncherMediaPillState.InternalMiniPlayer(
            label = internalNowPlaying.badgeLabel.ifBlank { "再生中" },
        )
    }
    val activeExternal = externalSessions.firstOrNull { it.isActiveForStrip }
    if (activeExternal != null) {
        return LauncherMediaPillState.ExternalMedia(
            sessionKey = activeExternal.sessionKey,
            packageName = activeExternal.packageName,
            title = activeExternal.title,
            appLabel = activeExternal.appLabel,
            isPlaying = activeExternal.isPlaying,
        )
    }
    return LauncherMediaPillState.None
}

internal fun isPlaybackStateActiveForStrip(state: Int): Boolean =
    state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

internal fun computeLivePlaybackPositionMs(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    lastUpdateElapsedMs: Long,
    nowElapsedMs: Long,
    isPlaying: Boolean,
): Long {
    val safePosition = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) {
        if (!isPlaying) return safePosition
        val deltaMs = (nowElapsedMs - lastUpdateElapsedMs).coerceAtLeast(0L)
        val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
        return (safePosition + (deltaMs * speed).toLong()).coerceAtLeast(0L)
    }
    if (!isPlaying) return safePosition.coerceIn(0L, durationMs)
    val deltaMs = (nowElapsedMs - lastUpdateElapsedMs).coerceAtLeast(0L)
    val speed = if (playbackSpeed > 0f) playbackSpeed else 1f
    val projected = safePosition + (deltaMs * speed).toLong()
    return projected.coerceIn(0L, durationMs)
}
