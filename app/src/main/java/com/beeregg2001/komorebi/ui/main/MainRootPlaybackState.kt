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
 * The lifecycle of a playback session.  The target remains available while it
 * is prepared or switched so the root host never has to briefly render an
 * empty player between two episodes.
 */
sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    data class Preparing(val target: PlaybackTarget) : PlaybackPhase
    data class Playing(val target: PlaybackTarget) : PlaybackPhase
    data class Switching(
        val from: PlaybackTarget.Recorded,
        val to: PlaybackTarget.Recorded,
        val reason: PlaybackSwitchReason,
        val initialPositionMs: Long,
    ) : PlaybackPhase
}

/** Why a recorded-program session is changing its item without being torn down. */
enum class PlaybackSwitchReason {
    NextEpisode,
    PreviousEpisode,
    QuickSelect,
}

/** Stable identity of a root-owned playback session (and its Cast lease). */
data class PlaybackSession(val epoch: Long)

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
    var playbackPhase by mutableStateOf<PlaybackPhase>(PlaybackPhase.Idle)
        private set
    var playbackSession by mutableStateOf<PlaybackSession?>(null)
        private set
    val playbackSessionEpoch: Long? get() = playbackSession?.epoch
    var initialPlaybackPositionMs by mutableLongStateOf(0L)

    private var nextPlaybackSessionEpoch = 0L
    private var switchRollbackPositionMs = 0L

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

    val isPlaybackActive: Boolean get() = playbackPhase !is PlaybackPhase.Idle
    /**
     * The item a player host should materialize. During a handoff this is the
     * incoming recording, while [playbackTarget] remains the committed item
     * until that player is ready to commit.
     */
    val renderPlaybackTarget: PlaybackTarget
        get() = when (val phase = playbackPhase) {
            is PlaybackPhase.Preparing -> phase.target
            is PlaybackPhase.Playing -> phase.target
            is PlaybackPhase.Switching -> phase.to
            PlaybackPhase.Idle -> PlaybackTarget.None
        }
    val livePlayback: PlaybackTarget.Live? get() = playbackTarget as? PlaybackTarget.Live
    val recordedPlayback: PlaybackTarget.Recorded? get() = playbackTarget as? PlaybackTarget.Recorded
    val smbPlayback: PlaybackTarget.Smb? get() = playbackTarget as? PlaybackTarget.Smb

    fun enterLive(
        channel: Channel,
        baseballMode: Boolean = false,
        exitMiniPlayer: Boolean = true,
    ) {
        // Live playback never consumed this value, and keeping it here avoids
        // changing the legacy state contract while sessions are introduced.
        startPlayback(PlaybackTarget.Live(channel), initialPlaybackPositionMs)
        isBaseballMode = baseballMode
        lastSelectedChannelId = channel.id
        lastSelectedProgramId = null
        isReturningFromPlayer = false
        if (exitMiniPlayer) isMiniPlayerMode = false
    }

    fun enterRecorded(program: RecordedProgram, initialPositionMs: Long = 0L) {
        startPlayback(PlaybackTarget.Recorded(program), initialPositionMs)
        lastSelectedProgramId = program.id.toString()
        lastSelectedChannelId = null
        lastPlayedRecordingId = program.id
        showPlayerControls = true
        isReturningFromPlayer = false
        isMiniPlayerMode = false
    }

    fun enterSmb(item: SmbItem, initialPositionMs: Long = 0L) {
        startPlayback(PlaybackTarget.Smb(item), initialPositionMs)
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

    /**
     * Starts a recorded-item handoff inside the current session.  Until
     * [commitRecordedSwitch] succeeds, [playbackTarget] stays on [from], which
     * keeps the root player and its Cast route continuously owned.
     */
    fun beginRecordedSwitch(
        program: RecordedProgram,
        initialPositionMs: Long = 0L,
        reason: PlaybackSwitchReason,
    ): Boolean {
        val from = playbackTarget as? PlaybackTarget.Recorded ?: return false
        if (playbackPhase !is PlaybackPhase.Playing || playbackSession == null) return false

        switchRollbackPositionMs = this.initialPlaybackPositionMs
        playbackPhase = PlaybackPhase.Switching(
            from = from,
            to = PlaybackTarget.Recorded(program),
            reason = reason,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
        )
        return true
    }

    /** Applies a successful recorded-item handoff without replacing the playback session. */
    fun commitRecordedSwitch(): Boolean {
        val transition = playbackPhase as? PlaybackPhase.Switching ?: return false
        playbackTarget = transition.to
        initialPlaybackPositionMs = transition.initialPositionMs
        lastSelectedProgramId = transition.to.program.id.toString()
        lastSelectedChannelId = null
        lastPlayedRecordingId = transition.to.program.id
        showPlayerControls = true
        isReturningFromPlayer = false
        playbackPhase = PlaybackPhase.Playing(transition.to)
        return true
    }

    /** Restores the previously playing recording when its replacement cannot be prepared. */
    fun failRecordedSwitch(): Boolean {
        val transition = playbackPhase as? PlaybackPhase.Switching ?: return false
        playbackTarget = transition.from
        initialPlaybackPositionMs = switchRollbackPositionMs
        playbackPhase = PlaybackPhase.Playing(transition.from)
        return true
    }

    fun leavePlayback(returningFromPlayer: Boolean = true) {
        endPlaybackSession()
        isMiniPlayerMode = false
        showPlayerControls = true
        isReturningFromPlayer = returningFromPlayer
    }

    fun resetPlayback() {
        endPlaybackSession()
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

    private fun startPlayback(target: PlaybackTarget, initialPositionMs: Long = 0L) {
        playbackTarget = target
        this.initialPlaybackPositionMs = initialPositionMs
        if (playbackSession == null) {
            playbackSession = PlaybackSession(epoch = ++nextPlaybackSessionEpoch)
        }
        playbackPhase = PlaybackPhase.Playing(target)
    }

    private fun endPlaybackSession() {
        playbackTarget = PlaybackTarget.None
        playbackPhase = PlaybackPhase.Idle
        playbackSession = null
        switchRollbackPositionMs = 0L
    }
}
