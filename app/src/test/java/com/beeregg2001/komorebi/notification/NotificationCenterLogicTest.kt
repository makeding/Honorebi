package com.beeregg2001.komorebi.notification

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterLogicTest {

    @Test
    fun filterAndSortNotifications_excludesOwnPackageSummaryWithChildrenAndMediaTransport() {
        val records = listOf(
            rawRecord(key = "own", packageName = "com.beeregg2001.Honorebi", postTimeMs = 500L),
            rawRecord(
                key = "group-summary",
                packageName = "com.example.mail",
                groupKey = "mail-group",
                isGroupSummary = true,
                postTimeMs = 450L,
            ),
            rawRecord(
                key = "group-child-1",
                packageName = "com.example.mail",
                groupKey = "mail-group",
                isGroupSummary = false,
                postTimeMs = 400L,
            ),
            rawRecord(
                key = "orphan-summary",
                packageName = "com.example.chat",
                groupKey = "chat-group",
                isGroupSummary = true,
                postTimeMs = 350L,
            ),
            rawRecord(
                key = "media-extra",
                packageName = "com.spotify.tv.android",
                hasMediaSessionExtra = true,
                postTimeMs = 600L,
            ),
            rawRecord(
                key = "transport-active",
                packageName = "com.google.android.youtube.tv",
                isTransportCategory = true,
                postTimeMs = 550L,
            ),
            rawRecord(
                key = "transport-inactive",
                packageName = "com.example.podcast",
                isTransportCategory = true,
                postTimeMs = 200L,
            ),
        )

        val filtered = filterAndSortNotifications(
            records = records,
            ownPackageName = "com.beeregg2001.Honorebi",
            activeMediaPackages = setOf("com.google.android.youtube.tv"),
        )

        assertEquals(
            listOf("group-child-1", "orphan-summary", "transport-inactive"),
            filtered.map { it.key },
        )
    }

    @Test
    fun mapPlaybackActions_mapsBitmaskCorrectly() {
        val full = mapPlaybackActions(
            PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP,
        )
        assertTrue(full.canPlay)
        assertTrue(full.canPause)
        assertTrue(full.canSkipToNext)
        assertTrue(full.canSkipToPrevious)
        assertTrue(full.canStop)

        val playOnly = mapPlaybackActions(PlaybackState.ACTION_PLAY)
        assertTrue(playOnly.canPlay)
        assertFalse(playOnly.canPause)
        assertFalse(playOnly.canSkipToNext)
        assertFalse(playOnly.canSkipToPrevious)
        assertFalse(playOnly.canStop)

        val none = mapPlaybackActions(0L)
        assertFalse(none.canPlay)
        assertFalse(none.canPause)
        assertFalse(none.canSkipToNext)
        assertFalse(none.canSkipToPrevious)
        assertFalse(none.canStop)
    }

    @Test
    fun computeLivePlaybackPositionMs_advancesWhenPlayingAndClampsToDuration() {
        assertEquals(
            15_000L,
            computeLivePlaybackPositionMs(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                isPlaying = true,
            ),
        )

        assertEquals(
            20_000L,
            computeLivePlaybackPositionMs(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackSpeed = 2.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 6_000L,
                isPlaying = true,
            ),
        )

        // Clamps to duration
        assertEquals(
            60_000L,
            computeLivePlaybackPositionMs(
                positionMs = 55_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 20_000L,
                isPlaying = true,
            ),
        )

        // Paused does not advance
        assertEquals(
            10_000L,
            computeLivePlaybackPositionMs(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackSpeed = 1.0f,
                lastUpdateElapsedMs = 1_000L,
                nowElapsedMs = 20_000L,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun resolveLauncherMediaPillState_prioritizesInternalOverExternalAndIgnoresPaused() {
        val playingExternal = mediaSession(
            sessionKey = "yt",
            packageName = "com.google.android.youtube.tv",
            appLabel = "YouTube",
            title = "Lo-Fi Radio",
            playbackState = PlaybackState.STATE_PLAYING,
        )
        val bufferingExternal = mediaSession(
            sessionKey = "sp",
            packageName = "com.spotify.tv.android",
            appLabel = "Spotify",
            title = "",
            playbackState = PlaybackState.STATE_BUFFERING,
        )
        val pausedExternal = mediaSession(
            sessionKey = "nf",
            packageName = "com.netflix.ninja",
            appLabel = "Netflix",
            title = "Episode 1",
            playbackState = PlaybackState.STATE_PAUSED,
        )

        // Internal MiniPlayer always wins
        val internalWins = resolveLauncherMediaPillState(
            internalNowPlaying = InternalNowPlayingState(title = "NHKニュース", badgeLabel = "再生中"),
            externalSessions = listOf(playingExternal),
        )
        assertEquals(LauncherMediaPillState.InternalMiniPlayer("再生中"), internalWins)

        // Active playing external wins when no internal player
        val externalPlaying = resolveLauncherMediaPillState(
            internalNowPlaying = null,
            externalSessions = listOf(pausedExternal, playingExternal),
        )
        assertEquals(
            LauncherMediaPillState.ExternalMedia(
                sessionKey = "yt",
                packageName = "com.google.android.youtube.tv",
                title = "Lo-Fi Radio",
                appLabel = "YouTube",
                isPlaying = true,
            ),
            externalPlaying,
        )
        assertEquals("Lo-Fi Radio", (externalPlaying as LauncherMediaPillState.ExternalMedia).displayLabel)

        // Buffering external is also shown; blank title falls back to appLabel
        val externalBuffering = resolveLauncherMediaPillState(
            internalNowPlaying = null,
            externalSessions = listOf(bufferingExternal),
        )
        assertEquals("Spotify", (externalBuffering as LauncherMediaPillState.ExternalMedia).displayLabel)

        // Paused-only external does not appear in top strip
        val pausedOnly = resolveLauncherMediaPillState(
            internalNowPlaying = null,
            externalSessions = listOf(pausedExternal),
        )
        assertEquals(LauncherMediaPillState.None, pausedOnly)
    }

    private fun rawRecord(
        key: String,
        packageName: String,
        groupKey: String? = null,
        isGroupSummary: Boolean = false,
        hasMediaSessionExtra: Boolean = false,
        isTransportCategory: Boolean = false,
        postTimeMs: Long = 100L,
    ) = RawNotificationRecord(
        key = key,
        packageName = packageName,
        groupKey = groupKey,
        isGroupSummary = isGroupSummary,
        hasMediaSessionExtra = hasMediaSessionExtra,
        isTransportCategory = isTransportCategory,
        appLabel = packageName,
        title = "Title $key",
        text = "Body $key",
        postTimeMs = postTimeMs,
        isClearable = true,
    )

    private fun mediaSession(
        sessionKey: String,
        packageName: String,
        appLabel: String,
        title: String,
        playbackState: Int,
    ) = ExternalMediaSessionState(
        sessionToken = null,
        sessionKey = sessionKey,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        subtitle = appLabel,
        playbackState = playbackState,
        isPlaying = playbackState == PlaybackState.STATE_PLAYING,
        isBuffering = playbackState == PlaybackState.STATE_BUFFERING,
        isActiveForStrip = isPlaybackStateActiveForStrip(playbackState),
        positionMs = 0L,
        durationMs = 120_000L,
        playbackSpeed = 1f,
        lastPositionUpdateTimeMs = 0L,
        canPlay = true,
        canPause = true,
        canSkipToNext = true,
        canSkipToPrevious = true,
        canStop = false,
    )
}
