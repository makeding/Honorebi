package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.media.IdleSystemMediaSession
import com.beeregg2001.komorebi.ui.components.DEFAULT_TOAST_DURATION_MS
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

internal fun resolveStartupTargetChannel(
    desiredStartupChannel: String?,
    lastWatchedId: String?,
    flatChannels: List<Channel>,
    tifBackend: Boolean,
    tifActive: Boolean,
): Channel? {
    if (desiredStartupChannel == null || flatChannels.isEmpty() || desiredStartupChannel == "OFF") {
        return null
    }
    val savedId = if (desiredStartupChannel == "LAST_WATCHED") lastWatchedId else desiredStartupChannel
    return when {
        !tifActive && savedId?.startsWith("tif:") == true ->
            flatChannels.firstOrNull { !it.id.startsWith("tif:") }
        else -> flatChannels.find { it.id == savedId }
            ?: if (tifBackend || savedId?.startsWith("tif:") == true) null else flatChannels.first()
    }
}

internal fun isMainSystemReady(
    state: MainRootState,
    isSettingsInitialized: Boolean,
    startupChannelSetting: String?,
    tifBackend: Boolean,
    isChannelLoading: Boolean,
    hasAnyChannels: Boolean,
): Boolean =
    ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) &&
        state.hasAppliedStartupTab &&
        (startupChannelSetting == "OFF" ||
            state.hasAppliedStartupChannel ||
            state.isOfflineMode ||
            (tifBackend && !isChannelLoading && !hasAnyChannels))

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun MainRootStartupEffects(
    state: MainRootState,
    navController: MainRootNavigationController,
    upgradeTourState: UpgradeTourHostState,
    appWakeState: AppWakeState,
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    backendType: String?,
    tifActive: Boolean,
    groupedChannels: Map<String, List<Channel>>,
    isChannelLoading: Boolean,
    isHomeLoading: Boolean,
    isChannelError: Boolean,
    isSettingsInitialized: Boolean,
    isEpgReady: Boolean,
    isNetworkAvailable: Boolean,
    hideAppsTab: Boolean,
    shouldAutoShowUpgradeTour: Boolean,
    homeIntentVersion: Int,
    settingsIntentVersion: Int,
    settingsDataRefreshRevision: Int,
    startupUpdateGateReady: Boolean,
    onReturnToLauncherHome: () -> Unit,
) {
    val tif = backendType == "TIF"
    val tabs = state.getVisibleTabs(hideAppsTab)
    val startupChannelSetting = state.startupSettings?.startupChannel
    val startupTabs = state.getVisibleTabs(state.startupSettings?.hideAppsTab ?: hideAppsTab)
    val currentOnReturnToLauncherHome by rememberUpdatedState(onReturnToLauncherHome)

    var lastBackend by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(backendType) {
        val selected = backendType ?: return@LaunchedEffect
        state.backendType = selected
        if (lastBackend != null && lastBackend != selected) {
            if (state.playbackState.renderPlaybackTarget !is PlaybackTarget.Smb) {
                state.playbackState.resetPlayback()
            }
            state.epgSelectedProgram = null
            state.selectedReserve = null
            state.editingReserveItem = null
            state.editingNewProgram = null
            state.selectedConditionReserveItem = null
            state.editingCondition = null
            state.reserveToDelete = null
            state.selectedProgramForAutoReserve = null
            state.isRecordListOpen = false
            state.isSeriesListOpen = false
            state.currentTabIndex = 0
        }
        lastBackend = selected
    }

    LaunchedEffect(tabs.size) {
        if (state.currentTabIndex >= tabs.size) state.currentTabIndex = 0
    }

    LaunchedEffect(backendType) {
        if (backendType != null && !state.hasAppliedStartupTab) {
            val snap = settingsViewModel.loadStartupSettings()
            state.startupSettings = snap
            val index = state.getVisibleTabs(snap.hideAppsTab).indexOf(snap.startupTab)
            state.currentTabIndex = if (index != -1) index else 0
            if (snap.startupTab == "番組表") epgViewModel.ensureInitialDataLoaded()
            channelViewModel.refreshChannelsIfStale()
            state.hasAppliedStartupTab = true
        }
    }

    LaunchedEffect(
        groupedChannels,
        state.hasAppliedStartupTab,
        startupChannelSetting,
        homeIntentVersion,
        isSettingsInitialized,
        isChannelLoading,
        isHomeLoading,
    ) {
        if (homeIntentVersion == 0 && isSettingsInitialized && state.hasAppliedStartupTab && !state.hasAppliedStartupChannel && !isChannelLoading && !isHomeLoading && groupedChannels.isNotEmpty()) {
            val desiredStartupChannel = startupChannelSetting ?: return@LaunchedEffect
            val flatChannels = groupedChannels.values.flatten()
            if (flatChannels.isEmpty() || desiredStartupChannel == "OFF") {
                state.hasAppliedStartupChannel = true
                return@LaunchedEffect
            }
            val lastWatchedId = if (desiredStartupChannel == "LAST_WATCHED") {
                channelViewModel.lastWatchedChannelIdOnce()
            } else null
            if (state.hasAppliedStartupChannel) return@LaunchedEffect
            state.hasAppliedStartupChannel = true
            val targetChannel = resolveStartupTargetChannel(
                desiredStartupChannel = desiredStartupChannel,
                lastWatchedId = lastWatchedId,
                flatChannels = flatChannels,
                tifBackend = tif,
                tifActive = tifActive,
            ) ?: return@LaunchedEffect

            state.playbackState.enterLive(targetChannel)
            homeViewModel.saveLastChannel(targetChannel)

            val liveIndex = startupTabs.indexOf("ライブ")
            if (liveIndex != -1) state.currentTabIndex = liveIndex
            state.isUiReady = true
        }
    }

    LaunchedEffect(state.isRecordListOpen) {
        if (state.isRecordListOpen) recordViewModel.triggerSmartSync()
    }

    LaunchedEffect(state.isSettingsOpen) {
        if (state.isSettingsOpen) {
            navController.onSettingsOpened(settingsDataRefreshRevision)
        }
    }

    LaunchedEffect(Unit) {
        settingsViewModel.startupFocusTarget.drop(1).collect { updated ->
            state.startupSettings = state.startupSettings?.copy(startupFocusTarget = updated)
        }
    }

    LaunchedEffect(homeIntentVersion) {
        if (homeIntentVersion > 0) {
            navController.handledHomeIntentVersion = homeIntentVersion
            upgradeTourState.dismissForHomeIntent(shouldAutoShowUpgradeTour) {
                settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
            }
            currentOnReturnToLauncherHome()
        }
    }

    LaunchedEffect(settingsIntentVersion) {
        if (settingsIntentVersion > 0) {
            upgradeTourState.dismissIfActive(shouldAutoShowUpgradeTour) {
                settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
            }
            if (!state.isSettingsOpen) {
                state.openSettings()
            }
        }
    }

    LaunchedEffect(state.toastMessage, state.toastTick) {
        if (state.toastMessage != null) {
            delay(DEFAULT_TOAST_DURATION_MS)
            state.clearToast()
        }
    }

    val reconnectGate = remember { NetworkReconnectGate() }
    LaunchedEffect(tif, isNetworkAvailable, isSettingsInitialized, appWakeState.isAwake, appWakeState.generation) {
        channelViewModel.setNetworkAvailable(isNetworkAvailable)
        if (tif) {
            if (appWakeState.isAwake && isSettingsInitialized) {
                channelViewModel.fetchChannels(afterNetworkRecovery = true)
                epgViewModel.refreshEpgData()
            }
            return@LaunchedEffect
        }
        if (reconnectGate.shouldRecheck(isNetworkAvailable, isSettingsInitialized, appWakeState.isAwake, appWakeState.generation)) {
            if (state.showConnectionErrorDialog) state.isConnectionRetryPending = true
            channelViewModel.refreshAfterRecovery()
            homeViewModel.refreshHomeData()
        }
        if (appWakeState.isAwake && isSettingsInitialized && !isNetworkAvailable &&
            !state.isOfflineMode && !state.showConnectionErrorDialog
        ) {
            state.isOfflineMode = true
            state.isDataReady = true
            state.isSplashFinished = true
            state.isUiReady = true
            state.hasAppliedStartupChannel = true
        }
        if (!isNetworkAvailable && state.showConnectionErrorDialog) {
            state.isConnectionRetryPending = false
        }
    }

    var wasSettingsInitialized by remember { mutableStateOf(isSettingsInitialized) }
    LaunchedEffect(isSettingsInitialized) {
        if (!wasSettingsInitialized && isSettingsInitialized) {
            state.showConnectionErrorDialog = false
            state.isConnectionRetryPending = false
            state.isOfflineMode = false
            state.isDataReady = false
            state.isUiReady = false
            channelViewModel.fetchChannels()
            epgViewModel.preloadAllEpgData()
            homeViewModel.refreshHomeData()
            recordViewModel.fetchRecentRecordings(forceRefresh = false)
            reserveViewModel.fetchReserves()
        }
        wasSettingsInitialized = isSettingsInitialized
    }

    LaunchedEffect(
        tif,
        isSettingsInitialized,
        isChannelLoading,
        isHomeLoading,
        isChannelError,
        isNetworkAvailable,
        appWakeState.isAwake,
        state.hasAppliedStartupTab,
    ) {
        if (!isSettingsInitialized) {
            state.showConnectionErrorDialog = false
            state.isConnectionRetryPending = false
            return@LaunchedEffect
        }
        if (appWakeState.isAwake && state.hasAppliedStartupTab && !isChannelLoading && (state.isDataReady || !isHomeLoading)) {
            if (tif) {
                state.isOfflineMode = false
                state.showConnectionErrorDialog = false
                state.isDataReady = true
                return@LaunchedEffect
            }
            val presentation = offlinePresentation(
                isNetworkAvailable, isChannelError, state.isOfflineMode, state.isDataReady,
            )
            when (offlineUiAction(presentation, state.showConnectionErrorDialog)) {
                OfflineUiAction.SHOW_CONNECTION_GUIDE -> {
                    state.isConnectionRetryPending = false
                    state.showConnectionErrorDialog = true
                    state.isDataReady = false
                }
                OfflineUiAction.KEEP_CONNECTION_GUIDE -> {
                    state.isConnectionRetryPending = false
                }
                OfflineUiAction.SHOW_CACHED -> {
                    state.isConnectionRetryPending = false
                    state.isOfflineMode = true
                    state.isDataReady = true
                    state.isSplashFinished = true
                    state.isUiReady = true
                    state.hasAppliedStartupChannel = true
                }
                OfflineUiAction.SHOW_CONNECTED -> {
                    state.isConnectionRetryPending = false
                    state.showConnectionErrorDialog = false
                    state.isDataReady = true
                    state.isOfflineMode = false
                }
            }
        }
    }

    LaunchedEffect(
        isEpgReady,
        state.isDataReady,
        isSettingsInitialized,
        state.hasAppliedStartupTab,
        state.currentTabIndex,
        startupTabs,
    ) {
        if (!state.hasAppliedStartupTab) return@LaunchedEffect
        if (!isSettingsInitialized) {
            state.isSplashFinished = true
        } else if (startupTabs.getOrNull(state.currentTabIndex) == "番組表") {
            if (isEpgReady && state.isDataReady) {
                state.isSplashFinished = true
            }
        } else {
            if (state.isDataReady) {
                state.isSplashFinished = true
            }
        }
    }

    val latestStartupUpdateGate by rememberUpdatedState(startupUpdateGateReady)
    LaunchedEffect(appWakeState.isAwake, appWakeState.generation) {
        if (appWakeState.isAwake) {
            settingsViewModel.nhkExclusionRepository.refreshExpiry()
            if (appWakeState.generation > 0) {
                homeViewModel.checkForUpdatesAutomatically()
                homeViewModel.refreshLauncherApps()
            } else {
                snapshotFlow { latestStartupUpdateGate }.first { it }
                delay(5_000)
                homeViewModel.checkForUpdatesAutomatically()
            }
        }
    }
}

@Composable
internal fun MainRootOsPipEffects(
    state: MainRootState,
    homeViewModel: HomeViewModel,
    defaultSystemChannel: Channel?,
    isInOsPip: Boolean,
    osPipDismissVersion: Int,
    isOsPipEligible: Boolean,
    osPipAutoEnter: Boolean,
    effectivePipIsPlaying: Boolean,
    systemPlaybackCanSeek: Boolean,
    onOsPipStateChanged: (eligible: Boolean, autoEnter: Boolean, isPlaying: Boolean, canSeek: Boolean) -> Unit,
) {
    val currentOnOsPipStateChanged by rememberUpdatedState(onOsPipStateChanged)

    LaunchedEffect(isOsPipEligible, osPipAutoEnter, effectivePipIsPlaying, systemPlaybackCanSeek) {
        currentOnOsPipStateChanged(isOsPipEligible, osPipAutoEnter, effectivePipIsPlaying, systemPlaybackCanSeek)
    }
    DisposableEffect(Unit) {
        onDispose { currentOnOsPipStateChanged(false, false, false, false) }
    }
    LaunchedEffect(osPipDismissVersion) {
        if (osPipDismissVersion > 0) {
            state.playbackState.leavePlayback(returningFromPlayer = false)
        }
    }
    LaunchedEffect(isInOsPip, state.toastMessage) {
        if (isInOsPip && state.toastMessage != null) {
            state.clearToast()
        }
    }
    IdleSystemMediaSession(enabled = !state.playbackState.isPlaybackActive) {
        val channel = defaultSystemChannel ?: return@IdleSystemMediaSession
        state.playbackState.enterLive(channel)
        homeViewModel.saveLastChannel(channel)
    }
}
