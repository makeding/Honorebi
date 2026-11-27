package com.beeregg2001.komorebi.ui.main

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal enum class MainRootBackAction {
    IGNORE,
    CLOSE_AI_CONCIERGE,
    DISMISS_SELECTED_CONDITION_RESERVE,
    DISMISS_EDITING_NEW_PROGRAM,
    DISMISS_EDITING_RESERVE_ITEM,
    DISMISS_RESERVE_TO_DELETE,
    DISMISS_AUTO_RESERVE_PROGRAM,
    DISMISS_DELETE_CONFIRM_DIALOG,
    HANDLE_PLAYBACK_BACK,
    CLOSE_SETTINGS,
    DISMISS_EPG_SELECTED_PROGRAM,
    DISMISS_SELECTED_RESERVE,
    DISMISS_EPG_JUMP_MENU,
    CLOSE_SMB_LIBRARY,
    CLOSE_ON_AIR,
    CLOSE_RECORD_LIST,
    CLOSE_SERIES_LIST,
    TRIGGER_HOME_BACK,
}

/** Pure, side-effect-free priority ladder matching MainRootScreen's exact 19-branch Back order. */
internal fun resolveMainRootBackAction(state: MainRootState): MainRootBackAction = when {
    state.isAiConciergeOpen -> MainRootBackAction.CLOSE_AI_CONCIERGE
    state.selectedConditionReserveItem != null -> MainRootBackAction.DISMISS_SELECTED_CONDITION_RESERVE
    state.editingNewProgram != null -> MainRootBackAction.DISMISS_EDITING_NEW_PROGRAM
    state.editingReserveItem != null -> MainRootBackAction.DISMISS_EDITING_RESERVE_ITEM
    state.reserveToDelete != null -> MainRootBackAction.DISMISS_RESERVE_TO_DELETE
    state.selectedProgramForAutoReserve != null -> MainRootBackAction.DISMISS_AUTO_RESERVE_PROGRAM
    state.showDeleteConfirmDialog -> MainRootBackAction.DISMISS_DELETE_CONFIRM_DIALOG
    state.playbackState.isPlaybackActive -> MainRootBackAction.HANDLE_PLAYBACK_BACK
    state.isSettingsOpen -> MainRootBackAction.CLOSE_SETTINGS
    state.epgSelectedProgram != null -> MainRootBackAction.DISMISS_EPG_SELECTED_PROGRAM
    state.selectedReserve != null -> MainRootBackAction.DISMISS_SELECTED_RESERVE
    state.isEpgJumpMenuOpen -> MainRootBackAction.DISMISS_EPG_JUMP_MENU
    state.isSmbLibraryOpen -> MainRootBackAction.CLOSE_SMB_LIBRARY
    state.isOnAirOpen -> MainRootBackAction.CLOSE_ON_AIR
    state.isRecordListOpen -> MainRootBackAction.CLOSE_RECORD_LIST
    state.isSeriesListOpen -> MainRootBackAction.CLOSE_SERIES_LIST
    state.showConnectionErrorDialog -> MainRootBackAction.IGNORE
    !(state.isDataReady && state.isUiReady) -> MainRootBackAction.IGNORE
    else -> MainRootBackAction.TRIGGER_HOME_BACK
}

@Stable
internal class MainRootNavigationController(
    val state: MainRootState,
    initialSettingsDataRevision: Int = 0,
    initialHomeIntentVersion: Int = 0,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    var settingsDataRevisionAtOpen by mutableIntStateOf(initialSettingsDataRevision)
        internal set
    var handledHomeIntentVersion by mutableIntStateOf(initialHomeIntentVersion)
        internal set

    fun onSettingsOpened(currentDataRevision: Int) {
        settingsDataRevisionAtOpen = currentDataRevision
    }

    fun closeSettings(
        currentDataRevision: Int,
        isChannelError: Boolean,
        onRefreshAllData: () -> Unit,
    ) {
        val needsBackgroundRefresh = shouldRefreshDataAfterSettingsClose(
            dataRevisionChanged = currentDataRevision != settingsDataRevisionAtOpen,
            isDataReady = state.isDataReady,
            isOfflineMode = state.isOfflineMode,
            isChannelError = isChannelError,
        )
        state.closeSettings()
        if (needsBackgroundRefresh) {
            state.showConnectionErrorDialog = false
            state.isOfflineMode = false
            onRefreshAllData()
        }
    }

    fun dismissRootOverlays(onCloseSettings: () -> Unit) {
        if (state.isSettingsOpen) {
            onCloseSettings()
        }
        state.clearRootOverlays()
    }

    fun returnToLauncherHome(
        hideAppsTab: Boolean,
        onCloseSettings: () -> Unit,
        onEnsureEpgLoaded: () -> Unit,
    ) {
        Log.i(
            "MainRootScreen",
            "returnToLauncherHome called: isPlaybackActive=${state.playbackState.isPlaybackActive}",
        )
        val hasActiveModule = state.hasActiveModuleOrOverlay()
        val preservedTabIndex = state.currentTabIndex

        if (state.isSettingsOpen) {
            onCloseSettings()
        }

        if (hasActiveModule) {
            state.currentTabIndex = preservedTabIndex
            state.returnToCurrentTabFromModule(hideAppsTab)
        } else {
            state.resetForLauncherHome("ホーム", hideAppsTab)
        }
        val tabs = state.getVisibleTabs(hideAppsTab)
        if (tabs.getOrNull(state.currentTabIndex) == "番組表") onEnsureEpgLoaded()
        state.clearRootOverlays()
    }

    fun closeAiConcierge(
        restoreFocus: Boolean,
        hideAppsTab: Boolean,
        onResetAiState: () -> Unit,
        onRestoreEpgFocus: () -> Unit,
        onClearEpgSearch: () -> Unit,
    ) {
        state.isAiConciergeOpen = false
        onResetAiState()

        if (restoreFocus) {
            if (state.currentTabIndex == state.getVisibleTabs(hideAppsTab).indexOf("番組表")) {
                onRestoreEpgFocus()
            } else {
                state.aiFocusReturnTick++
            }
        }
        onClearEpgSearch()
    }

    fun handleBackPress(
        onCloseAiConcierge: (restoreFocus: Boolean) -> Unit,
        onCloseSettings: () -> Unit,
        onResetRecordSearch: () -> Unit,
    ): MainRootBackAction {
        if (!state.canProcessBackPress(nowMillis())) return MainRootBackAction.IGNORE

        val action = resolveMainRootBackAction(state)
        when (action) {
            MainRootBackAction.CLOSE_AI_CONCIERGE -> onCloseAiConcierge(true)
            MainRootBackAction.DISMISS_SELECTED_CONDITION_RESERVE -> state.selectedConditionReserveItem = null
            MainRootBackAction.DISMISS_EDITING_NEW_PROGRAM -> state.editingNewProgram = null
            MainRootBackAction.DISMISS_EDITING_RESERVE_ITEM -> state.editingReserveItem = null
            MainRootBackAction.DISMISS_RESERVE_TO_DELETE -> state.reserveToDelete = null
            MainRootBackAction.DISMISS_AUTO_RESERVE_PROGRAM -> state.selectedProgramForAutoReserve = null
            MainRootBackAction.DISMISS_DELETE_CONFIRM_DIALOG -> state.showDeleteConfirmDialog = false
            MainRootBackAction.HANDLE_PLAYBACK_BACK -> state.playbackState.handleBack()
            MainRootBackAction.CLOSE_SETTINGS -> onCloseSettings()
            MainRootBackAction.DISMISS_EPG_SELECTED_PROGRAM -> state.epgSelectedProgram = null
            MainRootBackAction.DISMISS_SELECTED_RESERVE -> state.selectedReserve = null
            MainRootBackAction.DISMISS_EPG_JUMP_MENU -> state.isEpgJumpMenuOpen = false
            MainRootBackAction.CLOSE_SMB_LIBRARY -> state.isSmbLibraryOpen = false
            MainRootBackAction.CLOSE_ON_AIR -> state.closeOnAir()
            MainRootBackAction.CLOSE_RECORD_LIST -> {
                state.isRecordListOpen = false
                if (state.openedSeriesTitle != null) {
                    state.isSeriesListOpen = true
                    state.openedSeriesTitle = null
                }
                onResetRecordSearch()
            }
            MainRootBackAction.CLOSE_SERIES_LIST -> {
                state.isSeriesListOpen = false
                onResetRecordSearch()
            }
            MainRootBackAction.IGNORE -> Unit
            MainRootBackAction.TRIGGER_HOME_BACK -> state.triggerHomeBack = true
        }
        return action
    }
}

@Composable
internal fun rememberMainRootNavigationController(
    state: MainRootState,
    initialSettingsDataRevision: Int,
    initialHomeIntentVersion: Int,
): MainRootNavigationController = remember(state) {
    MainRootNavigationController(
        state = state,
        initialSettingsDataRevision = initialSettingsDataRevision,
        initialHomeIntentVersion = initialHomeIntentVersion,
    )
}
