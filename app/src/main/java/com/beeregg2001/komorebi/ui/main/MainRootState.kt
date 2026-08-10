package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.video.smb.SmbItem

enum class AiFocusTicket { NONE, PANEL_DEFAULT }

@Stable
class AiFocusTicketManager {
    var currentTicket by mutableStateOf(AiFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set

    fun issue(ticket: AiFocusTicket) {
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
    }

    fun consume(ticket: AiFocusTicket) {
        if (currentTicket == ticket) {
            currentTicket = AiFocusTicket.NONE
        }
    }
}

/**
 * MainRootScreenのすべてのUI状態(変数)を管理するState Holderクラス
 */
@Stable
class MainRootState {
    // タブ・選択状態
    var currentTabIndex by mutableIntStateOf(0)
    val playbackState = MainRootPlaybackState()

    val playbackTarget: PlaybackTarget get() = playbackState.playbackTarget
    val renderPlaybackTarget: PlaybackTarget get() = playbackState.renderPlaybackTarget
    val renderInitialPlaybackPositionMs: Long get() = playbackState.renderInitialPlaybackPositionMs
    val playbackPhase: PlaybackPhase get() = playbackState.playbackPhase
    val recordedSwitchToken: RecordedSwitchToken? get() = playbackState.recordedSwitchToken
    val playbackSession: PlaybackSession? get() = playbackState.playbackSession
    val playbackSessionEpoch: Long? get() = playbackState.playbackSessionEpoch
    var initialPlaybackPositionMs: Long
        get() = playbackState.initialPlaybackPositionMs
        set(value) { playbackState.initialPlaybackPositionMs = value }
    var epgSelectedProgram by mutableStateOf<EpgProgram?>(null)

    var backendType by mutableStateOf("KONOMITV")

    // 予約・リスト状態
    var selectedReserve by mutableStateOf<ReserveItem?>(null)
    var editingReserveItem by mutableStateOf<ReserveItem?>(null)
    var editingNewProgram by mutableStateOf<EpgProgram?>(null)
    var reserveToDelete by mutableStateOf<ReserveItem?>(null)
    var openedSeriesTitle by mutableStateOf<String?>(null)

    // 録画リストから自動予約へ進む際のターゲット番組
    var selectedProgramForAutoReserve by mutableStateOf<RecordedProgram?>(null)

    // AIコンシェルジュ
    var isAiConciergeOpen by mutableStateOf(false)
    var showAiKeyboardInput by mutableStateOf(false)
    var toastMessage by mutableStateOf<String?>(null)

    val aiTicketManager = AiFocusTicketManager()

    var aiFocusReturnTick by mutableIntStateOf(0)
    var launcherHomeFocusTick by mutableIntStateOf(0)

    // 各種オーバーレイの開閉状態
    var isEpgJumpMenuOpen by mutableStateOf(false)
    var isSettingsOpen by mutableStateOf(false)

    // ★ 追加: 設定画面を開く際のターゲット指定（ディープリンク用）
    var settingsInitialCategoryIndex by mutableIntStateOf(0)
    var settingsInitialFocusItemIndex by mutableStateOf<Int?>(null)
    var settingsOpenDeviceCapabilities by mutableStateOf(false)

    var isRecordListOpen by mutableStateOf(false)
    var isSeriesListOpen by mutableStateOf(false)
    var isSmbLibraryOpen by mutableStateOf(false)

    var showDeleteConfirmDialog by mutableStateOf(false)

    var triggerHomeBack by mutableStateOf(false)

    // Playback state is delegated to MainRootPlaybackState during the migration.
    var isPlayerMiniListOpen: Boolean
        get() = playbackState.isPlayerMiniListOpen
        set(value) { playbackState.isPlayerMiniListOpen = value }
    var playerShowOverlay: Boolean
        get() = playbackState.playerShowOverlay
        set(value) { playbackState.playerShowOverlay = value }
    var playerIsManualOverlay: Boolean
        get() = playbackState.playerIsManualOverlay
        set(value) { playbackState.playerIsManualOverlay = value }
    var playerIsPinnedOverlay: Boolean
        get() = playbackState.playerIsPinnedOverlay
        set(value) { playbackState.playerIsPinnedOverlay = value }
    var playerIsSubMenuOpen: Boolean
        get() = playbackState.playerIsSubMenuOpen
        set(value) { playbackState.playerIsSubMenuOpen = value }
    var showPlayerControls: Boolean
        get() = playbackState.showPlayerControls
        set(value) { playbackState.showPlayerControls = value }
    var isPlayerSubMenuOpen: Boolean
        get() = playbackState.isPlayerSubMenuOpen
        set(value) { playbackState.isPlayerSubMenuOpen = value }
    var isPlayerSceneSearchOpen: Boolean
        get() = playbackState.isPlayerSceneSearchOpen
        set(value) { playbackState.isPlayerSceneSearchOpen = value }
    var isMiniPlayerMode: Boolean
        get() = playbackState.isMiniPlayerMode
        set(value) { playbackState.isMiniPlayerMode = value }
    var lastSelectedChannelId: String?
        get() = playbackState.lastSelectedChannelId
        set(value) { playbackState.lastSelectedChannelId = value }
    var lastSelectedProgramId: String?
        get() = playbackState.lastSelectedProgramId
        set(value) { playbackState.lastSelectedProgramId = value }
    var isReturningFromPlayer: Boolean
        get() = playbackState.isReturningFromPlayer
        set(value) { playbackState.isReturningFromPlayer = value }
    var lastPlayedRecordingId: Int?
        get() = playbackState.lastPlayedRecordingId
        set(value) { playbackState.lastPlayedRecordingId = value }
    var lastPlayedSmbPath: String?
        get() = playbackState.lastPlayedSmbPath
        set(value) { playbackState.lastPlayedSmbPath = value }

    // システム状態
    var isDataReady by mutableStateOf(false)
    var isUiReady by mutableStateOf(false)
    var isSplashFinished by mutableStateOf(false)
    var showConnectionErrorDialog by mutableStateOf(false)
    var isOfflineMode by mutableStateOf(false)
    var hasAppliedStartupTab by mutableStateOf(false)

    var hasAppliedStartupChannel by mutableStateOf(false)

    var editingCondition by mutableStateOf<ReservationCondition?>(null)
    var selectedConditionReserveItem by mutableStateOf<ReserveItem?>(null)

    val isPlaybackActive: Boolean get() = playbackState.isPlaybackActive
    val livePlayback: PlaybackTarget.Live? get() = playbackState.livePlayback
    val recordedPlayback: PlaybackTarget.Recorded? get() = playbackState.recordedPlayback
    val smbPlayback: PlaybackTarget.Smb? get() = playbackState.smbPlayback

    fun enterLive(
        channel: Channel,
        exitMiniPlayer: Boolean = true
    ) {
        playbackState.enterLive(channel, exitMiniPlayer)
    }

    fun enterRecorded(program: RecordedProgram, initialPositionMs: Long = 0L) {
        playbackState.enterRecorded(program, initialPositionMs)
    }

    fun enterSmb(item: SmbItem, initialPositionMs: Long = 0L) {
        playbackState.enterSmb(item, initialPositionMs)
    }

    fun beginRecordedSwitch(
        program: RecordedProgram,
        initialPositionMs: Long = 0L,
        reason: PlaybackSwitchReason,
    ): Boolean = playbackState.beginRecordedSwitch(program, initialPositionMs, reason)

    fun commitRecordedSwitch(token: RecordedSwitchToken): Boolean = playbackState.commitRecordedSwitch(token)

    fun failRecordedSwitch(token: RecordedSwitchToken): Boolean = playbackState.failRecordedSwitch(token)

    fun enterMiniPlayer(): Boolean {
        return playbackState.enterMiniPlayer()
    }

    fun exitMiniPlayer() {
        playbackState.exitMiniPlayer()
    }

    fun leavePlayback(returningFromPlayer: Boolean = true) {
        playbackState.leavePlayback(returningFromPlayer)
    }

    /** Clears playback-only state when the system Home intent wins. */
    fun resetPlayback() {
        playbackState.resetPlayback()
    }

    /** The playback portion of a system Home reset. Root destinations are reset by their owner. */
    fun resetForLauncherHome() {
        resetPlayback()
        launcherHomeFocusTick++
        triggerHomeBack = false
    }

    // 戻るボタンの連打ガード
    private var lastBackPressTime by mutableLongStateOf(0L)
    fun canProcessBackPress(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 500) return false
        lastBackPressTime = currentTime
        return true
    }

    fun isFullScreen(
        epgProgram: EpgProgram?,
        settingsOpen: Boolean,
        recordListOpen: Boolean,
        reserveOverlayOpen: Boolean
    ): Boolean {
        if (isMiniPlayerMode) return false

        return isPlaybackActive || epgProgram != null ||
                settingsOpen || recordListOpen || reserveOverlayOpen ||
                isSeriesListOpen || isAiConciergeOpen || isSmbLibraryOpen ||
                editingCondition != null || selectedConditionReserveItem != null ||
                selectedReserve != null || editingReserveItem != null ||
                editingNewProgram != null || reserveToDelete != null ||
                selectedProgramForAutoReserve != null
    }

    fun getVisibleTabs(): List<String> {
        return listOf("ホーム", "ライブ", "アプリ", "ビデオ", "番組表", "録画予約")
    }
}

@Composable
fun rememberMainRootState(): MainRootState {
    return remember { MainRootState() }
}
