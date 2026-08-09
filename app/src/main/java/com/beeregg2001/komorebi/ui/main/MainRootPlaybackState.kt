package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.smb.SmbItem

/**
 * The single source of truth for the active player. Keeping the payload with
 * its kind makes an impossible combination such as live + SMB unrepresentable.
 */
sealed interface PlaybackTarget {
    data object None : PlaybackTarget
    data class Live(val channel: Channel) : PlaybackTarget
    data class Recorded(val program: RecordedProgram) : PlaybackTarget
    data class Smb(val item: SmbItem) : PlaybackTarget
}

/**
 * State and transitions that belong to playback, rather than to the launcher.
 *
 * [MainRootState] exposes this holder through compatibility delegates while the
 * UI is gradually split into a playback host.
 */
@Stable
class MainRootPlaybackState {
    var playbackTarget by mutableStateOf<PlaybackTarget>(PlaybackTarget.None)
        private set
    var initialPlaybackPositionMs by mutableLongStateOf(0L)

    // Player overlays
    var isPlayerMiniListOpen by mutableStateOf(false)
    var playerShowOverlay by mutableStateOf(true)
    var playerIsManualOverlay by mutableStateOf(false)
    var playerIsPinnedOverlay by mutableStateOf(false)
    var playerIsSubMenuOpen by mutableStateOf(false)
    var showPlayerControls by mutableStateOf(true)
    var isPlayerSubMenuOpen by mutableStateOf(false)
    var isPlayerSceneSearchOpen by mutableStateOf(false)
    var isMiniPlayerMode by mutableStateOf(false)

    // Playback return and restoration state
    var lastSelectedChannelId by mutableStateOf<String?>(null)
    var lastSelectedProgramId by mutableStateOf<String?>(null)
    var isReturningFromPlayer by mutableStateOf(false)
    var isBaseballMode by mutableStateOf(false)
    var lastPlayedRecordingId by mutableStateOf<Int?>(null)
    var lastPlayedSmbPath by mutableStateOf<String?>(null)

    val isPlaybackActive: Boolean get() = playbackTarget !is PlaybackTarget.None
    val livePlayback: PlaybackTarget.Live? get() = playbackTarget as? PlaybackTarget.Live
    val recordedPlayback: PlaybackTarget.Recorded? get() = playbackTarget as? PlaybackTarget.Recorded
    val smbPlayback: PlaybackTarget.Smb? get() = playbackTarget as? PlaybackTarget.Smb

    fun enterLive(
        channel: Channel,
        baseballMode: Boolean = false,
        exitMiniPlayer: Boolean = true,
    ) {
        playbackTarget = PlaybackTarget.Live(channel)
        isBaseballMode = baseballMode
        lastSelectedChannelId = channel.id
        lastSelectedProgramId = null
        isReturningFromPlayer = false
        if (exitMiniPlayer) isMiniPlayerMode = false
    }

    fun enterRecorded(program: RecordedProgram, initialPositionMs: Long = 0L) {
        playbackTarget = PlaybackTarget.Recorded(program)
        initialPlaybackPositionMs = initialPositionMs
        lastSelectedProgramId = program.id.toString()
        lastSelectedChannelId = null
        lastPlayedRecordingId = program.id
        showPlayerControls = true
        isReturningFromPlayer = false
        isMiniPlayerMode = false
    }

    fun enterSmb(item: SmbItem, initialPositionMs: Long = 0L) {
        playbackTarget = PlaybackTarget.Smb(item)
        initialPlaybackPositionMs = initialPositionMs
        lastPlayedSmbPath = item.path
        showPlayerControls = true
        isReturningFromPlayer = false
        isMiniPlayerMode = false
    }

    fun enterMiniPlayer(): Boolean {
        if (!isPlaybackActive) return false
        isMiniPlayerMode = true
        return true
    }

    fun exitMiniPlayer() {
        isMiniPlayerMode = false
    }

    fun leavePlayback(returningFromPlayer: Boolean = true) {
        playbackTarget = PlaybackTarget.None
        isMiniPlayerMode = false
        showPlayerControls = true
        isReturningFromPlayer = returningFromPlayer
    }

    fun resetPlayback() {
        playbackTarget = PlaybackTarget.None
        initialPlaybackPositionMs = 0L
        isMiniPlayerMode = false
        isPlayerMiniListOpen = false
        playerShowOverlay = false
        playerIsManualOverlay = false
        playerIsPinnedOverlay = false
        playerIsSubMenuOpen = false
        isPlayerSubMenuOpen = false
        isPlayerSceneSearchOpen = false
        showPlayerControls = true
        isReturningFromPlayer = false
        isBaseballMode = false
    }
}
