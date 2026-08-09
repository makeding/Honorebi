@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime
import androidx.compose.runtime.collectAsState
import androidx.media3.common.util.Log
import com.beeregg2001.komorebi.media.IdleSystemMediaSession
import com.beeregg2001.komorebi.media.RootSystemMediaSessionHost

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
    homeIntentVersion: Int = 0,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMainRootState()

    // =========================================================================================
    // ★ 追加: アプリのバックグラウンド移行（スリープ）と復帰を検知する機構
    // =========================================================================================
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isAppInForeground by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // 別のアプリを開いた、またはホーム画面に戻ってアプリが裏に回った（スリープ状態）
                    isAppInForeground = false
                }

                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // アプリ画面に戻ってきた時（かつ、一度裏に回っていた場合のみ実行）
                    if (!isAppInForeground) {
                        isAppInForeground = true

                        Log.i(
                            "KomorebiLifecycle",
                            "アプリがバックグラウンドから復帰しました。データをリフレッシュします。"
                        )

                        // 1. プロ野球タブやホーム画面のデータを最新に更新する
                        homeViewModel.refreshHomeData()
                        homeViewModel.refreshLauncherApps()
                        channelViewModel.fetchChannels()

                    }
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // =========================================================================================

    val timeFormat by settingsViewModel.timeFormat.collectAsState()
    val geminiApiKey by settingsViewModel.geminiApiKey.collectAsState(initial = "")

    val closeAiConcierge = { restoreFocus: Boolean ->
        state.isAiConciergeOpen = false
        aiConciergeViewModel.resetState()

        if (restoreFocus) {
            if (state.currentTabIndex == 3) epgViewModel.triggerRestore()
            else state.aiFocusReturnTick++
        }
        epgViewModel.clearSearch()
    }

    val backendType by homeViewModel.backendType.collectAsState()
    LaunchedEffect(backendType) { state.backendType = backendType }

    val baseTabs = state.getVisibleTabs()
    val favoriteBaseballTeams by homeViewModel.favoriteBaseballTeams.collectAsState()
    val tabs = remember(favoriteBaseballTeams, baseTabs) {
        if (favoriteBaseballTeams.isNotEmpty() && !baseTabs.contains("プロ野球")) baseTabs + "プロ野球" else baseTabs
    }

    val safeTabIndex = state.currentTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    LaunchedEffect(tabs.size) {
        if (state.currentTabIndex >= tabs.size) state.currentTabIndex = 0
    }

    if (AI_FEATURES_ENABLED) {
        LaunchedEffect(Unit) {
            aiConciergeViewModel.pendingAction.collect { action ->
                when (action) {
                is AiConciergeAction.PlayLive -> {
                    closeAiConcierge(false)
                    val target = channelViewModel.groupedChannels.value.values.flatten()
                        .find { it.id == action.channelId }
                    if (target != null) {
                        state.isPlayerMiniListOpen = false; state.playerIsSubMenuOpen = false
                        state.isPlayerSubMenuOpen = false; state.isPlayerSceneSearchOpen = false
                        state.enterLive(target)
                        homeViewModel.saveLastChannel(target)
                    }
                }

                is AiConciergeAction.PlayRecorded -> {
                    closeAiConcierge(false)
                    val target =
                        recordViewModel.recentRecordings.value.find { it.id == action.videoId }
                    if (target != null) {
                        state.isPlayerMiniListOpen = false; state.playerIsSubMenuOpen = false
                        state.isPlayerSubMenuOpen = false; state.isPlayerSceneSearchOpen = false
                        state.enterRecorded(target)
                    }
                }

                is AiConciergeAction.SearchEpg -> {
                    closeAiConcierge(false)
                    val isOnlyDate =
                        action.keyword.isBlank() && action.genre.isBlank() && action.date.isNotBlank() && !action.isLiveOnly && action.channelName.isBlank()
                    if (isOnlyDate) {
                        try {
                            val parsedDate =
                                java.time.LocalDate.parse(action.date.replace("/", "-"))
                            val jumpTime = java.time.OffsetDateTime.now().withYear(parsedDate.year)
                                .withMonth(parsedDate.monthValue)
                                .withDayOfMonth(parsedDate.dayOfMonth).withHour(4).withMinute(0)
                                .withSecond(0).withNano(0)
                            epgViewModel.updateTargetTime(jumpTime)
                            val tabIndex = tabs.indexOf("番組表")
                            if (tabIndex != -1) state.currentTabIndex = tabIndex
                            state.toastMessage = "${action.date} の番組表に移動しました"
                        } catch (e: Exception) {
                            state.toastMessage = "日付の指定が正しくありません"
                        }
                    } else {
                        epgViewModel.executeSearch(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName
                        )
                        val tabIndex = tabs.indexOf("番組表")
                        if (tabIndex != -1) state.currentTabIndex = tabIndex
                        state.toastMessage =
                            if (action.keyword.isNotBlank()) "「${action.keyword}」の検索結果を表示します" else "検索結果を表示します"
                    }
                }

                is AiConciergeAction.SearchRecord -> {
                    closeAiConcierge(false)
                    val tabIndex = tabs.indexOf("ビデオ")
                    if (tabIndex != -1) {
                        state.currentTabIndex = tabIndex; state.isRecordListOpen = true
                        if (action.keyword.isNotBlank()) recordViewModel.searchRecordings(
                            action.keyword.split(
                                ","
                            ).firstOrNull()?.trim() ?: ""
                        )
                        if (action.genre.isNotBlank()) recordViewModel.updateGenre(action.genre)
                        state.toastMessage = if (action.keyword.isNotBlank()) "「${
                            action.keyword.split(",").firstOrNull()
                        }」の録画を検索します" else "録画リストを表示します"
                    } else state.toastMessage =
                        "現在設定されているシステムでは録画検索は利用できません"
                }

                is AiConciergeAction.ReqEpgSearch -> {
                    scope.launch {
                        val results = epgViewModel.searchSilently(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName
                        )
                        aiConciergeViewModel.submitSilentSearchResult(action.keyword, results)
                    }
                }

                is AiConciergeAction.ReqRecSearch -> {
                    scope.launch {
                        val allRecs = recordViewModel.recentRecordings.value
                        val keywords =
                            action.keyword.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val results = allRecs.filter { program ->
                            val matchKeyword = keywords.isEmpty() || keywords.any { kw ->
                                program.title.contains(
                                    kw,
                                    ignoreCase = true
                                ) || program.description.contains(kw, ignoreCase = true)
                            }
                            val matchGenre = action.genre.isBlank() || program.genres?.any { g ->
                                g.major.contains(action.genre)
                            } == true
                            matchKeyword && matchGenre
                        }
                        aiConciergeViewModel.submitSilentRecordSearchResult(action.keyword, results)
                    }
                }

                is AiConciergeAction.ReserveSingle -> {
                    closeAiConcierge(true)
                    reserveViewModel.addReserve(action.programId) {
                        state.toastMessage = "番組の録画予約を完了しました"
                    }
                }

                is AiConciergeAction.ReserveAuto -> {
                    closeAiConcierge(true)
                    reserveViewModel.addEpgReserve(
                        keyword = action.keyword,
                        networkId = 0,
                        transportStreamId = 0,
                        serviceId = 0,
                        daysOfWeek = setOf(0, 1, 2, 3, 4, 5, 6),
                        startHour = 0,
                        startMinute = 0,
                        endHour = 23,
                        endMinute = 59,
                        excludeKeyword = "",
                        isTitleOnly = false,
                        broadcastType = "GR,BS,BS4K,CS,SKY",
                        isFuzzySearch = true,
                        duplicateScope = "SameTitle",
                        priority = 3,
                        isEventRelay = true,
                        isExactRecord = true,
                        onSuccess = {
                            state.toastMessage = "「${action.keyword}」の自動録画条件を登録しました"
                        }
                    )
                }
                }
            }
        }
    }

    val themeName by settingsViewModel.appTheme.collectAsState(initial = "MONOTONE")
    val currentTheme =
        remember(themeName) { runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.MONOTONE) }
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

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now(); delay(60000)
        }
    }

    val detailFocusRequester = remember { FocusRequester() }

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val isChannelLoading by channelViewModel.isLoading.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val networkConnectionStatus by rememberNetworkConnectionStatus()
    val isNetworkAvailable = networkConnectionStatus.isAvailable
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val watchHistory by homeViewModel.watchHistory.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val localRecordedCount by recordViewModel.localRecordedCount.collectAsState()
    val lastChannels by homeViewModel.lastWatchedChannelFlow.collectAsState(initial = emptyList())
    val conditions by reserveViewModel.conditions.collectAsState()
    val reserves by reserveViewModel.reserves.collectAsState()
    val defaultSystemChannel = remember(groupedChannels) {
        val channels = groupedChannels.values.flatten()
        channels.firstOrNull { it.type == "GR" } ?: channels.firstOrNull()
    }
    val isPlaybackScreenOpen = state.isPlaybackActive
    IdleSystemMediaSession(enabled = !isPlaybackScreenOpen) {
        val channel = defaultSystemChannel ?: return@IdleSystemMediaSession
        state.enterLive(channel)
        homeViewModel.saveLastChannel(channel)
    }
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
    val edcbIp by settingsViewModel.edcbIp.collectAsState(initial = "")
    val edcbPort by settingsViewModel.edcbPort.collectAsState(initial = "")

    val defaultLiveQuality by settingsViewModel.liveQuality.collectAsState(initial = "1080p-60fps")
    val defaultVideoQuality by settingsViewModel.videoQuality.collectAsState(initial = "1080p-60fps")

    val startupChannelSetting by settingsViewModel.startupChannel.collectAsState()
    var isLongPressHandled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!state.hasAppliedStartupTab) {
            val tab = settingsViewModel.getStartupTabOnce()
            val index = tabs.indexOf(tab)
            state.currentTabIndex = if (index != -1) index else 0
            channelViewModel.fetchChannels()
            state.hasAppliedStartupTab = true
        }
    }

    LaunchedEffect(
        groupedChannels,
        state.hasAppliedStartupTab,
        isSettingsInitialized,
        isChannelLoading,
        isHomeLoading,
        lastChannels
    ) {
        if (isSettingsInitialized && state.hasAppliedStartupTab && !state.hasAppliedStartupChannel && !isChannelLoading && !isHomeLoading && groupedChannels.isNotEmpty()) {
            state.hasAppliedStartupChannel = true
            val flatChannels = groupedChannels.values.flatten()
            if (flatChannels.isNotEmpty() && startupChannelSetting != "OFF") {
                val targetChannel = if (startupChannelSetting == "LAST_WATCHED") {
                    val lastHistory = lastChannels.firstOrNull()
                    flatChannels.find { it.id == lastHistory?.id } ?: flatChannels.first()
                } else flatChannels.find { it.id == startupChannelSetting } ?: flatChannels.first()

                state.enterLive(targetChannel)
                homeViewModel.saveLastChannel(targetChannel)

                val liveIndex = tabs.indexOf("ライブ")
                if (liveIndex != -1) state.currentTabIndex = liveIndex
                state.isUiReady = true
            }
        }
    }

    LaunchedEffect(state.isRecordListOpen) { if (state.isRecordListOpen) recordViewModel.triggerSmartSync() }

    val closeSettingsAndRefresh = {
        state.isSettingsOpen = false; state.isDataReady = false; state.isUiReady =
        false; state.showConnectionErrorDialog = false
        state.isOfflineMode = false
        state.currentTabIndex = 0
        channelViewModel.fetchChannels(); epgViewModel.preloadAllEpgData(); homeViewModel.refreshHomeData()
        recordViewModel.fetchRecentRecordings(forceRefresh = false); reserveViewModel.fetchReserves()
        state.settingsInitialCategoryIndex = 0
        state.settingsInitialFocusItemIndex = null
        state.settingsOpenDeviceCapabilities = false
    }

    val returnToLauncherHome = {
        if (state.isSettingsOpen) {
            closeSettingsAndRefresh()
        } else {
            state.currentTabIndex = 0
        }

        state.resetForLauncherHome()
        state.epgSelectedProgram = null
        state.selectedReserve = null
        state.editingReserveItem = null
        state.editingNewProgram = null
        state.reserveToDelete = null
        state.selectedProgramForAutoReserve = null
        state.editingCondition = null
        state.selectedConditionReserveItem = null
        state.openedSeriesTitle = null
        state.isRecordListOpen = false
        state.isSeriesListOpen = false
        state.isSmbLibraryOpen = false
        state.isEpgJumpMenuOpen = false
        state.showDeleteConfirmDialog = false
        state.isAiConciergeOpen = false
        state.showAiKeyboardInput = false
    }

    LaunchedEffect(homeIntentVersion) {
        if (homeIntentVersion > 0) {
            returnToLauncherHome()
        }
    }

    LaunchedEffect(state.toastMessage) {
        if (state.toastMessage != null) {
            delay(3000); state.toastMessage = null
        }
    }

    BackHandler(enabled = true) {
        if (!state.canProcessBackPress()) return@BackHandler

        when {
            state.isAiConciergeOpen -> closeAiConcierge(true)
            state.selectedConditionReserveItem != null -> state.selectedConditionReserveItem = null
            state.editingNewProgram != null -> state.editingNewProgram = null
            state.editingReserveItem != null -> state.editingReserveItem = null
            state.reserveToDelete != null -> state.reserveToDelete = null
            state.selectedProgramForAutoReserve != null -> state.selectedProgramForAutoReserve =
                null

            state.showDeleteConfirmDialog -> state.showDeleteConfirmDialog = false
            state.isMiniPlayerMode -> {
                state.exitMiniPlayer(); state.toastMessage = "フルスクリーンに戻りました"
            }

            state.isPlayerMiniListOpen -> state.isPlayerMiniListOpen = false
            state.playerIsSubMenuOpen -> state.playerIsSubMenuOpen = false
            state.livePlayback != null && state.playerShowOverlay -> {
                state.playerShowOverlay = false
                state.playerIsManualOverlay = false
                state.playerIsPinnedOverlay = false
            }

            state.livePlayback != null && state.playerIsPinnedOverlay -> {
                state.playerShowOverlay = false
                state.playerIsManualOverlay = false
                state.playerIsPinnedOverlay = false
            }

            state.isPlayerSubMenuOpen -> state.isPlayerSubMenuOpen = false
            state.isPlayerSceneSearchOpen -> {
                state.isPlayerSceneSearchOpen = false; state.showPlayerControls = false
            }

            state.livePlayback != null -> {
                state.leavePlayback()
            }

            state.recordedPlayback != null || state.smbPlayback != null -> {
                state.leavePlayback()
            }

            state.isSettingsOpen -> closeSettingsAndRefresh()
            state.epgSelectedProgram != null -> state.epgSelectedProgram = null
            state.selectedReserve != null -> state.selectedReserve = null
            state.isEpgJumpMenuOpen -> state.isEpgJumpMenuOpen = false

            state.isSmbLibraryOpen -> state.isSmbLibraryOpen = false

            state.isRecordListOpen -> {
                state.isRecordListOpen = false
                if (state.openedSeriesTitle != null) {
                    state.isSeriesListOpen = true; state.openedSeriesTitle = null
                }
                recordViewModel.searchRecordings("")
            }

            state.isSeriesListOpen -> {
                state.isSeriesListOpen = false; recordViewModel.searchRecordings("")
            }

            state.showConnectionErrorDialog -> {}
            !(state.isDataReady && state.isUiReady) -> {}
            else -> state.triggerHomeBack = true
        }
    }

    LaunchedEffect(isNetworkAvailable, isSettingsInitialized, state.isOfflineMode) {
        if (isSettingsInitialized && !isNetworkAvailable && !state.isOfflineMode) {
            state.showConnectionErrorDialog = false
            state.isOfflineMode = true
            state.isDataReady = true
            state.isSplashFinished = true
            state.isUiReady = true
            state.hasAppliedStartupChannel = true
        }
    }

    LaunchedEffect(isChannelLoading, isHomeLoading) {
        if (!isChannelLoading && !isHomeLoading) {
            delay(300)
            if (isChannelError) {
                if (isNetworkAvailable && !state.isOfflineMode) {
                    state.showConnectionErrorDialog = true; state.isDataReady = false
                } else {
                    state.showConnectionErrorDialog = false
                    state.isOfflineMode = true
                    state.isDataReady = true
                    state.isSplashFinished = true
                    state.isUiReady = true
                    state.hasAppliedStartupChannel = true
                }
            } else {
                state.showConnectionErrorDialog = false; state.isDataReady = true
                state.isOfflineMode = false
            }
        }
    }

    LaunchedEffect(isEpgReady, state.isDataReady, isSettingsInitialized, state.currentTabIndex) {
        if (!isSettingsInitialized) {
            delay(500); state.isSplashFinished = true
        } else if (state.currentTabIndex == tabs.indexOf("番組表")) {
            if (isEpgReady && state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        } else {
            if (state.isDataReady) {
                delay(300); state.isSplashFinished = true
            }
        }
    }

    val isSystemReady =
        ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) &&
                state.hasAppliedStartupTab && (startupChannelSetting == "OFF" || state.hasAppliedStartupChannel || state.isOfflineMode)

    KomorebiTheme(theme = currentTheme) {
        RootSystemMediaSessionHost(playbackSessionEpoch = state.playbackSessionEpoch) {
            val colors = KomorebiTheme.colors
            val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

            Box(
            modifier = Modifier
                .fillMaxSize()
                // ★ 修正: onPreviewKeyEvent（トップダウン）に戻し、グローバルショートカットの確実性を復活
                .onPreviewKeyEvent { event ->
                    val isCenterKey =
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter

                    // ★ 追加: 「プレイヤーのUI（シークバーやボタン）が表示されている時」は、
                    // 親玉（MainRoot）がイベントを横取りせず、子要素（PlayerControlsの早送り連打など）にイベントを譲る！
                    val isVideoUiVisible =
                        (state.recordedPlayback != null || state.smbPlayback != null) && state.showPlayerControls
                    val isLiveUiVisible = state.livePlayback != null && state.playerShowOverlay
                    if ((isVideoUiVisible || isLiveUiVisible) && isCenterKey) {
                        return@onPreviewKeyEvent false // 横取りせず、子要素へスルーさせる
                    }

                    if (!AI_FEATURES_ENABLED) return@onPreviewKeyEvent false

                    if (isCenterKey && event.type == KeyEventType.KeyUp && isLongPressHandled) {
                        isLongPressHandled = false
                        return@onPreviewKeyEvent true
                    }
                    if (state.isAiConciergeOpen || state.showAiKeyboardInput) return@onPreviewKeyEvent false
                    if (isCenterKey && event.type == KeyEventType.KeyDown) {
                        if ((event.nativeKeyEvent.isLongPress || event.nativeKeyEvent.repeatCount > 0) && !isLongPressHandled) {
                            isLongPressHandled = true
                            state.isAiConciergeOpen = true
                            state.aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT)
                            return@onPreviewKeyEvent true
                        }
                        if (isLongPressHandled) return@onPreviewKeyEvent true
                    }
                    false
                }
                .background(colors.background)
                .background(backgroundBrush)
        ) {
            if (!state.isPlaybackActive) {
                SeasonalDecor(
                    season = themeSeason,
                    isDark = colors.isDark,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            val showMainContent =
                isSystemReady && isSettingsInitialized && !state.showConnectionErrorDialog && !isSyncingInitial

            if (showMainContent) {
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
                        backgroundBrush = backgroundBrush,
                        onExitApp = onExitApp
                    )

                    if (state.isPlaybackActive) {
                        MainRootPlaybackHost(
                            state = state,
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
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !state.isUiReady && !state.showConnectionErrorDialog && isSettingsInitialized,
                enter = fadeIn(),
                exit = fadeOut(tween(250))
            ) {
                if (isSyncingInitial) {
                    val currentSync by recordViewModel.syncProgress.collectAsState()
                    val pRatio =
                        if (currentSync.total > 0) currentSync.current.toFloat() / currentSync.total.toFloat() else 0f
                    LoadingScreen(message = currentSync.progressText, progressRatio = pRatio)
                } else LoadingScreen()
            }

            if (state.showConnectionErrorDialog && isSettingsInitialized && !state.isSettingsOpen) {
                val hasOfflineCache = remember(
                    groupedChannels,
                    watchHistory,
                    recentRecordings,
                    lastChannels,
                    localRecordedCount
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
                        state.showConnectionErrorDialog = false
                        state.isOfflineMode = true
                        state.isDataReady = true
                        state.isSplashFinished = true
                        state.isUiReady = true
                        state.hasAppliedStartupChannel = true
                    },
                    onRetry = {
                        state.showConnectionErrorDialog = false
                        state.isOfflineMode = false
                        state.isDataReady = false
                        state.isUiReady = false
                        channelViewModel.fetchChannels()
                        recordViewModel.fetchRecentRecordings(forceRefresh = true)
                        homeViewModel.refreshHomeData()
                    },
                    onOpenSettings = {
                        state.showConnectionErrorDialog = false
                        state.settingsInitialCategoryIndex = 1
                        state.settingsInitialFocusItemIndex = null
                        state.isSettingsOpen = true
                    },
                    modifier = Modifier.zIndex(6f)
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
                onExitApp = onExitApp,
                closeSettingsAndRefresh = closeSettingsAndRefresh,
                closeAiConcierge = closeAiConcierge,
                onGoToSettings = {
                    closeAiConcierge(true)
                    state.settingsInitialCategoryIndex = 7
                    state.settingsInitialFocusItemIndex = 2
                    state.isSettingsOpen = true
                }
            )
            }
        }
    }
}
