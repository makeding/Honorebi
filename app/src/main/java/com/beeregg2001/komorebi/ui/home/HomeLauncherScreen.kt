@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
    onConnectionSettings: () -> Unit = { onSettingsToggle(true) },
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
    onRetryBackendConnection: () -> Unit = {},
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
    val tabs = remember(hideAppsTab) { visibleHomeTabs(hideAppsTab) }

    val safeTabIndex = initialTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    val requestedContentKey = tabs.getOrNull(safeTabIndex) ?: "ホーム"
    val renderState = remember { HomeTabRenderState(safeTabIndex, requestedContentKey) }
    val activeRenderIndex = renderState.activeIndex
    val activeRenderGeneration = renderState.generation

    suspend fun handleLauncherBack() {
        val defaultIndex = tabs.indexOf(settingsViewModel.getStartupTabOnce()).coerceAtLeast(0)
        ui.handleBackNavigation(
            defaultTabIndex = defaultIndex,
            onTabChange = onTabChange,
            onBackTriggered = onBackTriggered,
            requestTopNavFocus = { targetIndex -> focusMemory.remember(targetIndex) },
            escapeToSafeHouse = { ui.safeHouseRequester.safeRequestFocus("Back_SafeHouse") },
        )
    }

    // 各タブの先頭コンテンツのフォーカス先。
    fun firstContentRequesterFor(index: Int): FocusRequester =
        ui.contentFirstItemRequesters.getOrNull(index) ?: FocusRequester.Default

    // Only the currently rendered generation can unlock DOWN navigation. A late callback from
    // a disposed tab cannot make the new tab look ready.
    val handleUiReady: () -> Unit = remember(activeRenderGeneration) {
        {
            if (renderState.markContentReady(activeRenderGeneration)) onUiReady()
        }
    }

    val isFullScreenMode = ui.isFullScreen(
        selectedChannel, selectedProgram, epgSelectedProgram,
        isSettingsOpen, isRecordListOpen, isReserveOverlayOpen
    ) && !hasActivePlayer

    HomeResumeFocusEffect {
        if (!isFullScreenMode && !isReturningFromPlayer) {
            val currentTabName = tabs.getOrNull(safeTabIndex)
            val itemId = homeViewModel.lastClickedItemId
            if (currentTabName == "アプリ" &&
                homeViewModel.lastClickedSection == "apps" && itemId != null
            ) {
                focusMemory.remember(safeTabIndex, "apps", itemId)
            } else {
                focusMemory.remember(safeTabIndex)
            }
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

    LaunchedEffect(tabs.size) {
        if (ui.selectedTabIndex >= tabs.size) {
            ui.selectedTabIndex = 0
            onTabChange(0)
        }
    }

    LaunchedEffect(lastPlayerChannelId) {
        if (lastPlayerChannelId != null) {
            ui.internalLastPlayerChannelId = lastPlayerChannelId
        }
    }

    LaunchedEffect(launcherHomeFocusTick) {
        if (launcherHomeFocusTick > 0) {
            focusMemory.remember(safeTabIndex)
        }
    }

    // =====================================================================================
    // ★ 究極の軽量化ロジック: デバウンス・レンダリング（Debounce Rendering）
    // 連打中は画面を構築せず、指が止まってから60ms後に初めてコンテンツを描画する。
    // 裏側のタブは完全に破棄するため、フォーカスの迷子や競合は物理的に発生しません。
    // =====================================================================================
    HomeTabRenderEffect(safeTabIndex, requestedContentKey, renderState)

    var lastHomeRefreshTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(activeRenderIndex, networkConnectionStatus.isAvailable) {
        val currentLabel = renderState.activeContentKey

        // Offline mode can still render cached EPG data, so establish the lazy
        // subscription before the network-only polling guard below.
        if (currentLabel == "番組表") epgViewModel.ensureInitialDataLoaded()

        if (!networkConnectionStatus.isAvailable) {
            channelViewModel.stopPolling()
            return@LaunchedEffect
        }

        when (currentLabel) {
            "ホーム" -> {
                channelViewModel.startPolling()
                val now = System.currentTimeMillis()
                if (now - lastHomeRefreshTime > 300_000L) {
                    homeViewModel.refreshHomeData()
                    lastHomeRefreshTime = now
                }
            }

            "ライブ" -> {
                channelViewModel.startPolling()
            }

            "録画予約" -> {
                channelViewModel.stopPolling()
            }

            "番組表" -> {
                channelViewModel.stopPolling()
            }

            else -> {
                channelViewModel.stopPolling()
            }
        }
    }

    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            val currentTabName = tabs.getOrNull(safeTabIndex)
            when (currentTabName) {
                "ホーム" -> {
                    val section = homeViewModel.lastClickedSection
                    val itemId = homeViewModel.lastClickedItemId
                    if (section != null && itemId != null) {
                        focusMemory.remember(safeTabIndex, section, itemId)
                    } else {
                        focusMemory.requestContent(safeTabIndex)
                    }
                    onAiReturnConsumed()
                }

                "アプリ" -> {
                    val itemId = homeViewModel.lastClickedItemId
                    if (homeViewModel.lastClickedSection == "apps" && itemId != null) {
                        focusMemory.remember(safeTabIndex, "apps", itemId)
                    } else {
                        focusMemory.remember(safeTabIndex)
                    }
                    onAiReturnConsumed()
                }

                "ライブ", "ビデオ" -> {
                    // Their content owns restoration and consumes the AI return.
                }

                else -> {
                    focusMemory.remember(safeTabIndex)
                    onAiReturnConsumed()
                }
            }
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer && !isFullScreenMode) {
            val currentTabName = tabs.getOrNull(safeTabIndex)
            if (currentTabName != "ライブ" && currentTabName != "ビデオ") {
                ui.safeHouseRequester.safeRequestFocusWithRetry("SafeHouse_Return")
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                when {
                    currentTabName == "ホーム" && section != null && itemId != null -> {
                        focusMemory.remember(safeTabIndex, section, itemId)
                    }

                    currentTabName == "アプリ" && section == "apps" && itemId != null -> {
                        focusMemory.remember(safeTabIndex, "apps", itemId)
                    }

                    currentTabName == "番組表" || currentTabName == "録画予約" -> {
                        if (currentTabName == "番組表") {
                            onReturnFocusConsumed()
                        }
                    }

                    else -> {
                        focusMemory.remember(safeTabIndex)
                    }
                }
            }
        }
    }

    HomeOverlayReturnFocusEffect(isFullScreenMode, isReturningFromPlayer || aiFocusReturnTick > 0) {
        val currentTabName = tabs.getOrNull(safeTabIndex)
        when {
            currentTabName == "録画予約" -> {}

            currentTabName == "ホーム" -> {
                val section = homeViewModel.lastClickedSection
                val itemId = homeViewModel.lastClickedItemId
                if (section != null && itemId != null) {
                    focusMemory.remember(safeTabIndex, section, itemId)
                } else {
                    focusMemory.remember(safeTabIndex)
                }
            }

            currentTabName == "アプリ" -> {
                val itemId = homeViewModel.lastClickedItemId
                if (homeViewModel.lastClickedSection == "apps" && itemId != null) {
                    focusMemory.remember(safeTabIndex, "apps", itemId)
                } else {
                    focusMemory.remember(safeTabIndex)
                }
            }

            currentTabName != "番組表" -> {
                focusMemory.remember(safeTabIndex)
            }
        }
    }

    LaunchedEffect(triggerBack) {
        if (triggerBack) {
            handleLauncherBack()
        }
    }

    HomeEntryFocusEffect(
        focusMemory,
        tabRequester = { ui.tabFocusRequesters.getOrNull(it) },
        contentRequester = ::firstContentRequesterFor,
        contentReadyGeneration = renderState.readyGeneration,
        isContentReady = { index ->
            index == safeTabIndex &&
                renderState.isContentReadyFor(index, requestedContentKey)
        },
    )

    LaunchedEffect(focusMemory.completedId) {
        if (focusMemory.completedId > 0 && isReturningFromPlayer &&
            tabs.getOrNull(safeTabIndex) !in listOf("ライブ", "ビデオ")) {
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
                            rightFocusRequester = ui.tabFocusRequesters.getOrNull(0)
                                ?: FocusRequester.Default,
                            downFocusRequester = firstContentRequesterFor(safeTabIndex),
                            canMoveDown = renderState.isContentReadyFor(
                                safeTabIndex,
                                requestedContentKey,
                            ),
                            onClick = { homeViewModel.launchInputSourcePicker(inputSourceApp) }
                        )
                        Spacer(modifier = Modifier.width(18.dp))
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TabRow(
                        selectedTabIndex = safeTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionUp &&
                                    inputSourceApp != null
                                ) {
                                    // ★ 修正: 入力ソースアプリが未設定の場合はキーを握りつぶさず、
                                    // 既定のフォーカス移動に任せる。
                                    homeViewModel.launchInputSourcePicker(inputSourceApp)
                                    true
                                } else {
                                    false
                                }
                            },
                        indicator = { tabPositions, doesTabRowHaveFocus ->
                            if (safeTabIndex < tabPositions.size) {
                                TabRowDefaults.UnderlinedIndicator(
                                    currentTabPosition = tabPositions[safeTabIndex],
                                    doesTabRowHaveFocus = doesTabRowHaveFocus,
                                    activeColor = colors.accent
                                )
                            }
                        }) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = safeTabIndex == index,
                                onFocus = {
                                    ui.selectedTabIndex = index
                                    ui.onTabSelected(
                                        index,
                                        onTabChange,
                                        homeViewModel
                                    )
                                    ui.topNavHasFocus = true
                                },
                                modifier = Modifier
                                    .focusRequester(
                                        ui.tabFocusRequesters.getOrNull(index)
                                            ?: FocusRequester.Default
                                    )
                                    .focusProperties {
                                        down =
                                            if (safeTabIndex == index &&
                                                renderState.isContentReadyFor(index, requestedContentKey)
                                            )
                                                firstContentRequesterFor(index)
                                            else FocusRequester.Default

                                        canFocus = !(title == "番組表" && ui.isEpgJumping)

                                        up = FocusRequester.Cancel
                                        if (index == 0) {
                                            left =
                                                if (inputSourceApp != null) inputSourceFocusRequester
                                                else FocusRequester.Cancel
                                        }
                                    }) {
                                Text(
                                    text = title,
                                    modifier = Modifier.padding(
                                        horizontal = 10.dp,
                                        vertical = 8.dp
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (safeTabIndex == index) colors.textPrimary else colors.textSecondary
                                )
                            }
                        }
                    }

                    // 「再生中」ボタンの有無で上部バーやタブ列がリフローしないよう、
                    // 常に同じ幅のスロットを確保しておく。
                    Box(
                        modifier = Modifier.width(RETURN_PLAYER_SLOT_WIDTH),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hasActivePlayer) {
                            val isEpgJumping =
                                tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                            Button(
                                onClick = onReturnToPlayerClick,
                                modifier = Modifier
                                    .focusRequester(returnPlayerFocusRequester)
                                    .focusProperties {
                                        left = ui.tabFocusRequesters.getOrNull(tabs.lastIndex)
                                            ?: FocusRequester.Default
                                        right = systemSettingsFocusRequester
                                        canFocus = !isEpgJumping
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
                                val isEpgJumping =
                                    tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                                left =
                                    if (hasActivePlayer) returnPlayerFocusRequester else (ui.tabFocusRequesters.getOrNull(
                                        tabs.lastIndex
                                    ) ?: FocusRequester.Default)
                                right = wifiSettingsFocusRequester
                                canFocus = !isEpgJumping
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
                        canTakeFocus = !(tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping),
                        onClick = { homeViewModel.launchWifiSettings() }
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { homeViewModel.launchSystemSettings() },
                        modifier = Modifier
                            .focusRequester(ui.settingsFocusRequester)
                            .focusProperties {
                                val isEpgJumping =
                                    tabs.getOrNull(safeTabIndex) == "番組表" && ui.isEpgJumping
                                left = wifiSettingsFocusRequester
                                canFocus = !isEpgJumping
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
                // Keep the active tree mounted during the 60 ms coalescing window. Repeated
                // left/right presses replace only the pending target instead of rebuilding each tab.
                val currentTabLabel = renderState.activeContentKey

                when (currentTabLabel) {
                        "ホーム" -> HomeContents(
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
                                // ★ 修正: タブタイトルから「現在表示中のタブ一覧」での索引を解決する。
                                // アプリタブの非表示有無で一覧の索引がずれるため、固定索引は使わない。
                                val targetIndex = tabs.indexOf(targetTitle)
                                if (targetIndex != -1) {
                                    ui.tabFocusRequesters.getOrNull(targetIndex)
                                        ?.safeRequestFocus(TAG)
                                    ui.onTabSelected(targetIndex, onTabChange, homeViewModel)
                                }
                            },
                            konomiIp = konomiIp,
                            konomiPort = konomiPort,
                            mirakurunIp = mirakurunIp,
                            mirakurunPort = mirakurunPort,
                            tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                            externalFocusRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                            lastFocusedChannelId = ui.internalLastPlayerChannelId
                                ?: lastPlayerChannelId,
                            lastFocusedProgramId = lastPlayerProgramId,
                            isTopNavFocused = ui.topNavHasFocus,
                            onUiReady = handleUiReady,
                            homeViewModel = homeViewModel,
                            timeFormat = timeFormat,
                            focusMemory = focusMemory,
                            tabIndex = activeRenderIndex,
                        )

                        "ライブ" -> {
                            LiveContent(
                                channelViewModel = channelViewModel,
                                epgViewModel = epgViewModel,
                                groupedChannels = groupedChannels,
                                onChannelClick = onChannelClick,
                                onFocusChannelChange = { ui.internalLastPlayerChannelId = it },
                                mirakurunIp = mirakurunIp,
                                mirakurunPort = mirakurunPort,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                lastFocusedChannelId = ui.internalLastPlayerChannelId
                                    ?: lastPlayerChannelId,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ライブ",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (currentTabLabel == "ライブ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = handleUiReady,
                            )
                        }

                        "アプリ" -> {
                            AppsTabContent(
                                homeViewModel = homeViewModel,
                                tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                onUiReady = handleUiReady,
                                focusMemory = focusMemory,
                                tabIndex = activeRenderIndex,
                            )
                        }

                        "ビデオ" -> {
                            VideoTabContent(
                                recordViewModel = recordViewModel,
                                onProgramClick = { onProgramSelected(it) },
                                onShowAllRecordings = onShowAllRecordings,
                                onShowSeriesList = onShowSeriesList,
                                onShowSmbLibrary = onShowSmbLibrary,
                                onShowOnAir = onShowOnAir,
                                openedSeriesTitle = ui.openedSeriesTitle,
                                onOpenedSeriesTitleChange = { ui.openedSeriesTitle = it },
                                tabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                isTopNavFocused = ui.topNavHasFocus,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "ビデオ",
                                lastPlayedProgramId = lastPlayerProgramId,
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                timeFormat = timeFormat,
                                watchHistory = ui.watchHistory,
                                isNetworkAvailable = networkConnectionStatus.isAvailable,
                                aiFocusReturnTick = if (currentTabLabel == "ビデオ") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = handleUiReady,
                            )
                        }

                        "番組表" -> {
                            EpgNavigationContainer(
                                uiState = ui.epgUiState,
                                logoUrls = ui.logoUrls,
                                mainTabFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                contentRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                selectedProgram = epgSelectedProgram,
                                onProgramSelected = onEpgProgramSelected,
                                isJumpMenuOpen = isEpgJumpMenuOpen,
                                onJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                                onNavigateToPlayer = onNavigateToPlayer,
                                currentType = epgViewModel.selectedBroadcastingType.collectAsState().value,
                                onTypeChanged = { epgViewModel.updateBroadcastingType(it) },
                                restoreChannelId = if (isReturningFromPlayer && currentTabLabel == "番組表") lastPlayerChannelId else null,
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
                                onContentReady = handleUiReady,
                            )
                        }

                        "録画予約" -> {
                            ReserveListScreen(
                                onBack = {
                                    ui.tabFocusRequesters[activeRenderIndex].safeRequestFocus(
                                        TAG
                                    )
                                },
                                onProgramClick = onReserveSelected,
                                onConditionClick = onConditionClick,
                                konomiIp = konomiIp,
                                konomiPort = konomiPort,
                                contentFirstItemRequester = ui.contentFirstItemRequesters[activeRenderIndex],
                                topNavFocusRequester = ui.tabFocusRequesters[activeRenderIndex],
                                groupedChannels = groupedChannels,
                                isReserveOverlayOpen = isReserveOverlayOpen,
                                isReturningFromPlayer = isReturningFromPlayer && currentTabLabel == "録画予約",
                                onReturnFocusConsumed = onReturnFocusConsumed,
                                timeFormat = timeFormat,
                                aiFocusReturnTick = if (currentTabLabel == "録画予約") aiFocusReturnTick else 0,
                                onAiReturnConsumed = onAiReturnConsumed,
                                onContentReady = handleUiReady,
                            )
                        }
                }

                HomeConnectionFeedback(
                    state = homeConnectionFeedbackState(
                        networkAvailable = networkConnectionStatus.isAvailable,
                        hasBackendFailure = hasBackendFailure,
                        backendFailureReason = backendFailureReason,
                        recoveryInProgress = isBackendRecoveryInProgress,
                    ),
                    onOpenConnectionSettings = onConnectionSettings,
                    onRetryBackendConnection = onRetryBackendConnection,
                    onRecoveryFocus = {
                        scope.launch {
                            if (!firstContentRequesterFor(safeTabIndex)
                                    .safeRequestFocusWithRetry("HomeConnectionRecovered")
                            ) {
                                ui.tabFocusRequesters.getOrNull(safeTabIndex)
                                    ?.safeRequestFocusWithRetry("HomeConnectionRecoveredFallback")
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp, start = 48.dp, end = 48.dp),
                )
            }
        }
    }
}
