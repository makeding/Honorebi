package com.beeregg2001.komorebi.ui.player

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
        val token: RecordedPlaybackToken,
    ) : PlaybackPhase
}

/** Why a recorded-program session is changing its item without being torn down. */
enum class PlaybackSwitchReason {
    NextEpisode,
    PreviousEpisode,
    QuickSelect,
    RemoteOpen,
}

/** Stable identity of a root-owned playback session (and its Cast lease). */
data class PlaybackSession(val epoch: Long)

/** Identifies one concrete A -> B attempt inside a root playback session. */
data class RecordedPlaybackToken(
    val sessionEpoch: Long,
    val attemptId: Long,
    val programId: Int,
)

/** Identifies a latest-wins remote playback open request. */
data class PlaybackOpenIntentToken(val id: Long)

enum class PlaybackBackResult { Ignored, Handled, RestoredFullscreen }

/**
 * State and transitions that belong to playback, rather than to the launcher.
 *
 * Navigation sends requests directly to this holder; player surfaces own its controls.
 */
@Stable
class PlaybackSessionState {
    var playbackTarget by mutableStateOf<PlaybackTarget>(PlaybackTarget.None)
        private set
    var playbackPhase by mutableStateOf<PlaybackPhase>(PlaybackPhase.Idle)
        private set
    var playbackSession by mutableStateOf<PlaybackSession?>(null)
        private set
    val playbackSessionEpoch: Long? get() = playbackSession?.epoch
    var initialPlaybackPositionMs by mutableLongStateOf(0L)

    private var nextPlaybackSessionEpoch = 0L
    private var nextRecordedPlaybackAttemptId = 0L
    private var switchRollbackPositionMs = 0L
    @Volatile private var activeRecordedPlaybackToken: RecordedPlaybackToken? = null
    @Volatile private var currentPlaybackOpenIntent: PlaybackOpenIntentToken? = null
    private var nextPlaybackOpenIntentId = 0L

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
    val renderInitialPlaybackPositionMs: Long
        get() = (playbackPhase as? PlaybackPhase.Switching)?.initialPositionMs
            ?: initialPlaybackPositionMs
    val recordedPlaybackToken: RecordedPlaybackToken
        get() = requireNotNull(activeRecordedPlaybackToken) { "Recorded playback must have a token" }
    val livePlayback: PlaybackTarget.Live? get() = playbackTarget as? PlaybackTarget.Live
    val recordedPlayback: PlaybackTarget.Recorded? get() = playbackTarget as? PlaybackTarget.Recorded
    val smbPlayback: PlaybackTarget.Smb? get() = playbackTarget as? PlaybackTarget.Smb

    fun enterLive(
        channel: Channel,
        exitMiniPlayer: Boolean = true,
    ) {
        invalidatePlaybackOpenIntents()
        startPlayback(PlaybackTarget.Live(channel), initialPlaybackPositionMs)
        lastSelectedChannelId = channel.id
        lastSelectedProgramId = null
        isReturningFromPlayer = false
        if (exitMiniPlayer) isMiniPlayerMode = false
    }

    fun enterRecorded(program: RecordedProgram, initialPositionMs: Long = 0L) {
        invalidatePlaybackOpenIntents()
        startPlayback(PlaybackTarget.Recorded(program), initialPositionMs)
        lastSelectedProgramId = program.id.toString()
        lastSelectedChannelId = null
        lastPlayedRecordingId = program.id
        showPlayerControls = true
        isReturningFromPlayer = false
        isMiniPlayerMode = false
    }

    fun enterSmb(item: SmbItem, initialPositionMs: Long = 0L) {
        invalidatePlaybackOpenIntents()
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

    /** One ordered back action for every playback surface. */
    fun handleBack(): PlaybackBackResult {
        if (!isPlaybackActive) return PlaybackBackResult.Ignored
        when {
            isMiniPlayerMode -> {
                exitMiniPlayer()
                return PlaybackBackResult.RestoredFullscreen
            }
            isPlayerMiniListOpen -> isPlayerMiniListOpen = false
            playerIsSubMenuOpen -> playerIsSubMenuOpen = false
            livePlayback != null && (playerShowOverlay || playerIsPinnedOverlay) -> {
                playerShowOverlay = false
                playerIsManualOverlay = false
                playerIsPinnedOverlay = false
            }
            isPlayerSubMenuOpen -> isPlayerSubMenuOpen = false
            isPlayerSceneSearchOpen -> {
                isPlayerSceneSearchOpen = false
                showPlayerControls = false
            }
            else -> leavePlayback()
        }
        return PlaybackBackResult.Handled
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
        invalidatePlaybackOpenIntents()
        val from = playbackTarget as? PlaybackTarget.Recorded ?: return false
        if (playbackPhase !is PlaybackPhase.Playing && playbackPhase !is PlaybackPhase.Switching) return false
        if (playbackSession == null) return false
        if (from.program.id == program.id && reason != PlaybackSwitchReason.RemoteOpen) return false

        if (playbackPhase !is PlaybackPhase.Switching) switchRollbackPositionMs = this.initialPlaybackPositionMs
        val token = newRecordedPlaybackToken(program.id)
        activeRecordedPlaybackToken = token
        playbackPhase = PlaybackPhase.Switching(
            from = from,
            to = PlaybackTarget.Recorded(program),
            reason = reason,
            initialPositionMs = initialPositionMs.coerceAtLeast(0L),
            token = token,
        )
        return true
    }

    /** Applies a successful recorded-item handoff without replacing the playback session. */
    fun commitRecordedSwitch(token: RecordedPlaybackToken): Boolean {
        val transition = playbackPhase as? PlaybackPhase.Switching ?: return false
        if (transition.token != token) return false
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
    fun failRecordedSwitch(token: RecordedPlaybackToken): Boolean {
        val transition = playbackPhase as? PlaybackPhase.Switching ?: return false
        if (transition.token != token) return false
        activeRecordedPlaybackToken = newRecordedPlaybackToken(transition.from.program.id)
        playbackTarget = transition.from
        initialPlaybackPositionMs = switchRollbackPositionMs
        playbackPhase = PlaybackPhase.Playing(transition.from)
        return true
    }

    fun leavePlayback(returningFromPlayer: Boolean = true) {
        invalidatePlaybackOpenIntents()
        endPlaybackSession()
        isMiniPlayerMode = false
        showPlayerControls = true
        isReturningFromPlayer = returningFromPlayer
    }

    fun resetPlayback() {
        invalidatePlaybackOpenIntents()
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
    }

    /** The token fence is always published before UI state exposes its target. */
    private fun startPlayback(target: PlaybackTarget, initialPositionMs: Long = 0L) {
        if (playbackSession == null) {
            playbackSession = PlaybackSession(epoch = ++nextPlaybackSessionEpoch)
        }
        activeRecordedPlaybackToken = (target as? PlaybackTarget.Recorded)
            ?.let { newRecordedPlaybackToken(it.program.id) }
        playbackTarget = target
        this.initialPlaybackPositionMs = initialPositionMs
        playbackPhase = PlaybackPhase.Playing(target)
    }

    @Synchronized
    fun beginPlaybackOpenIntent(): PlaybackOpenIntentToken {
        return PlaybackOpenIntentToken(++nextPlaybackOpenIntentId).also { currentPlaybackOpenIntent = it }
    }

    @Synchronized
    fun completePlaybackOpenIntent(token: PlaybackOpenIntentToken): Boolean {
        if (currentPlaybackOpenIntent != token) return false
        currentPlaybackOpenIntent = null
        return true
    }

    fun failPlaybackOpenIntent(token: PlaybackOpenIntentToken): Boolean = completePlaybackOpenIntent(token)

    fun isCurrentPlaybackOpenIntent(token: PlaybackOpenIntentToken): Boolean = currentPlaybackOpenIntent == token

    fun isCurrentRecordedPlayback(token: RecordedPlaybackToken): Boolean {
        return activeRecordedPlaybackToken == token
    }

    @Synchronized
    private fun invalidatePlaybackOpenIntents() {
        currentPlaybackOpenIntent = null
    }

    private fun newRecordedPlaybackToken(programId: Int): RecordedPlaybackToken = RecordedPlaybackToken(
        sessionEpoch = requireNotNull(playbackSession).epoch,
        attemptId = ++nextRecordedPlaybackAttemptId,
        programId = programId,
    )

    private fun endPlaybackSession() {
        activeRecordedPlaybackToken = null
        playbackTarget = PlaybackTarget.None
        playbackPhase = PlaybackPhase.Idle
        playbackSession = null
        switchRollbackPositionMs = 0L
    }
}
