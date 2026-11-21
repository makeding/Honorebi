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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.epg.EpgNavigationContainer
import com.beeregg2001.komorebi.ui.main.NetworkConnectionStatus
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import com.beeregg2001.komorebi.ui.reserve.ReserveListScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "HomeLauncher"
internal const val NETWORK_STATUS_BUTTON_SIZE_DP = 48
internal const val NETWORK_STATUS_BUTTON_TEST_TAG = "network-status-button"
private val RETURN_PLAYER_SLOT_WIDTH = 112.dp

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

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(timeFormat: String, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
    val formattedTime = currentTime.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))

    Text(
        text = formattedTime,
        color = KomorebiTheme.colors.textPrimary,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
        modifier = modifier
    )
}

@Composable
private fun PinnedSystemAppButton(
    app: LauncherApp,
    focusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    canMoveDown: Boolean,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .focusRequester(focusRequester)
            .focusProperties {
                left = FocusRequester.Cancel
                right = rightFocusRequester
                down = if (canMoveDown) downFocusRequester else FocusRequester.Default
                up = FocusRequester.Cancel
            },
        colors = IconButtonDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.06f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        )
    ) {
        Icon(
            Icons.Default.SettingsInputHdmi,
            contentDescription = app.label,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
internal fun NetworkStatusButton(
    status: NetworkConnectionStatus,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    canTakeFocus: Boolean,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val (icon, description) = when (status.transport) {
        NetworkTransport.WIFI -> Icons.Default.Wifi to "Wi-Fi 設定"
        NetworkTransport.ETHERNET -> Icons.Default.SettingsEthernet to "有線ネットワーク設定"
        NetworkTransport.OTHER -> Icons.Default.Wifi to "ネットワーク設定"
        NetworkTransport.DISCONNECTED -> Icons.Default.WifiOff to "オフライン。Wi-Fi 設定"
    }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(NETWORK_STATUS_BUTTON_SIZE_DP.dp)
            .testTag(NETWORK_STATUS_BUTTON_TEST_TAG)
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
                up = FocusRequester.Cancel
                canFocus = canTakeFocus
            },
        colors = IconButtonDefaults.colors(
            containerColor = if (status.isAvailable) Color.Transparent
            else colors.accent.copy(alpha = 0.16f),
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
            contentColor = if (status.isAvailable) colors.textSecondary else colors.accent,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
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
    onReturnToPlayerClick: () -> Unit = {},
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {}
) {
    val ui = rememberHomeLauncherState(
        initialTabIndex,
        channelViewModel,
        homeViewModel,
        epgViewModel,
        recordViewModel,
        reserveViewModel
    )
    val colors = KomorebiTheme.colors

    val focusMemory = rememberFocusMemory()
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        homeViewModel.refreshLauncherApps()
    }

    val launcherApps by homeViewModel.launcherApps.collectAsState()
    val inputSourceApp = remember(launcherApps) {
        launcherApps.firstOrNull { homeViewModel.isPinnedSystemApp(it) }
    }

    val backendType by homeViewModel.backendType.collectAsState()
    val shouldCropLogo = remember(backendType) { backendType == "KONOMITV" }

    val hideAppsTab by settingsViewModel.hideAppsTab.collectAsState()
    val visibleTabs = remember(hideAppsTab) { LauncherTab.visibleTabs(hideAppsTab) }

    val safeTabIndex = initialTabIndex.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
    val requestedTab = visibleTabs.getOrNull(safeTabIndex) ?: LauncherTab.HOME

    val tabController = remember {
        LauncherTabController(
            initialTab = requestedTab,
            initialVisibleTabs = visibleTabs,
        )
    }

    LaunchedEffect(visibleTabs) {
        val fallback = tabController.syncVisibleTabs(visibleTabs)
        if (fallback != null) {
            ui.selectedTabIndex = 0
            onTabChange(0)
        }
    }

    LaunchedEffect(requestedTab, visibleTabs) {
        tabController.selectTab(requestedTab)
    }

    suspend fun handleLauncherBack() {
        val defaultTab = LauncherTab.fromTitle(settingsViewModel.getStartupTabOnce())
            ?.takeIf { it in visibleTabs }
            ?: visibleTabs.first()
        val defaultIndex = visibleTabs.indexOf(defaultTab).coerceAtLeast(0)
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

    val isFullScreenMode = ui.isFullScreen(
        selectedChannel, selectedProgram, epgSelectedProgram,
        isSettingsOpen, isRecordListOpen, isReserveOverlayOpen
    ) && !hasActivePlayer

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
    val systemSettingsFocusRequester = remember { FocusRequester() }
    val wifiSettingsFocusRequester = remember { FocusRequester() }
    val displayFlatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    val translatedLastChannels = remember(ui.lastChannels, displayFlatChannels) {
        ui.lastChannels.map { savedChannel ->
            displayFlatChannels.find {
                it.networkId == savedChannel.networkId && it.serviceId == savedChannel.serviceId
            } ?: savedChannel
        }
    }

    LaunchedEffect(lastPlayerChannelId) {
        if (lastPlayerChannelId != null) {
            ui.internalLastPlayerChannelId = lastPlayerChannelId
        }
    }

    LaunchedEffect(launcherHomeFocusTick) {
        if (launcherHomeFocusTick > 0) {
            applyReturnFocus(FocusReturnTrigger.LAUNCHER_HOME)
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

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer && !isFullScreenMode) {
            val currentTab = tabController.selectedTab
            if (!currentTab.ownsExternalReturnFocus) {
                ui.safeHouseRequester.safeRequestFocusWithRetry("SafeHouse_Return")
                val action = applyReturnFocus(FocusReturnTrigger.PLAYER_RETURN)
                if (action is TabFocusAction.DelegateToTabContent && action.consumeImmediately) {
                    onReturnFocusConsumed()
                }
            }
        }
    }

    HomeOverlayReturnFocusEffect(isFullScreenMode, isReturningFromPlayer || aiFocusReturnTick > 0) {
        applyReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS)
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            handleLauncherBack()
        }
    }

    HomeEntryFocusEffect(
        memory = focusMemory,
        tabRequester = { tabController.tabRequesterAt(it) },
        contentRequester = { tabController.contentRequesterAt(it) },
        contentReadyGeneration = tabController.readyGeneration,
        isContentReady = { tabController.isContentReadyAt(it) },
    )

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

        Column(modifier = Modifier.fillMaxSize()) {
            if (!isFullScreenMode) {
                val isEpgJumpingActive = tabController.selectedTab == LauncherTab.EPG && ui.isEpgJumping
                val firstVisibleTab = visibleTabs.firstOrNull() ?: LauncherTab.HOME
                val lastVisibleTab = visibleTabs.lastOrNull() ?: LauncherTab.HOME

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(top = 8.dp, start = 40.dp, end = 40.dp)
                        .onFocusChanged { ui.topNavHasFocus = it.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                                scope.launch { handleLauncherBack() }
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DigitalClock(timeFormat = timeFormat)
                    Spacer(modifier = Modifier.width(24.dp))
                    if (inputSourceApp != null) {
                        PinnedSystemAppButton(
                            app = inputSourceApp,
                            focusRequester = inputSourceFocusRequester,
                            rightFocusRequester = tabController.tabRequester(firstVisibleTab),
                            downFocusRequester = tabController.contentRequester(tabController.selectedTab),
                            canMoveDown = tabController.isActiveContentReady,
                            onClick = { homeViewModel.launchInputSourcePicker(inputSourceApp) }
                        )
                        Spacer(modifier = Modifier.width(18.dp))
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    LauncherTabBar(
                        controller = tabController,
                        isEpgJumping = ui.isEpgJumping,
                        leftEdgeFocusRequester = if (inputSourceApp != null) {
                            inputSourceFocusRequester
                        } else {
                            FocusRequester.Cancel
                        },
                        rightEdgeFocusRequester = if (hasActivePlayer) {
                            returnPlayerFocusRequester
                        } else {
                            systemSettingsFocusRequester
                        },
                        onTabFocused = { tab ->
                            tabController.selectTab(tab)
                            val targetIndex = tabController.selectedIndex
                            ui.selectedTabIndex = targetIndex
                            ui.onTabSelected(targetIndex, onTabChange, homeViewModel)
                            ui.topNavHasFocus = true
                        },
                        onUpKey = inputSourceApp?.let { app ->
                            {
                                homeViewModel.launchInputSourcePicker(app)
                                true
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    // 「再生中」ボタンの有無で上部バーやタブ列がリフローしないよう、
                    // 常に同じ幅のスロットを確保しておく。
                    Box(
                        modifier = Modifier.width(RETURN_PLAYER_SLOT_WIDTH),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasActivePlayer) {
                            Button(
                                onClick = onReturnToPlayerClick,
                                modifier = Modifier
                                    .focusRequester(returnPlayerFocusRequester)
                                    .focusProperties {
                                        left = tabController.tabRequester(lastVisibleTab)
                                        right = systemSettingsFocusRequester
                                        canFocus = !isEpgJumpingActive
                                        up = FocusRequester.Cancel
                                    },
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.accent.copy(alpha = 0.2f),
                                    focusedContainerColor = colors.accent,
                                    contentColor = colors.accent,
                                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                                ),
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(20.dp)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "再生中",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "再生中",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))

                    IconButton(
                        onClick = { onSettingsToggle(true) },
                        modifier = Modifier
                            .focusRequester(systemSettingsFocusRequester)
                            .focusProperties {
                                left = if (hasActivePlayer) {
                                    returnPlayerFocusRequester
                                } else {
                                    tabController.tabRequester(lastVisibleTab)
                                }
                                right = wifiSettingsFocusRequester
                                canFocus = !isEpgJumpingActive
                                up = FocusRequester.Cancel
                            },
                        colors = IconButtonDefaults.colors(
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
                            contentColor = colors.textSecondary
                        )
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "設定")
                    }
                    Spacer(modifier = Modifier.width(8.dp))

                    NetworkStatusButton(
                        status = networkConnectionStatus,
                        focusRequester = wifiSettingsFocusRequester,
                        leftFocusRequester = systemSettingsFocusRequester,
                        rightFocusRequester = ui.settingsFocusRequester,
                        canTakeFocus = !isEpgJumpingActive,
                        onClick = { homeViewModel.launchWifiSettings() }
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { homeViewModel.launchSystemSettings() },
                        modifier = Modifier
                            .focusRequester(ui.settingsFocusRequester)
                            .focusProperties {
                                left = wifiSettingsFocusRequester
                                canFocus = !isEpgJumpingActive
                                up = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            },
                        colors = IconButtonDefaults.colors(
                            focusedContainerColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White,
                            contentColor = colors.textSecondary
                        )
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "システム設定")
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
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
                            .focusProperties { canFocus = isCurrentSnapshot },
                    ) {
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
                                        tabController.selectTab(targetTab)
                                        tabController.tabRequester(targetTab).safeRequestFocus(TAG)
                                        ui.onTabSelected(targetIndex, onTabChange, homeViewModel)
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
                                contentFirstItemRequester = snapshotContentRequester,
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && isCurrentSnapshot,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (isCurrentSnapshot) aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = snapshotReady,
                            )

                            LauncherTab.APPS -> AppsTabContent(
                                homeViewModel = homeViewModel,
                                tabFocusRequester = snapshotTabRequester,
                                contentFirstItemRequester = snapshotContentRequester,
                                onUiReady = snapshotReady,
                                focusMemory = focusMemory,
                                tabIndex = snapshotTabIndex,
                            )

                            LauncherTab.VIDEO -> VideoTabContent(
                                recordViewModel = recordViewModel,
                                onProgramClick = { onProgramSelected(it) },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                onShowSmbLibrary = onShowSmbLibrary,
                                onShowOnAir = onShowOnAir,
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
                                timeFormat = timeFormat,
                                watchHistory = ui.watchHistory,
                                isNetworkAvailable = networkConnectionStatus.isAvailable,
                                aiFocusReturnTick = if (isCurrentSnapshot) aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = snapshotReady,
                            )

                            LauncherTab.EPG -> EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = snapshotTabRequester,
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
                                groupedChannels = groupedChannels,
                                isReserveOverlayOpen = isReserveOverlayOpen,
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

                HomeConnectionFeedback(
                    state = homeConnectionFeedbackState(
                        networkAvailable = networkConnectionStatus.isAvailable,
                        hasBackendFailure = hasBackendFailure,
                        backendFailureReason = backendFailureReason,
                        recoveryInProgress = isBackendRecoveryInProgress,
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 48.dp, end = 48.dp),
                )
            }
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
        ) + slideInHorizontally(
            animationSpec = tween(durationMillis = 210, easing = FastOutSlowInEasing),
            initialOffsetX = { width -> sign * (width / 24).coerceIn(18, 36) },
        )
        val exit = fadeOut(
            animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        ) + slideOutHorizontally(
            animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            targetOffsetX = { width -> -sign * (width / 32).coerceIn(12, 24) },
        )
        enter togetherWith exit
    }
}
