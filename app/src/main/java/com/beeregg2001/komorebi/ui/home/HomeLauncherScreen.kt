@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.beeregg2001.komorebi.data.StartupFocusTarget
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.notification.InternalNowPlayingState
import com.beeregg2001.komorebi.notification.LauncherMediaPillState
import com.beeregg2001.komorebi.notification.NotificationCenterViewModel
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.main.NetworkConnectionStatus
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.TvRailCollapsedWidth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "HomeLauncher"

@Composable
internal fun HomeTabRenderEffect(
    requestedIndex: Int,
    requestedContentKey: String,
    state: HomeTabRenderState,
) {
    LaunchedEffect(requestedIndex, requestedContentKey) {
        if (state.activeIndex != requestedIndex ||
            state.activeContentKey != requestedContentKey
        ) {
            delay(TAB_RENDER_COALESCE_MS)
            state.commit(requestedIndex, requestedContentKey)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeLauncherScreen(
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    settingsViewModel: SettingsViewModel,
    groupedChannels: Map<String, List<Channel>>,
    mirakurunIp: String, mirakurunPort: String,
    konomiIp: String, konomiPort: String,
    onChannelClick: (Channel?) -> Unit,
    selectedChannel: Channel?,
    onTabChange: (Int) -> Unit,
    initialTabIndex: Int = 0,
    startupFocusTarget: StartupFocusTarget = StartupFocusTarget.SIDEBAR,
    launcherHomeFocusTick: Int = 0,
    selectedProgram: RecordedProgram?,
    onProgramSelected: (RecordedProgram?) -> Unit,
    epgSelectedProgram: EpgProgram?,
    onEpgProgramSelected: (EpgProgram?) -> Unit,
    onReserveSelected: (ReserveItem) -> Unit = {},
    onConditionClick: (ReservationCondition) -> Unit = {},
    isReserveOverlayOpen: Boolean = false,
    isEpgJumpMenuOpen: Boolean,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    triggerBack: Boolean,
    onBackTriggered: () -> Unit,
    onUiReady: () -> Unit,
    isUiReadyFlag: Boolean,
    onNavigateToPlayer: (String, String, String) -> Unit,
    lastPlayerChannelId: String? = null,
    lastPlayerProgramId: String? = null,
    isSettingsOpen: Boolean = false,
    onSettingsToggle: (Boolean) -> Unit = {},
    onOpenAppInfo: () -> Unit = { onSettingsToggle(true) },
    isRecordListOpen: Boolean = false,
    onShowAllRecordings: () -> Unit = {},
    onCloseRecordList: () -> Unit = {},
    onShowSeriesList: () -> Unit = {},
    onShowSmbLibrary: () -> Unit = {},
    onShowOnAir: () -> Unit = {},
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    networkConnectionStatus: NetworkConnectionStatus = NetworkConnectionStatus.Disconnected,
    hasBackendFailure: Boolean = false,
    backendFailureReason: String? = null,
    isBackendRecoveryInProgress: Boolean = false,
    hasActivePlayer: Boolean = false,
    internalNowPlaying: InternalNowPlayingState? = null,
    onReturnToPlayerClick: () -> Unit = {},
    onInternalActivityLaunch: () -> Unit = {},
    onClockClick: () -> Unit = {},
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {},
    overlayReturnFocusTick: Int = 0,
    onOverlayReturnConsumed: () -> Unit = {},
    showUpgradeTour: Boolean = false,
    spotlightTargets: UpgradeSpotlightTargets = rememberUpgradeSpotlightTargets(),
    notificationCenterViewModel: NotificationCenterViewModel = hiltViewModel(),
) {
    val ui = rememberHomeLauncherState(
        initialTabIndex,
        channelViewModel,
        homeViewModel,
        epgViewModel,
        recordViewModel,
        reserveViewModel
    )

    val focusMemory = rememberFocusMemory()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val supportsTvInput = remember(context) {
        com.beeregg2001.komorebi.data.repository.tif.TifInput.isSupported(context)
    }
    var showTvInputs by remember { mutableStateOf(false) }
    var showNotificationCenter by remember { mutableStateOf(false) }
    var notificationDrawerOpenReason by remember { mutableStateOf(NotificationDrawerOpenReason.BELL) }
    var isRestoringStripFocus by remember { mutableStateOf(false) }

    val notificationUiState by notificationCenterViewModel.uiState.collectAsState()
    val effectiveInternalNowPlaying = remember(hasActivePlayer, internalNowPlaying) {
        when {
            !hasActivePlayer -> null
            internalNowPlaying != null -> internalNowPlaying
            else -> InternalNowPlayingState(title = "再生中")
        }
    }
    val mediaPillState = remember(notificationUiState.externalMediaSessions, effectiveInternalNowPlaying) {
        notificationUiState.resolveMediaPillState(effectiveInternalNowPlaying)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        homeViewModel.refreshLauncherApps()
        notificationCenterViewModel.refreshAccessStatus()
    }

    val launcherApps by homeViewModel.launcherApps.collectAsState()
    val inputSourceApp = remember(launcherApps) {
        launcherApps.firstOrNull { homeViewModel.isPinnedSystemApp(it) }
    }

    val backendType by homeViewModel.backendType.collectAsState()
    val shouldCropLogo = remember(backendType) { backendType == "KONOMITV" }

    val hideAppsTab by settingsViewModel.hideAppsTab.collectAsState()
    val currentStartupFocusTarget by settingsViewModel.startupFocusTarget.collectAsState()
    val tifHideEmptyInputs by settingsViewModel.tifHideEmptyInputs.collectAsState()
    val tifShowTunerInputs by settingsViewModel.tifShowTunerInputs.collectAsState()
    val tifActive by settingsViewModel.tifIntegrationActive.collectAsState()
    val useTifPicker = supportsTvInput && tifActive
    val hasInputs = hasVisibleTvInputs(useTifPicker, tifHideEmptyInputs, tifShowTunerInputs)
    val showInputSource = showInputSourceAction(hasInputs, inputSourceApp != null)
    LaunchedEffect(useTifPicker) {
        if (!useTifPicker) showTvInputs = false
    }
    val visibleTabs = remember(hideAppsTab, backendType) {
        LauncherTab.visibleTabs(hideAppsTab, tif = backendType == "TIF")
    }

    val safeTabIndex = initialTabIndex.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
    val requestedTab = visibleTabs.getOrNull(safeTabIndex) ?: LauncherTab.HOME

    val tabController = remember {
        LauncherTabController(
            initialTab = requestedTab,
            initialVisibleTabs = visibleTabs,
        )
    }

    val isOverlayOpen = epgSelectedProgram != null ||
        isSettingsOpen || isRecordListOpen || isReserveOverlayOpen || ui.isSeriesListOpen
    val isFullScreenPlayer = (selectedChannel != null || selectedProgram != null) && !hasActivePlayer
    val isFullScreenMode = isOverlayOpen || isFullScreenPlayer

    LaunchedEffect(visibleTabs) {
        val fallback = tabController.syncVisibleTabs(visibleTabs)
        val syncedIndex = tabController.selectedIndex
        ui.selectedTabIndex = syncedIndex
        if (syncedIndex != initialTabIndex || fallback != null) {
            onTabChange(syncedIndex)
        }
        if (fallback != null && !isFullScreenMode) {
            focusMemory.remember(0)
        }
    }

    LaunchedEffect(initialTabIndex) {
        val target = visibleTabs.getOrNull(initialTabIndex.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0)))
            ?: LauncherTab.HOME
        if (target != tabController.selectedTab) {
            tabController.selectTab(target)
        }
    }

    var syncedActiveTab by remember { mutableStateOf(tabController.activeTab) }
    LaunchedEffect(tabController.activeTab) {
        if (tabController.activeTab != LauncherTab.EPG) {
            ui.isEpgJumping = false
        }
        if (tabController.activeTab != syncedActiveTab) {
            syncedActiveTab = tabController.activeTab
            val committedIndex = tabController.activeIndex
            ui.selectedTabIndex = committedIndex
            ui.onTabSelected(committedIndex, onTabChange, homeViewModel)
        }
    }

    var pendingEnterContentTab by remember { mutableStateOf<LauncherTab?>(null) }
    var railHasFocus by remember { mutableStateOf(false) }
    var isEnteringContentFromRail by remember { mutableStateOf(false) }
    var stripHasFocus by remember { mutableStateOf(false) }
    var suppressInitialRailExpand by remember {
        mutableStateOf(startupFocusTarget == StartupFocusTarget.SIDEBAR)
    }

    LaunchedEffect(isFullScreenMode) {
        if (isFullScreenMode) {
            pendingEnterContentTab = null
            suppressInitialRailExpand = false
        }
    }

    suspend fun handleLauncherBack() {
        pendingEnterContentTab = null
        if (stripHasFocus) {
            tabController.tabRequester(tabController.selectedTab).safeRequestFocus("Back_StripToRail")
            onBackTriggered()
            return
        }
        if (railHasFocus) {
            val activeTab = tabController.selectedTab
            if (tabController.isContentReadyFor(activeTab)) {
                isEnteringContentFromRail = true
                androidx.compose.runtime.withFrameNanos { }
                if (tabController.contentRequester(activeTab).safeRequestFocus("Back_CollapseRail")) {
                    onBackTriggered()
                    return
                }
                isEnteringContentFromRail = false
            }
        }
        val defaultTab = LauncherTab.fromTitle(settingsViewModel.getStartupTabOnce())
            ?.takeIf { it in visibleTabs }
            ?: visibleTabs.first()
        val defaultIndex = visibleTabs.indexOf(defaultTab).coerceAtLeast(0)
        if (railHasFocus && tabController.selectedIndex == defaultIndex) {
            onBackTriggered()
            return
        }
        ui.handleBackNavigation(
            defaultTabIndex = defaultIndex,
            onTabChange = { targetIndex ->
                tabController.selectIndex(targetIndex, immediate = true)
                onTabChange(targetIndex)
            },
            onBackTriggered = onBackTriggered,
            requestTopNavFocus = { targetIndex -> focusMemory.remember(targetIndex) },
            escapeToSafeHouse = { ui.safeHouseRequester.safeRequestFocus("Back_SafeHouse") },
        )
    }

    fun applyReturnFocus(trigger: FocusReturnTrigger): TabFocusAction {
        val currentTab = tabController.selectedTab
        val currentIndex = tabController.selectedIndex
        val action = resolveTabReturnFocus(
            trigger = trigger,
            tab = currentTab,
            tabIndex = currentIndex,
            lastClickedSection = homeViewModel.lastClickedSection,
            lastClickedItemId = homeViewModel.lastClickedItemId,
        )
        when (action) {
            is TabFocusAction.FocusTab -> focusMemory.remember(action.tabIndex)
            is TabFocusAction.FocusContentTop -> focusMemory.requestContent(action.tabIndex)
            is TabFocusAction.FocusItem -> focusMemory.remember(
                action.tabIndex,
                action.section,
                action.itemId,
            )
            is TabFocusAction.DelegateToTabContent -> Unit
            TabFocusAction.NoOp -> Unit
        }
        return action
    }

    HomeResumeFocusEffect {
        if (!isFullScreenMode && !isReturningFromPlayer) {
            applyReturnFocus(FocusReturnTrigger.ACTIVITY_RESUME)
        }
    }

    val returnPlayerFocusRequester = remember { FocusRequester() }
    val inputSourceFocusRequester = remember { FocusRequester() }
    val railSettingsFocusRequester = remember { FocusRequester() }
    val wifiSettingsFocusRequester = remember { FocusRequester() }
    val bellFocusRequester = remember { FocusRequester() }
    val clockFocusRequester = remember { FocusRequester() }
    val displayFlatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val translatedLastChannels = remember(ui.lastChannels, displayFlatChannels) {
        ui.lastChannels.mapNotNull { savedChannel ->
            displayFlatChannels.find {
                if (savedChannel.id.startsWith("tif:")) it.id == savedChannel.id
                else it.networkId == savedChannel.networkId && it.serviceId == savedChannel.serviceId
            } ?: savedChannel.takeUnless { it.id.startsWith("tif:") }
        }
    }

    LaunchedEffect(lastPlayerChannelId) {
        if (lastPlayerChannelId != null) {
            ui.internalLastPlayerChannelId = lastPlayerChannelId
        }
    }

    LaunchedEffect(launcherHomeFocusTick) {
        if (launcherHomeFocusTick > 0) {
            val targetTab = visibleTabs.getOrNull(initialTabIndex.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0)))
                ?: LauncherTab.HOME
            if (targetTab != tabController.selectedTab) {
                tabController.selectTab(targetTab, immediate = true)
            }
            val targetIndex = tabController.selectedIndex
            if (currentStartupFocusTarget == StartupFocusTarget.CONTENT) {
                suppressInitialRailExpand = false
                focusMemory.requestContent(targetIndex)
            } else {
                suppressInitialRailExpand = true
                focusMemory.remember(targetIndex)
            }
        }
    }

    LauncherTabLifecycleHost(
        controller = tabController,
        isFullScreenMode = isFullScreenMode,
        networkAvailable = networkConnectionStatus.isAvailable,
        onEnsureEpgLoaded = { epgViewModel.ensureInitialDataLoaded() },
        onPollingChanged = { shouldPoll ->
            if (shouldPoll) channelViewModel.startPolling()
            else channelViewModel.stopPolling()
        },
        onRefreshHomeData = { homeViewModel.refreshHomeData() },
    )

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            val action = applyReturnFocus(FocusReturnTrigger.AI_RETURN)
            if (action !is TabFocusAction.DelegateToTabContent) {
                onAiReturnConsumed()
            }
        }
    }

    LaunchedEffect(overlayReturnFocusTick, isFullScreenMode) {
        if (overlayReturnFocusTick > 0 && !isFullScreenMode) {
            val action = applyReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS)
            if (action !is TabFocusAction.DelegateToTabContent) {
                onOverlayReturnConsumed()
            }
        }
    }

    LaunchedEffect(isReturningFromPlayer, isFullScreenMode) {
        if (isReturningFromPlayer && !isFullScreenMode) {
            val currentTab = tabController.selectedTab
            if (!currentTab.ownsExternalReturnFocus) {
                val action = applyReturnFocus(FocusReturnTrigger.PLAYER_RETURN)
                if (action is TabFocusAction.DelegateToTabContent) {
                    if (action.consumeImmediately) {
                        // Allow EPG/content one compose cycle to read restoreChannelId before clearing.
                        delay(250)
                        onReturnFocusConsumed()
                    }
                } else {
                    ui.safeHouseRequester.safeRequestFocusWithRetry("SafeHouse_Return")
                }
            }
        }
    }

    var localOverlayReturnTick by remember { mutableIntStateOf(0) }
    val combinedOverlayReturnTick = overlayReturnFocusTick + localOverlayReturnTick
    val consumeOverlayReturn: () -> Unit = {
        localOverlayReturnTick = 0
        onOverlayReturnConsumed()
    }

    HomeOverlayReturnFocusEffect(
        isFullScreen = isFullScreenMode,
        returnInProgress = isReturningFromPlayer || aiFocusReturnTick > 0 || overlayReturnFocusTick > 0,
        onInitialEntry = {
            if (startupFocusTarget == StartupFocusTarget.CONTENT) {
                focusMemory.requestContent(tabController.selectedIndex)
            } else {
                focusMemory.remember(tabController.selectedIndex)
            }
        },
        onRestore = {
            if (applyReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS) is TabFocusAction.DelegateToTabContent) {
                localOverlayReturnTick++
            }
        },
    )

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            handleLauncherBack()
        }
    }

    if (!showUpgradeTour) {
        HomeEntryFocusEffect(
            memory = focusMemory,
            tabRequester = { tabController.tabRequesterAt(it) },
            contentRequester = { tabController.contentRequesterAt(it) },
            contentReadyGeneration = tabController.readyGeneration,
            isContentReady = { tabController.isContentReadyAt(it) },
        )
    }

    var wasShowingUpgradeTour by remember { mutableStateOf(showUpgradeTour) }
    LaunchedEffect(showUpgradeTour, isSettingsOpen) {
        if (wasShowingUpgradeTour && !showUpgradeTour && !isSettingsOpen) {
            withFrameNanos { }
            if (currentStartupFocusTarget == StartupFocusTarget.SIDEBAR) {
                tabController.tabRequester(tabController.selectedTab).safeRequestFocus("UpgradeTourDismiss_Rail")
            } else {
                tabController.contentRequester(tabController.selectedTab).safeRequestFocus("UpgradeTourDismiss_Content")
            }
        }
        wasShowingUpgradeTour = showUpgradeTour
    }

    LaunchedEffect(pendingEnterContentTab, tabController.activeTab, tabController.readyGeneration, isFullScreenMode, railHasFocus) {
        val target = pendingEnterContentTab ?: return@LaunchedEffect
        if (isFullScreenMode || !railHasFocus) {
            pendingEnterContentTab = null
            return@LaunchedEffect
        }
        if (tabController.isContentReadyFor(target)) {
            pendingEnterContentTab = null
            withFrameNanos { }
            tabController.contentRequester(target).safeRequestFocusWithRetry("RailEnterContent_${target.name}")
        }
    }

    LaunchedEffect(focusMemory.completedId) {
        if (focusMemory.completedId > 0 && isReturningFromPlayer &&
            !tabController.selectedTab.ownsExternalReturnFocus
        ) {
            onReturnFocusConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .graphicsLayer { alpha = 0f }
                .focusRequester(ui.safeHouseRequester)
                .focusable()
        )

        var isContentScrolled by remember(tabController.activeTab) { mutableStateOf(false) }
        val isCompactStrip = isContentScrolled && !stripHasFocus && !showNotificationCenter && !isRestoringStripFocus
        val showStripActions = !isCompactStrip
        val isEpgJumpingActive = tabController.selectedTab == LauncherTab.EPG && ui.isEpgJumping
        val topBarEntryRequester = when {
            mediaPillState !is LauncherMediaPillState.None -> returnPlayerFocusRequester
            !showStripActions -> wifiSettingsFocusRequester
            showInputSource -> inputSourceFocusRequester
            else -> ui.settingsFocusRequester
        }
        val stripDownRequester = if (tabController.isActiveContentReady) {
            tabController.contentRequester(tabController.selectedTab)
        } else {
            FocusRequester.Cancel
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (!isFullScreenMode) TvRailCollapsedWidth else 0.dp)
                .onGloballyPositioned { spotlightTargets.contentStageBounds = it.boundsInRoot() }
        ) {
            AnimatedContent(
                targetState = tabController.activeSnapshot,
                transitionSpec = {
                    launcherTabContentTransform(tabController.transitionDirection)
                },
                contentKey = { "${it.tab.name}-${it.generation}" },
                label = "launcher_tab_content",
                modifier = Modifier.fillMaxSize(),
            ) { snapshot ->
                    val isCurrentSnapshot = snapshot.generation == tabController.generation
                    val snapshotTabIndex = visibleTabs.indexOf(snapshot.tab).coerceAtLeast(0)
                    val snapshotTabRequester = tabController.tabRequester(snapshot.tab)
                    val snapshotContentRequester = tabController.contentRequester(snapshot.tab)
                    val snapshotReady: () -> Unit = remember(snapshot.generation) {
                        {
                            if (tabController.markContentReady(snapshot.generation)) {
                                onUiReady()
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties {
                                canFocus = isCurrentSnapshot &&
                                    !isFullScreenMode &&
                                    !showTvInputs &&
                                    !showNotificationCenter &&
                                    !showUpgradeTour &&
                                    (!railHasFocus || isEnteringContentFromRail || pendingEnterContentTab != null)
                            },
                    ) {
                        val openOnAirTab: () -> Unit = {
                            val onAirIndex = visibleTabs.indexOf(LauncherTab.ON_AIR)
                            if (onAirIndex != -1) {
                                suppressInitialRailExpand = false
                                tabController.selectTab(LauncherTab.ON_AIR, immediate = true)
                                ui.selectedTabIndex = onAirIndex
                                ui.onTabSelected(onAirIndex, onTabChange, homeViewModel)
                                focusMemory.requestContent(onAirIndex)
                            } else {
                                onShowOnAir()
                            }
                        }

                        when (snapshot.tab) {
                            LauncherTab.HOME -> HomeContents(
                                lastWatchedChannels = translatedLastChannels,
                                watchHistory = ui.watchHistory,
                                hotChannels = ui.hotChannels,
                                upcomingReserves = ui.upcomingReserves,
                                genrePickup = ui.genrePickup,
                                pickupGenreName = ui.pickupGenreLabel,
                                pickupTimeSlot = ui.genrePickupTimeSlot,
                                groupedChannels = groupedChannels,
                                getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                                shouldCropLogo = shouldCropLogo,
                                onChannelClick = onChannelClick,
                                onHistoryClick = { historyItem ->
                                    val programId = historyItem.program.id.toIntOrNull()
                                    val betterProgram = ui.recentRecordings.find { it.id == programId }
                                    onProgramSelected(
                                        betterProgram?.copy(playbackPosition = historyItem.playback_position)
                                            ?: KonomiDataMapper.toDomainModel(historyItem)
                                    )
                                },
                                onReserveClick = onReserveSelected,
                                onProgramClick = { onEpgProgramSelected(it) },
                                onNavigateToTab = { targetTitle ->
                                    val targetTab = LauncherTab.fromTitle(targetTitle)
                                        ?.takeIf { it in visibleTabs }
                                    if (targetTab != null) {
                                        val targetIndex = visibleTabs.indexOf(targetTab)
                                        suppressInitialRailExpand = false
                                        tabController.selectTab(targetTab, immediate = true)
                                        ui.selectedTabIndex = targetIndex
                                        ui.onTabSelected(targetIndex, onTabChange, homeViewModel)
                                        focusMemory.requestContent(targetIndex)
                                    }
                                },
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                tabFocusRequester = snapshotTabRequester,
                                externalFocusRequester = snapshotContentRequester,
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                lastFocusedProgramId = lastPlayerProgramId,
                                isTopNavFocused = ui.topNavHasFocus,
                                onUiReady = snapshotReady,
                                homeViewModel = homeViewModel,
                                timeFormat = timeFormat,
                                focusMemory = focusMemory,
                                tabIndex = snapshotTabIndex,
                                topBarFocusRequester = topBarEntryRequester,
                                onShowAllRecordings = onShowAllRecordings,
                                onShowOnAir = openOnAirTab,
                                hideAppsTab = hideAppsTab,
                                onScrolledChanged = { isContentScrolled = it },
                            )

                            LauncherTab.LIVE -> LiveContent(
                                channelViewModel = channelViewModel,
                                epgViewModel = epgViewModel,
                                groupedChannels = groupedChannels,
                                onChannelClick = onChannelClick,
                                onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                topNavFocusRequester = snapshotTabRequester,
                                topBarFocusRequester = topBarEntryRequester,
                                contentFirstItemRequester = snapshotContentRequester,
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && isCurrentSnapshot,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (isCurrentSnapshot) aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                overlayReturnFocusTick = if (isCurrentSnapshot) combinedOverlayReturnTick else 0,
                                onOverlayReturnConsumed = consumeOverlayReturn,
                                onContentReady = snapshotReady,
                                onScrolledChanged = { isContentScrolled = it },
                            )

                            LauncherTab.APPS -> AppsTabContent(
                                homeViewModel = homeViewModel,
                                tabFocusRequester = snapshotTabRequester,
                                topBarFocusRequester = topBarEntryRequester,
                                contentFirstItemRequester = snapshotContentRequester,
                                isTopNavFocused = ui.topNavHasFocus,
                                onUiReady = snapshotReady,
                                focusMemory = focusMemory,
                                tabIndex = snapshotTabIndex,
                                onScrolledChanged = { isContentScrolled = it },
                            )

                            LauncherTab.VIDEO -> VideoTabContent(
                                recordViewModel = recordViewModel,
                                onProgramClick = { onProgramSelected(it) },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                onShowSmbLibrary = onShowSmbLibrary,
                                onShowOnAir = openOnAirTab,
                                topBarFocusRequester = topBarEntryRequester,
                                openedSeriesTitle = ui.openedSeriesTitle,
                                onOpenedSeriesTitleChange = { ui.openedSeriesTitle = it },
                                tabFocusRequester = snapshotTabRequester,
                                contentFirstItemRequester = snapshotContentRequester,
                                isTopNavFocused = ui.topNavHasFocus,
                                isReturningFromPlayer = isReturningFromPlayer && isCurrentSnapshot,
                                lastPlayedProgramId = lastPlayerProgramId,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                                shouldCropLogo = shouldCropLogo,
                                timeFormat = timeFormat,
                                watchHistory = ui.watchHistory,
                                isNetworkAvailable = networkConnectionStatus.isAvailable,
                                aiFocusReturnTick = if (isCurrentSnapshot) aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                overlayReturnFocusTick = if (isCurrentSnapshot) combinedOverlayReturnTick else 0,
                                onOverlayReturnConsumed = consumeOverlayReturn,
                                onContentReady = snapshotReady,
                                onScrolledChanged = { isContentScrolled = it },
                            )

                            LauncherTab.ON_AIR -> com.beeregg2001.komorebi.ui.onair.OnAirScreen(
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                timeFormat = timeFormat,
                                onProgramClick = { onProgramSelected(it) },
                                onBack = {
                                    snapshotTabRequester.safeRequestFocus(TAG)
                                },
                                onSettings = { onSettingsToggle(true) },
                                initialFocusRequester = snapshotContentRequester,
                                isReturningFromPlayer = isReturningFromPlayer && isCurrentSnapshot,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                topFocusRequester = topBarEntryRequester,
                                leftNavFocusRequester = snapshotTabRequester,
                                isEmbeddedTab = true,
                                autoRequestInitialFocus = false,
                                onContentReady = snapshotReady,
                            )

                            LauncherTab.EPG -> EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = snapshotTabRequester,
                                topBarFocusRequester = topBarEntryRequester,
                                contentRequester = snapshotContentRequester,
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen,
                                onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer,
                                currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && isCurrentSnapshot) {
                                    lastPlayerChannelId
                                } else {
                                    null
                                },
                                availableTypes = groupedChannels.keys.toList(),
                                onJumpStateChanged = { ui.isEpgJumping = it },
                                reserves = ui.reserves,
                                onUpdateTargetTime = { epgViewModel.updateTargetTime(it) },
                                searchQuery = epgViewModel.searchQuery.collectAsState().value,
                                searchHistory = epgViewModel.searchHistory.collectAsState().value,
                                onSearchQueryChange = { epgViewModel.updateSearchQuery(it) },
                                onExecuteSearch = { epgViewModel.executeSearch(it) },
                                activeSearchQuery = epgViewModel.activeSearchQuery.collectAsState().value,
                                searchResults = epgViewModel.searchResults.collectAsState().value,
                                isSearching = epgViewModel.isSearching.collectAsState().value,
                                onClearSearch = { epgViewModel.clearSearch() },
                                timeFormat = timeFormat,
                                onContentReady = snapshotReady,
                            )

                            LauncherTab.RESERVE -> ReserveListScreen(
                                onBack = {
                                    snapshotTabRequester.safeRequestFocus(TAG)
                                },
                                onProgramClick = onReserveSelected,
                                onConditionClick = onConditionClick,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                contentFirstItemRequester = snapshotContentRequester,
                                topNavFocusRequester = snapshotTabRequester,
                                topBarFocusRequester = topBarEntryRequester,
                                groupedChannels = groupedChannels,
                                isReserveOverlayOpen = isReserveOverlayOpen,
                                isTopNavFocused = ui.topNavHasFocus,
                                isReturningFromPlayer = isReturningFromPlayer && isCurrentSnapshot,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (isCurrentSnapshot) aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = snapshotReady,
                            )
                        }
                    }
                }

            if (!isFullScreenMode) {
                val connectionFeedbackState = homeConnectionFeedbackState(
                    networkAvailable = networkConnectionStatus.isAvailable,
                    hasBackendFailure = hasBackendFailure,
                    backendFailureReason = backendFailureReason,
                    recoveryInProgress = isBackendRecoveryInProgress,
                )
                LauncherSystemStrip(
                    timeFormat = timeFormat,
                    mediaPillState = mediaPillState,
                    showInputSource = showInputSource,
                    networkConnectionStatus = networkConnectionStatus,
                    connectionFeedbackKind = connectionFeedbackState.kind,
                    canTakeFocus = !isEpgJumpingActive && !showUpgradeTour && !showNotificationCenter,
                    returnPlayerFocusRequester = returnPlayerFocusRequester,
                    inputSourceFocusRequester = inputSourceFocusRequester,
                    wifiSettingsFocusRequester = wifiSettingsFocusRequester,
                    systemSettingsFocusRequester = ui.settingsFocusRequester,
                    leftEdgeFocusRequester = tabController.tabRequester(tabController.selectedTab),
                    downFocusRequester = stripDownRequester,
                    onMediaPillClick = {
                        when (mediaPillState) {
                            is LauncherMediaPillState.InternalMiniPlayer -> onReturnToPlayerClick()
                            is LauncherMediaPillState.ExternalMedia -> {
                                notificationDrawerOpenReason = NotificationDrawerOpenReason.MEDIA_PILL
                                showNotificationCenter = true
                            }
                            LauncherMediaPillState.None -> Unit
                        }
                    },
                    onLaunchInputSourcePicker = {
                        if (useTifPicker) showTvInputs = true
                        else inputSourceApp?.let {
                            onInternalActivityLaunch()
                            homeViewModel.launchInputSourcePicker(it)
                        }
                    },
                    onLaunchWifiSettings = {
                        onInternalActivityLaunch()
                        homeViewModel.launchWifiSettings()
                    },
                    onLaunchSystemSettings = {
                        onInternalActivityLaunch()
                        homeViewModel.launchSystemSettings()
                    },
                    onHasFocusChanged = { hasFocus ->
                        stripHasFocus = hasFocus
                        if (hasFocus) {
                            pendingEnterContentTab = null
                            isEnteringContentFromRail = false
                            tabController.cancelPendingSelection()
                            ui.selectedTabIndex = tabController.selectedIndex
                        }
                        ui.topNavHasFocus = railHasFocus || hasFocus
                    },
                    onBack = { scope.launch { handleLauncherBack() } },
                    notificationCount = notificationUiState.notificationCount,
                    bellFocusRequester = bellFocusRequester,
                    onBellClick = {
                        notificationDrawerOpenReason = NotificationDrawerOpenReason.BELL
                        showNotificationCenter = true
                    },
                    clockFocusRequester = clockFocusRequester,
                    onClockClick = onClockClick,
                    isContentReady = tabController.isActiveContentReady,
                    isCompactStatusOnly = isCompactStrip,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 24.dp)
                        .onGloballyPositioned { spotlightTargets.systemStripBounds = it.boundsInRoot() }
                        .zIndex(25f),
                )
            }
        }

        if (!isFullScreenMode) {
            LauncherNavRail(
                controller = tabController,
                isEpgJumping = isEpgJumpingActive,
                upEdgeFocusRequester = topBarEntryRequester,
                settingsItemFocusRequester = railSettingsFocusRequester,
                settingsCanFocus = !isEpgJumpingActive && !showUpgradeTour,
                onTabFocused = { tab ->
                    focusMemory.clear()
                    isEnteringContentFromRail = false
                    if (pendingEnterContentTab != null && pendingEnterContentTab != tab) {
                        pendingEnterContentTab = null
                    }
                    tabController.selectTab(tab)
                    ui.selectedTabIndex = tabController.selectedIndex
                    ui.topNavHasFocus = true
                },
                onTabActivated = { tab ->
                    focusMemory.clear()
                    isEnteringContentFromRail = true
                    tabController.selectTab(tab, immediate = true)
                    val targetIndex = tabController.selectedIndex
                    ui.selectedTabIndex = targetIndex
                    syncedActiveTab = tabController.activeTab
                    ui.onTabSelected(targetIndex, onTabChange, homeViewModel)
                    if (tabController.isContentReadyFor(tab)) {
                        pendingEnterContentTab = null
                        tabController.contentRequester(tab).safeRequestFocus(TAG)
                    } else {
                        pendingEnterContentTab = tab
                    }
                },
                onOpenSettings = { onSettingsToggle(true) },
                onOpenAppInfo = onOpenAppInfo,
                onSettingsFocused = {
                    pendingEnterContentTab = null
                    isEnteringContentFromRail = false
                    tabController.cancelPendingSelection()
                    ui.selectedTabIndex = tabController.selectedIndex
                },
                onPrepareEnterContent = { isEnteringContentFromRail = true },
                onHasFocusChanged = { hasFocus ->
                    val hadFocus = railHasFocus
                    railHasFocus = hasFocus
                    if (hadFocus && !hasFocus) {
                        isEnteringContentFromRail = false
                        tabController.cancelPendingSelection()
                        ui.selectedTabIndex = tabController.selectedIndex
                    } else if (!hasFocus) {
                        isEnteringContentFromRail = false
                    }
                    ui.topNavHasFocus = hasFocus || stripHasFocus
                },
                onBack = { scope.launch { handleLauncherBack() } },
                startCollapsed = suppressInitialRailExpand,
                onStartCollapsedConsumed = { suppressInitialRailExpand = false },
                onSelectedTabBoundsChanged = { spotlightTargets.navRailBounds = it },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .zIndex(30f),
            )
        }
        if (showTvInputs) {
            TvInputSourceDialog(
                onOpenChannel = { channel ->
                    showTvInputs = false
                    onChannelClick(channel)
                },
                onSystemPicker = inputSourceApp?.let { app ->
                    { showTvInputs = false; homeViewModel.launchInputSourcePicker(app) }
                },
                hideEmptyInputs = tifHideEmptyInputs,
                showTunerChannels = tifShowTunerInputs,
                onDismiss = {
                    showTvInputs = false
                    scope.launch {
                        androidx.compose.runtime.withFrameNanos { }
                        inputSourceFocusRequester.safeRequestFocus("TvInputDismiss")
                    }
                },
            )
        }
        if (showNotificationCenter) {
            NotificationMediaDrawer(
                uiState = notificationUiState,
                internalNowPlaying = effectiveInternalNowPlaying,
                openReason = notificationDrawerOpenReason,
                timeFormat = timeFormat,
                onReturnToInternalPlayer = {
                    showNotificationCenter = false
                    onReturnToPlayerClick()
                },
                onSendMediaAction = { session, action ->
                    notificationCenterViewModel.sendMediaAction(session, action)
                },
                onOpenMediaApp = { session ->
                    val launched = notificationCenterViewModel.openMediaApp(session, onInternalActivityLaunch)
                    if (launched) showNotificationCenter = false
                },
                onOpenNotification = { item ->
                    val launched = notificationCenterViewModel.openNotification(item, onInternalActivityLaunch)
                    if (launched) showNotificationCenter = false
                },
                onDismissNotification = { key ->
                    notificationCenterViewModel.dismissNotification(key)
                },
                onClearAllNotifications = {
                    notificationCenterViewModel.clearAllClearable()
                },
                onOpenNotificationSettings = {
                    notificationCenterViewModel.openNotificationListenerSettings(context, onInternalActivityLaunch)
                },
                onRefreshAccessStatus = {
                    notificationCenterViewModel.refreshAccessStatus()
                },
                onDismiss = {
                    val opener = if (notificationDrawerOpenReason == NotificationDrawerOpenReason.MEDIA_PILL) {
                        returnPlayerFocusRequester
                    } else {
                        bellFocusRequester
                    }
                    isRestoringStripFocus = true
                    showNotificationCenter = false
                    scope.launch {
                        withFrameNanos { }
                        val restored = opener.safeRequestFocus("NotificationDrawerDismiss_Opener") ||
                            bellFocusRequester.safeRequestFocus("NotificationDrawerDismiss_Bell") ||
                            wifiSettingsFocusRequester.safeRequestFocus("NotificationDrawerDismiss_Wifi") ||
                            clockFocusRequester.safeRequestFocus("NotificationDrawerDismiss_Clock")
                        if (!restored) {
                            tabController.tabRequester(tabController.selectedTab).safeRequestFocus("NotificationDrawerDismiss_Rail")
                        }
                        withFrameNanos { }
                        isRestoringStripFocus = false
                    }
                },
            )
        }
    }
}

private fun launcherTabContentTransform(direction: TabTransitionDirection): ContentTransform {
    val sign = direction.sign
    return if (sign == 0) {
        fadeIn(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing))
    } else {
        val enter = fadeIn(
            animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        ) + slideInVertically(
            animationSpec = tween(durationMillis = 210, easing = FastOutSlowInEasing),
            initialOffsetY = { height -> sign * (height / 24).coerceIn(18, 36) },
        )
        val exit = fadeOut(
            animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        ) + slideOutVertically(
            animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            targetOffsetY = { height -> -sign * (height / 32).coerceIn(12, 24) },
        )
        enter togetherWith exit
    }
}
