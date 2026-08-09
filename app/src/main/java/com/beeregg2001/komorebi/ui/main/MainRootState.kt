package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.video.smb.SmbItem

enum class AiFocusTicket { NONE, PANEL_DEFAULT }

/**
 * The single source of truth for the active player.  Keeping the payload with
 * its kind makes an impossible combination such as live + SMB unrepresentable.
 */
sealed interface PlaybackTarget {
    data object None : PlaybackTarget
    data class Live(val channel: Channel) : PlaybackTarget
    data class Recorded(val program: RecordedProgram) : PlaybackTarget
    data class Smb(val item: SmbItem) : PlaybackTarget
}

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
    var playbackTarget by mutableStateOf<PlaybackTarget>(PlaybackTarget.None)
        private set
    var initialPlaybackPositionMs by mutableLongStateOf(0L)
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

    // プレイヤー固有の状態
    var isPlayerMiniListOpen by mutableStateOf(false)
    var playerShowOverlay by mutableStateOf(true)
    var playerIsManualOverlay by mutableStateOf(false)
    var playerIsPinnedOverlay by mutableStateOf(false)
    var playerIsSubMenuOpen by mutableStateOf(false)
    var showPlayerControls by mutableStateOf(true)
    var isPlayerSubMenuOpen by mutableStateOf(false)
    var isPlayerSceneSearchOpen by mutableStateOf(false)

    // アプリ内ミニプレイヤー（PiP）のフラグ
    var isMiniPlayerMode by mutableStateOf(false)

    // 履歴・復帰状態
    var lastSelectedChannelId by mutableStateOf<String?>(null)
    var lastSelectedProgramId by mutableStateOf<String?>(null)
    var isReturningFromPlayer by mutableStateOf(false)

    // プロ野球特化モードのフラグ
    var isBaseballMode by mutableStateOf(false)

    // 再生から戻った際にフォーカスすべき録画番組のID
    var lastPlayedRecordingId by mutableStateOf<Int?>(null)

    // 再生から戻った際にフォーカスすべきSMBファイルのパス
    var lastPlayedSmbPath by mutableStateOf<String?>(null)

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

    val isPlaybackActive: Boolean get() = playbackTarget !is PlaybackTarget.None
    val livePlayback: PlaybackTarget.Live? get() = playbackTarget as? PlaybackTarget.Live
    val recordedPlayback: PlaybackTarget.Recorded? get() = playbackTarget as? PlaybackTarget.Recorded
    val smbPlayback: PlaybackTarget.Smb? get() = playbackTarget as? PlaybackTarget.Smb

    fun enterLive(
        channel: Channel,
        baseballMode: Boolean = false,
        exitMiniPlayer: Boolean = true
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

    fun leavePlayback(returningFromPlayer: Boolean = true) {
        playbackTarget = PlaybackTarget.None
        isMiniPlayerMode = false
        showPlayerControls = true
        isReturningFromPlayer = returningFromPlayer
    }

    /** Clears playback-only state when the system Home intent wins. */
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
