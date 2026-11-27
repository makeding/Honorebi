package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.*
import com.beeregg2001.komorebi.ui.player.PlaybackSessionState
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.setting.SettingsDestination
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.viewmodel.StartupSettingsSnapshot

data class RootHostModalState(
    val hasSyncError: Boolean = false,
    val isUpdateDialogVisible: Boolean = false,
    val isScreensaverVisible: Boolean = false,
    val isExitDialogVisible: Boolean = false,
) {
    val hasAnyModal: Boolean
        get() = hasSyncError || isUpdateDialogVisible || isScreensaverVisible || isExitDialogVisible
}

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
    var toastTick by mutableIntStateOf(0)
        private set

    fun showToast(message: String) {
        toastMessage = message
        toastTick++
    }

    fun clearToast() {
        toastMessage = null
    }

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
    var settingsDestination by mutableStateOf<SettingsDestination>(SettingsDestination.Default)
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

    fun openSettings(destination: SettingsDestination = SettingsDestination.Default) {
        settingsDestination = destination
        isSettingsOpen = true
    }

    /** Closes Settings without tearing down the mounted Home hierarchy or resetting the active tab. */
    fun closeSettings() {
        isSettingsOpen = false
        settingsDestination = SettingsDestination.Default
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

    /** Sub-screens mounted in place of or above the launcher tabs. */
    fun hasLauncherSubScreen(): Boolean =
        isRecordListOpen ||
            isSeriesListOpen ||
            isSmbLibraryOpen ||
            isOnAirOpen

    /** Reservation-specific detail/edit/delete modals (used by ReserveListScreen focus suspension & restore). */
    fun hasReserveOverlay(): Boolean =
        selectedReserve != null ||
            editingReserveItem != null ||
            reserveToDelete != null ||
            editingCondition != null ||
            selectedConditionReserveItem != null

    /** Full-screen or backdrop detail/reservation/confirmation modals rendered above the launcher. */
    fun hasDetailOrReservationModal(): Boolean =
        hasReserveOverlay() ||
            epgSelectedProgram != null ||
            editingNewProgram != null ||
            selectedProgramForAutoReserve != null ||
            showDeleteConfirmDialog

    /** AI Concierge side panel or text input dialog. */
    fun hasAiOverlay(): Boolean =
        isAiConciergeOpen || showAiKeyboardInput

    /**
     * True when the launcher's own chrome (nav rail + top system strip) and tab polling
     * should suspend because a full-screen module, sub-screen, or detail/reservation modal covers it.
     * Deliberately excludes in-tab menus (isEpgJumpMenuOpen) and side-panels (hasAiOverlay)
     * so the nav rail does not unmount or shift content 68.dp left.
     */
    fun hasLauncherFullScreenOverlay(): Boolean =
        isSettingsOpen ||
            hasLauncherSubScreen() ||
            hasDetailOrReservationModal()

    /** True when any sub-screen, detail pane, jump menu, or reservation/error dialog owned by MainRootState is open. */
    fun hasRootOverlayDestination(): Boolean =
        showConnectionErrorDialog ||
            isEpgJumpMenuOpen ||
            hasLauncherSubScreen() ||
            hasDetailOrReservationModal()

    /** True when the user is currently inside a player, settings, sub-screen, or any overlay above the launcher tabs. */
    fun hasActiveModuleOrOverlay(): Boolean =
        playbackState.isPlaybackActive ||
            isSettingsOpen ||
            hasAiOverlay() ||
            hasRootOverlayDestination()

    fun hasUpgradeTourBlockingOverlay(
        isSettingsInitialized: Boolean,
        hostModals: RootHostModalState = RootHostModalState(),
    ): Boolean =
        !isSettingsInitialized ||
            hostModals.hasAnyModal ||
            hasAiOverlay() ||
            hasRootOverlayDestination()

    fun hasRemoteKeyBlockingOverlay(
        showMainContent: Boolean,
        isUpgradeTourActive: Boolean = false,
        hostModals: RootHostModalState = RootHostModalState(),
    ): Boolean =
        !showMainContent ||
            isUpgradeTourActive ||
            hostModals.hasAnyModal ||
            hasRootOverlayDestination()

    fun clearRootOverlays() {
        epgSelectedProgram = null
        selectedReserve = null
        editingReserveItem = null
        editingNewProgram = null
        reserveToDelete = null
        selectedProgramForAutoReserve = null
        editingCondition = null
        selectedConditionReserveItem = null
        openedSeriesTitle = null
        isRecordListOpen = false
        isSeriesListOpen = false
        isSmbLibraryOpen = false
        isOnAirOpen = false
        isEpgJumpMenuOpen = false
        showDeleteConfirmDialog = false
        isAiConciergeOpen = false
        showAiKeyboardInput = false
    }

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
