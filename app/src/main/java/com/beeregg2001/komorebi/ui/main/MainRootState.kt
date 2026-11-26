package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.ui.player.PlaybackSessionState
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.viewmodel.StartupSettingsSnapshot

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
    val playbackState = PlaybackSessionState()

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
    var handledLauncherHomeFocusTick by mutableIntStateOf(0)
        private set

    val hasPendingLauncherHomeFocus: Boolean
        get() = launcherHomeFocusTick > handledLauncherHomeFocusTick

    fun consumeLauncherHomeFocus(tick: Int) {
        if (tick > handledLauncherHomeFocusTick) {
            handledLauncherHomeFocusTick = tick
        }
    }
    var overlayReturnFocusTick by mutableIntStateOf(0)

    // 各種オーバーレイの開閉状態
    var isEpgJumpMenuOpen by mutableStateOf(false)
    var isSettingsOpen by mutableStateOf(false)

    // ★ 追加: 設定画面を開く際のターゲット指定（ディープリンク用）
    var settingsInitialCategoryIndex by mutableIntStateOf(0)
    var settingsInitialFocusItemIndex by mutableStateOf<Int?>(null)
    var settingsOpenDeviceCapabilities by mutableStateOf(false)
    var remotePlaybackFocusTick by mutableIntStateOf(0)
        private set

    var isRecordListOpen by mutableStateOf(false)
    var isSeriesListOpen by mutableStateOf(false)
    var isSmbLibraryOpen by mutableStateOf(false)
    var isOnAirOpen by mutableStateOf(false)

    fun closeOnAir() {
        if (isOnAirOpen) {
            isOnAirOpen = false
            overlayReturnFocusTick++
        }
    }

    var showDeleteConfirmDialog by mutableStateOf(false)

    var triggerHomeBack by mutableStateOf(false)

    // システム状態
    var isDataReady by mutableStateOf(false)
    var isUiReady by mutableStateOf(false)
    var isSplashFinished by mutableStateOf(false)
    var showConnectionErrorDialog by mutableStateOf(false)
    var isOfflineMode by mutableStateOf(false)
    var isConnectionRetryPending by mutableStateOf(false)
    var hasAppliedStartupTab by mutableStateOf(false)
    var startupSettings by mutableStateOf<StartupSettingsSnapshot?>(null)

    var hasAppliedStartupChannel by mutableStateOf(false)

    var editingCondition by mutableStateOf<ReservationCondition?>(null)
    var selectedConditionReserveItem by mutableStateOf<ReserveItem?>(null)

    /** Closes Settings without tearing down the mounted Home hierarchy or resetting the active tab. */
    fun closeSettings() {
        isSettingsOpen = false
        settingsInitialCategoryIndex = 0
        settingsInitialFocusItemIndex = null
        settingsOpenDeviceCapabilities = false
    }

    /** Called only after the latest remote open has resolved to an allowed target. */
    fun bringRemotePlaybackToFront() {
        closeSettings()
        playbackState.exitMiniPlayer()
        remotePlaybackFocusTick++
    }

    /** The playback portion of a system Home reset. Root destinations are reset by their owner. */
    fun resetForLauncherHome(startupTab: String = "ホーム", hideAppsTab: Boolean = false) {
        currentTabIndex = getVisibleTabs(hideAppsTab).indexOf(startupTab).coerceAtLeast(0)
        playbackState.resetPlayback()
        launcherHomeFocusTick++
        triggerHomeBack = false
    }

    /** True when the user is currently inside a player, settings, sub-screen, or overlay above the launcher tabs. */
    fun hasActiveModuleOrOverlay(): Boolean =
        playbackState.isPlaybackActive ||
            isSettingsOpen ||
            isRecordListOpen ||
            isSeriesListOpen ||
            isSmbLibraryOpen ||
            isOnAirOpen ||
            epgSelectedProgram != null ||
            selectedReserve != null ||
            editingReserveItem != null ||
            editingNewProgram != null ||
            reserveToDelete != null ||
            editingCondition != null ||
            selectedConditionReserveItem != null ||
            selectedProgramForAutoReserve != null ||
            isAiConciergeOpen ||
            isEpgJumpMenuOpen

    /** Returns from an active module (player, settings, overlay) to the launcher while preserving the current tab. */
    fun returnToCurrentTabFromModule(hideAppsTab: Boolean = false) {
        val visibleCount = getVisibleTabs(hideAppsTab).size
        currentTabIndex = if (visibleCount > 0) currentTabIndex.coerceIn(0, visibleCount - 1) else 0
        val wasPlaybackActive = playbackState.isPlaybackActive
        playbackState.resetPlayback(returningFromPlayer = wasPlaybackActive)
        if (!wasPlaybackActive) {
            launcherHomeFocusTick++
        }
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
        if (playbackState.isMiniPlayerMode) return false

        return playbackState.isPlaybackActive || epgProgram != null ||
                settingsOpen || recordListOpen || reserveOverlayOpen ||
                isSeriesListOpen || isAiConciergeOpen || isSmbLibraryOpen ||
                isOnAirOpen ||
                editingCondition != null || selectedConditionReserveItem != null ||
                selectedReserve != null || editingReserveItem != null ||
                editingNewProgram != null || reserveToDelete != null ||
                selectedProgramForAutoReserve != null
    }

    fun getVisibleTabs(hideAppsTab: Boolean = false): List<String> =
        com.beeregg2001.komorebi.ui.home.LauncherTab.visibleTabs(hideAppsTab, tif = backendType == "TIF").map { it.title }
}

internal fun shouldRefreshDataAfterSettingsClose(
    dataRevisionChanged: Boolean,
    isDataReady: Boolean,
    isOfflineMode: Boolean,
    isChannelError: Boolean,
): Boolean = dataRevisionChanged || !isDataReady || isOfflineMode || isChannelError

@Composable
fun rememberMainRootState(): MainRootState {
    return remember { MainRootState() }
}
