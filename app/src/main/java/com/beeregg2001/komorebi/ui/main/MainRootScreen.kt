@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.common.LocalLateNightWatchEnabled
import com.beeregg2001.komorebi.common.LocalSpoilerBlurEnabled
import com.beeregg2001.komorebi.common.detectJikkyoFestival
import com.beeregg2001.komorebi.data.BackgroundPlaybackMode
import com.beeregg2001.komorebi.media.RootSystemMediaSessionHost
import com.beeregg2001.komorebi.navigation.PlaybackOpenRequest
import com.beeregg2001.komorebi.notification.NotificationCenterViewModel
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.components.LocalShowToast
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.home.LocalUpgradeSpotlightTargets
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.ui.home.UpgradeSpotlightOverlay
import com.beeregg2001.komorebi.ui.home.UpgradeTourCatalog
import com.beeregg2001.komorebi.ui.home.rememberUpgradeSpotlightTargets
import com.beeregg2001.komorebi.ui.home.shouldShowUpgradeLandingTour
import com.beeregg2001.komorebi.ui.player.PlaybackPhase
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.ui.setting.SettingsCategory
import com.beeregg2001.komorebi.ui.setting.SettingsDestination
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.util.UpdateState
import com.beeregg2001.komorebi.viewmodel.AiConciergeViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.RemoteControlViewModel
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private const val TAG = "MainRootScreen"
private const val AI_FEATURES_ENABLED = false

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootScreen(
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel = hiltViewModel(),
    aiConciergeViewModel: AiConciergeViewModel = hiltViewModel(),
    remoteControlViewModel: RemoteControlViewModel = hiltViewModel(),
    notificationCenterViewModel: NotificationCenterViewModel = hiltViewModel(),
    homeIntentVersion: Int = 0,
    latestHomeIntentVersion: () -> Int = { homeIntentVersion },
    settingsIntentVersion: Int = 0,
    launchedFromDeepLink: Boolean = false,
    deepLinkRequests: Flow<PlaybackOpenRequest> = emptyFlow(),
    onRemotePlaybackOpened: () -> Unit = {},
    isInOsPip: Boolean = false,
    osPipDismissVersion: Int = 0,
    onOsPipStateChanged: (eligible: Boolean, autoEnter: Boolean, isPlaying: Boolean, canSeek: Boolean) -> Unit = { _, _, _, _ -> },
    onRegisterPipTransportActions: (togglePlayPause: () -> Unit, seekRelativeMs: (Long) -> Unit) -> Unit = { _, _ -> },
    onInternalActivityLaunch: () -> Unit = {},
    onExitApp: () -> Unit
) {
    val state = rememberMainRootState()
    val settingsDataRefreshRevision by settingsViewModel.dataRefreshRevision.collectAsState()
    val navController = rememberMainRootNavigationController(
        state = state,
        initialSettingsDataRevision = settingsDataRefreshRevision,
        initialHomeIntentVersion = homeIntentVersion,
    )
    val remoteKeyController = rememberMainRootRemoteKeyController(
        state = state,
        aiFeaturesEnabled = AI_FEATURES_ENABLED,
    )

    MainRootRemotePlaybackEffects(
        state = state,
        channelViewModel = channelViewModel,
        homeViewModel = homeViewModel,
        settingsViewModel = settingsViewModel,
        recordViewModel = recordViewModel,
        remoteControlViewModel = remoteControlViewModel,
        launchedFromDeepLink = launchedFromDeepLink,
        deepLinkRequests = deepLinkRequests,
        onRemotePlaybackOpened = onRemotePlaybackOpened,
    )

    // =========================================================================================
    // ★ 追加: アプリのバックグラウンド移行（スリープ）と復帰を検知する機構
    // =========================================================================================
    val appWakeState = rememberAppWakeState(channelViewModel::setAppAwake)

    val nhkExclusionRepository = settingsViewModel.nhkExclusionRepository
    val nhkExclusionState by nhkExclusionRepository.state.collectAsState()
    val tifActive by settingsViewModel.tifIntegrationActive.collectAsState()
    val timeFormat by settingsViewModel.timeFormat.collectAsState()
    val geminiApiKey by settingsViewModel.geminiApiKey.collectAsState(initial = "")
    val hideAppsTab by settingsViewModel.hideAppsTab.collectAsState(initial = false)
    val deepTv by settingsViewModel.deepTvFeatureSettings.collectAsState()
    val backendType by settingsViewModel.configuredBackend.collectAsState(initial = null)
    val tif = backendType == "TIF"

    val tabs = state.getVisibleTabs(hideAppsTab)
    val safeTabIndex = state.currentTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    val closeAiConcierge = { restoreFocus: Boolean ->
        navController.closeAiConcierge(
            restoreFocus = restoreFocus,
            hideAppsTab = hideAppsTab,
            onResetAiState = aiConciergeViewModel::resetState,
            onRestoreEpgFocus = epgViewModel::triggerRestore,
            onClearEpgSearch = epgViewModel::clearSearch,
        )
    }

    MainRootAiConciergeEffect(
        enabled = AI_FEATURES_ENABLED,
        state = state,
        tabs = tabs,
        channelViewModel = channelViewModel,
        epgViewModel = epgViewModel,
        homeViewModel = homeViewModel,
        recordViewModel = recordViewModel,
        reserveViewModel = reserveViewModel,
        aiConciergeViewModel = aiConciergeViewModel,
        onCloseAiConcierge = closeAiConcierge,
    )

    val themeName by settingsViewModel.appTheme.collectAsState(initial = "MONOTONE")
    val currentTheme = remember(themeName) {
        runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.MONOTONE)
    }
    val themeSeason = remember(themeName) {
        when (themeName) {
            "SPRING", "SPRING_LIGHT" -> "SPRING"
            "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
            "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
            "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
            "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI"
            "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE"
            else -> "DEFAULT"
        }
    }

    val detailFocusRequester = remember { FocusRequester() }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isChannelRecovering by channelViewModel.isRecovering.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val channelErrorReason by channelViewModel.channelError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val upgradeCheckState by settingsViewModel.upgradeCheckState.collectAsState()
    val upgradeTourState = remember {
        UpgradeTourHostState(
            steps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE),
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(BuildConfig.VERSION_CODE),
        )
    }
    val spotlightTargets = rememberUpgradeSpotlightTargets()
    val shouldAutoShowUpgradeTour = (upgradeCheckState as? SettingsViewModel.UpgradeCheckState.Ready)?.let { ready ->
        upgradeTourState.shouldAutoShow(
            lastSeenVersionCode = ready.lastSeenVersionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            isSettingsInitialized = ready.isSettingsInitialized,
        )
    } == true

    LaunchedEffect(upgradeCheckState) {
        val ready = upgradeCheckState as? SettingsViewModel.UpgradeCheckState.Ready ?: return@LaunchedEffect
        val shouldShow = shouldShowUpgradeLandingTour(
            lastSeenVersionCode = ready.lastSeenVersionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            isSettingsInitialized = ready.isSettingsInitialized,
            hasTourForCurrentVersion = upgradeTourState.steps.isNotEmpty(),
            tourIntroducedVersionCode = upgradeTourState.introducedVersionCode ?: BuildConfig.VERSION_CODE,
        )
        if (!shouldShow && ready.lastSeenVersionCode != BuildConfig.VERSION_CODE) {
            settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
        }
    }

    val networkConnectionStatus by rememberNetworkConnectionStatus(
        awake = appWakeState.isAwake,
        wakeGeneration = appWakeState.generation,
        onPhysicalNetworkRestored = {
            if (appWakeState.isAwake && isSettingsInitialized) {
                channelViewModel.onPhysicalNetworkRestored()
                homeViewModel.refreshHomeData()
            }
        },
    )
    val isNetworkAvailable = networkConnectionStatus.isAvailable
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val localRecordedCount by recordViewModel.localRecordedCount.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState(initial = emptyList())
    val conditions by reserveViewModel.conditions.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()

    val screensaverHostState = rememberMainRootScreensaverHostState(
        settingsViewModel = settingsViewModel,
        appWakeState = appWakeState,
        onInternalActivityLaunch = onInternalActivityLaunch,
    )

    var systemPlaybackIsPlaying by remember { mutableStateOf(false) }
    var systemPlaybackCanSeek by remember { mutableStateOf(false) }
    val renderTarget = state.playbackState.renderPlaybackTarget
    val isTifLiveTarget = renderTarget is PlaybackTarget.Live &&
        renderTarget.channel.id.startsWith("tif:")
    val isOsPipEligible = state.playbackState.isPlaybackActive &&
        (renderTarget is PlaybackTarget.Live ||
            renderTarget is PlaybackTarget.Recorded ||
            renderTarget is PlaybackTarget.Smb) &&
        !state.isSettingsOpen &&
        !screensaverHostState.isVisible
    val osPipAutoEnter = deepTv.backgroundPlaybackMode == BackgroundPlaybackMode.PIP
    val effectivePipIsPlaying = if (isTifLiveTarget) true else systemPlaybackIsPlaying
    val backgroundAudioPlaybackEnabled =
        deepTv.backgroundPlaybackMode == BackgroundPlaybackMode.AUDIO_ONLY &&
            appWakeState.isInteractive &&
            state.playbackState.isPlaybackActive &&
            !isTifLiveTarget &&
            (renderTarget is PlaybackTarget.Live ||
                renderTarget is PlaybackTarget.Recorded ||
                renderTarget is PlaybackTarget.Smb)

    val defaultSystemChannel = remember(groupedChannels) {
        val channels = groupedChannels.values.flatten()
        channels.firstOrNull { it.type == "GR" } ?: channels.firstOrNull()
    }
    MainRootOsPipEffects(
        state = state,
        homeViewModel = homeViewModel,
        defaultSystemChannel = defaultSystemChannel,
        isInOsPip = isInOsPip,
        osPipDismissVersion = osPipDismissVersion,
        isOsPipEligible = isOsPipEligible,
        osPipAutoEnter = osPipAutoEnter,
        effectivePipIsPlaying = effectivePipIsPlaying,
        systemPlaybackCanSeek = systemPlaybackCanSeek,
        onOsPipStateChanged = onOsPipStateChanged,
    )

    val updateState by homeViewModel.updateState.collectAsState()
    val autoReserveKeywords = remember(conditions) {
        conditions.map { it.programSearchCondition.keyword }.filter { it.isNotBlank() }
    }
    val isSyncingInitial by remember(recordViewModel) {
        recordViewModel.syncProgress.map { it.isSyncing && it.isInitialBuild }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    val hasSyncError by remember(recordViewModel) {
        recordViewModel.syncProgress.map { it.error != null }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val isEpgReady by epgViewModel.isInitialLoadComplete.collectAsState()

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState(initial = "")
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState(initial = "")
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    val closeSettings = {
        navController.closeSettings(
            currentDataRevision = settingsDataRefreshRevision,
            isChannelError = isChannelError,
            onRefreshAllData = {
                channelViewModel.fetchChannels()
                epgViewModel.preloadAllEpgData()
                homeViewModel.refreshHomeData()
                recordViewModel.fetchRecentRecordings(forceRefresh = false)
                reserveViewModel.fetchReserves()
            },
        )
    }

    val dismissRootOverlays = {
        navController.dismissRootOverlays(onCloseSettings = closeSettings)
    }

    val returnToLauncherHome = {
        navController.returnToLauncherHome(
            hideAppsTab = hideAppsTab,
            onCloseSettings = closeSettings,
            onEnsureEpgLoaded = epgViewModel::ensureInitialDataLoaded,
        )
    }

    val isSystemReady = isMainSystemReady(
        state = state,
        isSettingsInitialized = isSettingsInitialized,
        startupChannelSetting = state.startupSettings?.startupChannel,
        tifBackend = tif,
        isChannelLoading = isChannelLoading,
        hasAnyChannels = groupedChannels.isNotEmpty(),
    )
    val startupRenderPlan = startupRenderPlan(
        isSystemReady = isSystemReady,
        isSettingsInitialized = isSettingsInitialized,
        hasConnectionError = state.showConnectionErrorDialog,
        isSyncingInitial = isSyncingInitial && !tif,
        isPlaybackActive = state.playbackState.isPlaybackActive,
    )
    ReportDrawnWhen {
        startupRenderPlan.showMainContent ||
            state.showConnectionErrorDialog ||
            !isSettingsInitialized && state.isSplashFinished
    }

    MainRootStartupEffects(
        state = state,
        navController = navController,
        upgradeTourState = upgradeTourState,
        appWakeState = appWakeState,
        channelViewModel = channelViewModel,
        epgViewModel = epgViewModel,
        homeViewModel = homeViewModel,
        settingsViewModel = settingsViewModel,
        recordViewModel = recordViewModel,
        reserveViewModel = reserveViewModel,
        backendType = backendType,
        tifActive = tifActive,
        groupedChannels = groupedChannels,
        isChannelLoading = isChannelLoading,
        isHomeLoading = isHomeLoading,
        isChannelError = isChannelError,
        isSettingsInitialized = isSettingsInitialized,
        isEpgReady = isEpgReady,
        isNetworkAvailable = isNetworkAvailable,
        hideAppsTab = hideAppsTab,
        shouldAutoShowUpgradeTour = shouldAutoShowUpgradeTour,
        homeIntentVersion = homeIntentVersion,
        settingsIntentVersion = settingsIntentVersion,
        settingsDataRefreshRevision = settingsDataRefreshRevision,
        startupUpdateGateReady = startupRenderPlan.showMainContent ||
            state.showConnectionErrorDialog ||
            !isSettingsInitialized,
        onReturnToLauncherHome = returnToLauncherHome,
    )

    BackHandler(enabled = true) {
        navController.handleBackPress(
            onCloseAiConcierge = closeAiConcierge,
            onCloseSettings = closeSettings,
            onResetRecordSearch = { recordViewModel.searchRecordings("") },
        )
    }

    val lateNightWatchMode by settingsViewModel.lateNightWatchMode.collectAsState()
    val spoilerBlurEnabled by settingsViewModel.spoilerBlurEnabled.collectAsState()
    val jikkyoFestivalAlert by settingsViewModel.jikkyoFestivalAlert.collectAsState()
    val cmPanicZapEnabled by settingsViewModel.cmPanicZapEnabled.collectAsState()

    JikkyoFestivalAlertEffect(
        enabled = jikkyoFestivalAlert == "ON" && !state.isSettingsOpen,
        channelViewModel = channelViewModel,
        currentChannelId = state.playbackState.livePlayback?.channel?.id,
        onAlert = state::showToast,
    )

    CompositionLocalProvider(
        LocalLateNightWatchEnabled provides (lateNightWatchMode == "ON"),
        LocalSpoilerBlurEnabled provides (spoilerBlurEnabled == "ON"),
    ) {
        KomorebiTheme(theme = currentTheme) {
            var showExitDialog by remember { mutableStateOf(false) }
            val hostModals = RootHostModalState(
                hasSyncError = hasSyncError,
                isUpdateDialogVisible = updateState is UpdateState.UpdateAvailable,
                isScreensaverVisible = screensaverHostState.isVisible,
                isExitDialogVisible = showExitDialog,
            )
            val isUpgradeTourActive = upgradeTourState.isActive(
                showMainContent = startupRenderPlan.showMainContent,
                isPlaybackActive = state.playbackState.isPlaybackActive,
                isSettingsOpen = state.isSettingsOpen,
                hasBlockingOverlay = state.hasUpgradeTourBlockingOverlay(
                    isSettingsInitialized = isSettingsInitialized,
                    hostModals = hostModals,
                ),
                shouldAutoShow = shouldAutoShowUpgradeTour,
            )
            val showToast: (String) -> Unit = remember(state) { state::showToast }
            val tunableChannels = remember(groupedChannels, nhkExclusionState) {
                groupedChannels.values.flatten().filterNot(nhkExclusionRepository::isExcluded)
            }
            val liveChannel = state.playbackState.livePlayback?.channel
            val currentLiveType = liveChannel?.type
            val remoteKeyContext = remoteKeyController.buildRemoteKeyContext(
                isInOsPip = isInOsPip,
                showMainContent = startupRenderPlan.showMainContent,
                isUpgradeTourActive = isUpgradeTourActive,
                hostModals = hostModals,
            )
            val osdState = remember(
                remoteKeyController.channelNumberBuffer,
                remoteKeyController.channelNumberNotFound,
                tunableChannels,
                currentLiveType,
            ) {
                remoteKeyController.buildOsdState(tunableChannels, currentLiveType)
            }

            MainRootRemoteKeyEffects(
                controller = remoteKeyController,
                liveChannel = liveChannel,
                isInOsPip = isInOsPip,
                tunableChannels = tunableChannels,
                currentLiveType = currentLiveType,
                onSaveLastChannel = homeViewModel::saveLastChannel,
            )

            CompositionLocalProvider(
                LocalUpgradeSpotlightTargets provides spotlightTargets,
                LocalUpgradeTourActive provides isUpgradeTourActive,
                LocalShowToast provides showToast,
            ) {
                RootSystemMediaSessionHost(
                    playbackSessionEpoch = state.playbackState.playbackSessionEpoch,
                    remoteContentType = when {
                        state.playbackState.livePlayback != null -> {
                            val liveChannel = state.playbackState.livePlayback!!.channel
                            if (liveChannel.id.startsWith("tif:") &&
                                !com.beeregg2001.komorebi.ui.live.usesLiveOsd(liveChannel.id, liveChannel.type)
                            ) "Input" else "Live"
                        }
                        state.playbackState.recordedPlayback != null -> "Recorded"
                        state.playbackState.renderPlaybackTarget is PlaybackTarget.Smb -> "Smb"
                        else -> "Idle"
                    },
                    remoteSeriesId = state.playbackState.recordedPlayback?.program?.seriesId,
                    isPlaybackSwitching = state.playbackState.playbackPhase is PlaybackPhase.Switching,
                    remoteControlClient = remoteControlViewModel.client,
                    backgroundAudioPlaybackEnabled = backgroundAudioPlaybackEnabled,
                    isDeviceInteractive = appWakeState.isInteractive,
                    onSystemPlaybackSnapshotChanged = { isPlaying, canSeek ->
                        systemPlaybackIsPlaying = isPlaying
                        systemPlaybackCanSeek = canSeek
                    },
                    onRegisterTransportActions = { togglePlayPause, seekRelativeMs, _ ->
                        onRegisterPipTransportActions(togglePlayPause, seekRelativeMs)
                    },
                ) {
                    val colors = KomorebiTheme.colors

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // ★ 修正: onPreviewKeyEvent（トップダウン）に戻し、グローバルショートカットの確実性を復活
                            .onPreviewKeyEvent { event ->
                                remoteKeyController.handlePreviewKeyEvent(
                                    nativeEvent = event.nativeKeyEvent,
                                    cmPanicZapEnabled = cmPanicZapEnabled == "ON",
                                    tunableChannels = tunableChannels,
                                    onSaveLastChannel = homeViewModel::saveLastChannel,
                                )
                            }
                            .onKeyEvent { event ->
                                remoteKeyController.handleKeyEvent(
                                    nativeEvent = event.nativeKeyEvent,
                                    remoteShortcutKeysEnabled = deepTv.remoteShortcutKeysEnabled,
                                    remoteNumberTuningEnabled = deepTv.remoteNumberTuningEnabled,
                                    remoteKeyContext = remoteKeyContext,
                                    tabs = tabs,
                                    tifActive = tifActive,
                                    lastChannels = lastChannels,
                                    tunableChannels = tunableChannels,
                                    defaultSystemChannel = defaultSystemChannel,
                                    isChannelExcluded = nhkExclusionRepository::isExcluded,
                                    onDismissRootOverlays = dismissRootOverlays,
                                    onEnsureEpgLoaded = epgViewModel::ensureInitialDataLoaded,
                                    onSaveLastChannel = homeViewModel::saveLastChannel,
                                )
                            }
                            .background(colors.background)
                    ) {
                        if (!state.playbackState.isPlaybackActive) {
                            SeasonalBackgroundOverlay(
                                theme = KomorebiTheme.theme,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (startupRenderPlan.showSeasonalDecor) {
                            SeasonalDecor(
                                season = themeSeason,
                                isDark = colors.isDark,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }

                        if (startupRenderPlan.showMainContent) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                MainRootBackground(
                                    state = state,
                                    channelViewModel = channelViewModel,
                                    homeViewModel = homeViewModel,
                                    epgViewModel = epgViewModel,
                                    recordViewModel = recordViewModel,
                                    reserveViewModel = reserveViewModel,
                                    settingsViewModel = settingsViewModel,
                                    groupedChannels = groupedChannels,
                                    watchHistory = watchHistory,
                                    conditions = conditions,
                                    reserves = reserves,
                                    autoReserveKeywords = autoReserveKeywords,
                                    mirakurunIp = mirakurunIp,
                                    mirakurunPort = mirakurunPort,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    timeFormat = timeFormat,
                                    networkConnectionStatus = networkConnectionStatus,
                                    safeTabIndex = safeTabIndex,
                                    isSyncingInitial = isSyncingInitial,
                                    hasBackendFailure = isChannelError,
                                    backendFailureReason = channelErrorReason,
                                    isBackendRecoveryInProgress = isNetworkAvailable &&
                                        (isChannelRecovering || state.isConnectionRetryPending),
                                    onExitApp = { showExitDialog = true },
                                    onTriggerScreensaver = screensaverHostState.trigger,
                                    onInternalActivityLaunch = onInternalActivityLaunch,
                                    notificationCenterViewModel = notificationCenterViewModel,
                                    hasPendingHomeIntent = { latestHomeIntentVersion() != navController.handledHomeIntentVersion },
                                )

                                if (state.playbackState.isPlaybackActive) {
                                    MainRootPlaybackHost(
                                        state = state,
                                        suppressChrome = (screensaverHostState.isVisible && screensaverHostState.usePlayerSaver) || isInOsPip,
                                        isInOsPip = isInOsPip,
                                        data = MainRootPlaybackHostData(
                                            groupedChannels = groupedChannels,
                                            watchHistory = watchHistory,
                                            recentRecordings = recentRecordings,
                                            defaultLiveQuality = defaultLiveQuality,
                                            defaultVideoQuality = defaultVideoQuality,
                                            timeFormat = timeFormat,
                                            isNetworkAvailable = isNetworkAvailable,
                                        ),
                                        onSaveLastChannel = homeViewModel::saveLastChannel,
                                        onPlaybackEnded = {
                                            recordViewModel.fetchRecentRecordings(forceRefresh = true)
                                            channelViewModel.fetchChannels()
                                        },
                                        onLaunchSystemSettings = {
                                            onInternalActivityLaunch()
                                            homeViewModel.launchSystemSettings()
                                        },
                                        onTriggerScreensaver = screensaverHostState.trigger,
                                    )
                                }
                            }
                        }

                        // Strictly mutually exclusive with MainRootBackground. An exit animation here
                        // would still animate the spinner while the complete Home tree is first composed.
                        if (startupRenderPlan.showLoading) {
                            if (isSyncingInitial) {
                                val currentSync by recordViewModel.syncProgress.collectAsState()
                                val pRatio =
                                    if (currentSync.total > 0) currentSync.current.toFloat() / currentSync.total.toFloat() else 0f
                                LoadingScreen(message = currentSync.progressText, progressRatio = pRatio)
                            } else {
                                LoadingScreen()
                            }
                        }

                        if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
                            val hasOfflineCache = remember(
                                groupedChannels,
                                watchHistory,
                                recentRecordings,
                                lastChannels,
                                localRecordedCount,
                            ) {
                                groupedChannels.isNotEmpty() ||
                                    watchHistory.isNotEmpty() ||
                                    recentRecordings.isNotEmpty() ||
                                    lastChannels.isNotEmpty() ||
                                    localRecordedCount > 0
                            }

                            OfflineGuideScreen(
                                hasOfflineCache = hasOfflineCache,
                                isDeviceOffline = !isNetworkAvailable,
                                onContinueOffline = {
                                    state.isConnectionRetryPending = false
                                    state.showConnectionErrorDialog = false
                                    state.isOfflineMode = true
                                    state.isDataReady = true
                                    state.isSplashFinished = true
                                    state.isUiReady = true
                                    state.hasAppliedStartupChannel = true
                                },
                                onOpenSettings = {
                                    state.isConnectionRetryPending = false
                                    state.showConnectionErrorDialog = false
                                    state.openSettings(SettingsDestination.Category(SettingsCategory.CONNECTION))
                                },
                                modifier = Modifier.zIndex(6f),
                                reason = if (!isNetworkAvailable) {
                                    "ネットワーク接続がありません。接続設定を確認してください。接続が戻ると自動で更新します。（DEVICE_OFFLINE）"
                                } else {
                                    channelErrorReason
                                },
                            )
                        }

                        MainRootDialogs(
                            state = state,
                            channelViewModel = channelViewModel,
                            epgViewModel = epgViewModel,
                            homeViewModel = homeViewModel,
                            recordViewModel = recordViewModel,
                            reserveViewModel = reserveViewModel,
                            aiConciergeViewModel = aiConciergeViewModel,
                            groupedChannels = groupedChannels,
                            reserves = reserves,
                            updateState = updateState,
                            timeFormat = timeFormat,
                            isSettingsInitialized = isSettingsInitialized,
                            hasSyncError = hasSyncError,
                            detailFocusRequester = detailFocusRequester,
                            apiKey = if (AI_FEATURES_ENABLED) geminiApiKey else "",
                            onExitApp = { showExitDialog = true },
                            onCloseSettings = closeSettings,
                            closeAiConcierge = closeAiConcierge,
                            onGoToSettings = {
                                closeAiConcierge(true)
                                state.openSettings()
                            },
                            onOpenWhatsNew = {
                                upgradeTourState.openManually(
                                    onCloseSettings = closeSettings,
                                    onUnavailable = {
                                        state.showToast("このバージョンの新機能ガイドはありません")
                                    },
                                )
                            },
                        )
                        if (isUpgradeTourActive && upgradeTourState.steps.isNotEmpty()) {
                            UpgradeSpotlightOverlay(
                                steps = upgradeTourState.steps,
                                onStepChanged = { idx ->
                                    upgradeTourState.handleStepChanged(idx, state)
                                },
                                onStepAction = { action ->
                                    upgradeTourState.handleStepAction(action, state) {
                                        settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
                                    }
                                },
                                onFinish = {
                                    upgradeTourState.finish(state) {
                                        settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
                                    }
                                },
                                modifier = Modifier.zIndex(50f),
                            )
                        }
                        if (showExitDialog) {
                            ExitDialog(onConfirm = onExitApp, onDismiss = { showExitDialog = false })
                        }
                        ChannelNumberOsd(
                            state = osdState,
                            isInOsPip = isInOsPip,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 28.dp, end = 36.dp)
                                .zIndex(5f),
                        )
                        MainRootScreensaverDialog(
                            hostState = screensaverHostState,
                            settingsViewModel = settingsViewModel,
                            appWakeState = appWakeState,
                            isInOsPip = isInOsPip,
                        )
                        GlobalToast(
                            message = if (isInOsPip) null else state.toastMessage,
                            modifier = Modifier.zIndex(1100f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonalBackgroundOverlay(
    theme: AppTheme,
    modifier: Modifier = Modifier,
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60_000L)
        }
    }
    val backgroundBrush = getSeasonalBackgroundBrush(theme, currentTime)
    Box(modifier = modifier.background(backgroundBrush))
}

@Composable
private fun JikkyoFestivalAlertEffect(
    enabled: Boolean,
    channelViewModel: ChannelViewModel,
    currentChannelId: String?,
    onAlert: (String) -> Unit,
) {
    if (!enabled) return
    val liveRows by channelViewModel.liveRows.collectAsState()
    val alertedAt = remember { mutableMapOf<String, Long>() }
    val currentOnAlert by rememberUpdatedState(onAlert)
    LaunchedEffect(liveRows, currentChannelId) {
        val nowMillis = System.currentTimeMillis()
        val alert = detectJikkyoFestival(
            rows = liveRows,
            currentChannelId = currentChannelId,
            alertedAtMillis = alertedAt,
            nowMillis = nowMillis,
        )
        if (alert != null) {
            alertedAt[alert.channel.id] = nowMillis
            currentOnAlert(alert.bannerText)
        }
    }
}
