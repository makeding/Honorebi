@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.ui.player.*

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.ui.home.LoadingScreen
import com.beeregg2001.komorebi.ui.home.UpgradeSpotlightOverlay
import com.beeregg2001.komorebi.ui.home.UpgradeTourAction
import com.beeregg2001.komorebi.ui.home.UpgradeTourCatalog
import com.beeregg2001.komorebi.ui.home.UpgradeTourTargetId
import com.beeregg2001.komorebi.ui.home.rememberUpgradeSpotlightTargets
import com.beeregg2001.komorebi.ui.home.shouldShowUpgradeLandingTour
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.ui.theme.AppTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import androidx.compose.runtime.collectAsState
import com.beeregg2001.komorebi.media.IdleSystemMediaSession
import com.beeregg2001.komorebi.media.RootSystemMediaSessionHost
import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import com.beeregg2001.komorebi.navigation.PlaybackOpenRequest
import com.beeregg2001.komorebi.navigation.PlaybackOpenSource
import com.beeregg2001.komorebi.notification.NotificationCenterViewModel
import com.beeregg2001.komorebi.ui.video.player.displayLabel
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.screensaver.rememberInAppScreensaverState

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberMainRootState()
    val hideEmptyInputs by settingsViewModel.tifHideEmptyInputs.collectAsState()
    val integrationActive by settingsViewModel.tifIntegrationActive.collectAsState()
    val showTuners by settingsViewModel.tifShowTunerInputs.collectAsState()
    var inputGeneration by remember { mutableIntStateOf(0) }
    var remoteInputs by remember { mutableStateOf<RemoteInputs?>(null) }
    val selectedInputId = (state.playbackState.renderPlaybackTarget as? PlaybackTarget.Live)?.channel?.id
    DisposableEffect(context) {
        val manager = context.getSystemService(android.media.tv.TvInputManager::class.java)
        val callback = object : android.media.tv.TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) { inputGeneration++ }
            override fun onInputRemoved(inputId: String) { inputGeneration++ }
            override fun onInputUpdated(inputId: String) { inputGeneration++ }
            override fun onInputStateChanged(inputId: String, state: Int) { inputGeneration++ }
        }
        manager?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager?.unregisterCallback(callback) }
    }
    LaunchedEffect(inputGeneration, hideEmptyInputs, showTuners, integrationActive, selectedInputId) {
        remoteInputs = remoteInputs(context, hideEmptyInputs, showTuners && integrationActive, selectedInputId)
        remoteControlViewModel.client.updateInputSources(remoteInputs!!.json)
    }
    remember(launchedFromDeepLink) {
        if (launchedFromDeepLink) state.hasAppliedStartupChannel = true
    }
    val nhkExclusionRepository = settingsViewModel.nhkExclusionRepository
    val nhkExclusionState by nhkExclusionRepository.state.collectAsState()
    var wasNHKExclusionLoaded by remember { mutableStateOf(nhkExclusionState.isLoaded) }
    var wasNHKExclusionActive by remember { mutableStateOf(nhkExclusionState.isActive) }

    fun excludeCurrentNHKContent(invalidatePendingOpens: Boolean = true): Boolean {
        fun isExcludedTarget(target: PlaybackTarget): Boolean = when (target) {
            is PlaybackTarget.Live -> nhkExclusionRepository.isExcluded(target.channel)
            is PlaybackTarget.Recorded -> nhkExclusionRepository.isExcluded(target.program)
            else -> false
        }
        val excluded = isExcludedTarget(state.playbackState.renderPlaybackTarget) ||
            isExcludedTarget(state.playbackState.playbackTarget)
        if (excluded) {
            state.playbackState.resetPlayback()
            remoteControlViewModel.client.sendState(contentType = "Idle")
        }
        if (nhkExclusionRepository.state.value.isActive) {
            if (invalidatePendingOpens) {
                state.playbackState.invalidatePlaybackOpenIntents()
            }
            if (state.epgSelectedProgram?.channel_id?.let(nhkExclusionRepository::isExcludedChannelId) == true) {
                state.epgSelectedProgram = null
            }
            if (state.selectedProgramForAutoReserve?.let(nhkExclusionRepository::isExcluded) == true) {
                state.selectedProgramForAutoReserve = null
            }
            if (state.selectedReserve?.let(nhkExclusionRepository::isExcluded) == true) state.selectedReserve = null
            if (state.editingReserveItem?.let(nhkExclusionRepository::isExcluded) == true) state.editingReserveItem = null
            if (state.reserveToDelete?.let(nhkExclusionRepository::isExcluded) == true) state.reserveToDelete = null
            if (state.selectedConditionReserveItem?.let(nhkExclusionRepository::isExcluded) == true) state.selectedConditionReserveItem = null
            if (state.editingNewProgram?.channel_id?.let(nhkExclusionRepository::isExcludedChannelId) == true) state.editingNewProgram = null
        }
        return excluded
    }

    val tifActive by settingsViewModel.tifIntegrationActive.collectAsState()
    LaunchedEffect(tifActive) {
        if (!tifActive) {
            (state.playbackState.renderPlaybackTarget as? PlaybackTarget.Live)
                ?.takeIf { it.channel.id.startsWith("tif:") }
                ?.let { state.playbackState.leavePlayback(returningFromPlayer = false) }
        }
    }

    LaunchedEffect(nhkExclusionState, state.playbackState.renderPlaybackTarget) {
        val newlyActivated = wasNHKExclusionLoaded && !wasNHKExclusionActive && nhkExclusionState.isActive
        wasNHKExclusionLoaded = nhkExclusionState.isLoaded
        wasNHKExclusionActive = nhkExclusionState.isActive
        excludeCurrentNHKContent(invalidatePendingOpens = newlyActivated)
        remoteControlViewModel.client.refreshNHKExclusionState()
    }

    fun playbackOpenFailureMessage(request: PlaybackOpenRequest): String = when (request) {
        is PlaybackOpenRequest.Live -> when (request.source) {
            PlaybackOpenSource.Remote -> "リモートで指定されたチャンネルを開けませんでした"
            PlaybackOpenSource.DeepLink -> "指定されたチャンネルを開けませんでした"
        }
        is PlaybackOpenRequest.Recording -> when (request.source) {
            PlaybackOpenSource.Remote -> "リモートで指定された録画を開けませんでした"
            PlaybackOpenSource.DeepLink -> "指定された録画を開けませんでした"
        }
    }

    fun openPlayback(request: PlaybackOpenRequest) {
        val intent = state.playbackState.beginPlaybackOpenIntent()
        scope.launch {
            val exclusionLoaded = withTimeoutOrNull(10_000) {
                nhkExclusionRepository.state.first { it.isLoaded }
            }
            if (exclusionLoaded == null) {
                if (state.playbackState.failPlaybackOpenIntent(intent)) {
                    state.toastMessage = playbackOpenFailureMessage(request)
                }
                return@launch
            }
            when (request) {
                is PlaybackOpenRequest.Live -> {
                    val findChannel = {
                        channelViewModel.groupedChannels.value.values.flatten()
                            .firstOrNull { it.displayChannelId == request.displayChannelId }
                    }
                    val channel = findChannel() ?: run {
                        channelViewModel.fetchChannels()
                        withTimeoutOrNull(10_000) {
                            while (findChannel() == null) delay(100)
                            findChannel()
                        }
                    }
                    when {
                        channel == null -> {
                            if (state.playbackState.failPlaybackOpenIntent(intent)) {
                                state.toastMessage = playbackOpenFailureMessage(request)
                            }
                        }
                        nhkExclusionRepository.isExcluded(channel) -> {
                            state.playbackState.failPlaybackOpenIntent(intent)
                        }
                        state.playbackState.completePlaybackOpenIntent(intent) -> {
                            state.bringRemotePlaybackToFront()
                            onRemotePlaybackOpened()
                            state.playbackState.enterLive(channel)
                            homeViewModel.saveLastChannel(channel)
                        }
                    }
                }
                is PlaybackOpenRequest.Recording -> {
                    when (val lookup = recordViewModel.lookupProgramForOpen(request.recordedProgramId)) {
                        is ProgramOpenLookup.Found -> {
                            val program = lookup.program
                            val positionSeconds = request.positionSeconds
                                ?: recordViewModel.resumePositionSeconds(program)
                            if (nhkExclusionRepository.isExcluded(program)) {
                                state.playbackState.failPlaybackOpenIntent(intent)
                            } else if (state.playbackState.completePlaybackOpenIntent(intent)) {
                                state.bringRemotePlaybackToFront()
                                onRemotePlaybackOpened()
                                val positionMs = (positionSeconds * 1_000).toLong().coerceAtLeast(0L)
                                if (!state.playbackState.beginRecordedSwitch(program, positionMs, PlaybackSwitchReason.RemoteOpen)) {
                                    state.playbackState.enterRecorded(program, positionMs)
                                }
                            }
                        }
                        ProgramOpenLookup.Excluded -> {
                            state.playbackState.failPlaybackOpenIntent(intent)
                        }
                        ProgramOpenLookup.NotFound -> {
                            if (state.playbackState.failPlaybackOpenIntent(intent)) {
                                state.toastMessage = playbackOpenFailureMessage(request)
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(deepLinkRequests) {
        deepLinkRequests.collect { request ->
            state.hasAppliedStartupChannel = true
            state.playbackState.dismissPlayerOverlaysForRemoteControl()
            openPlayback(request)
        }
    }

    // HonomiTV Web クライアントで選択されたライブ放送・録画番組を、既存の再生状態へ直接引き渡す。
    // 再生開始後の操作は既存の Android MediaSession / Cast 経路へ任せ、独自制御を重複実装しない。
    LaunchedEffect(remoteControlViewModel) {
        remoteControlViewModel.client.commands.collect { command ->
            // 視聴者はテレビの前にいないので、再生を動かすコマンドが来たら開きっぱなしのメニューを畳む。
            // 特に再生終了後のおすすめメニューは自動では消えず、Web UI から再生を再開しても
            // 本編がメニューに隠れたままになってしまう。
            if (shouldDismissPlayerOverlaysForRemoteCommand(command)) {
                state.playbackState.dismissPlayerOverlaysForRemoteControl()
            }
            when (command) {
                is HonomiRemoteCommand.SelectSource -> {
                    val channel = remoteInputs?.channels?.get(command.sourceId)
                    if (channel == null) {
                        remoteControlViewModel.client.reportSourceResult(command.commandId, false, "SOURCE_UNAVAILABLE")
                    } else {
                        remoteControlViewModel.client.sendState(contentType = "Live", title = channel.name, isBuffering = true)
                        remoteControlViewModel.client.reportInputSignal(channel.id, "Loading", null)
                        state.bringRemotePlaybackToFront()
                        onRemotePlaybackOpened()
                        state.playbackState.enterLive(channel)
                        homeViewModel.saveLastChannel(channel)
                        remoteControlViewModel.client.reportSourceResult(command.commandId, true, null)
                    }
                }
                is HonomiRemoteCommand.OpenLive -> {
                    openPlayback(
                        PlaybackOpenRequest.Live(
                            displayChannelId = command.displayChannelId,
                            source = PlaybackOpenSource.Remote,
                        )
                    )
                }
                is HonomiRemoteCommand.OpenRecording -> {
                    openPlayback(
                        PlaybackOpenRequest.Recording(
                            recordedProgramId = command.recordedProgramId,
                            positionSeconds = command.positionSeconds,
                            source = PlaybackOpenSource.Remote,
                        )
                    )
                }
                // CM スキップ設定は再生セッションではなくアプリ全体の設定なので、
                // 再生画面が開いていなくても反映できるようここで受ける。
                is HonomiRemoteCommand.EnableTemporaryNHKHide -> {
                    try {
                        if (!remoteControlViewModel.client.wasNHKCommandApplied(command.commandId)) {
                            nhkExclusionRepository.enableTemporaryNHKHide()
                        }
                        excludeCurrentNHKContent()
                        withFrameNanos { }
                        remoteControlViewModel.client.reportNHKExclusionResult(command.commandId, applied = true)
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        remoteControlViewModel.client.reportNHKExclusionResult(command.commandId, applied = false)
                        state.toastMessage = "N〇K除外モードを保存できませんでした。もう一度操作してください。（NHK_EXCLUSION_SAVE_FAILED）"
                    }
                }
                is HonomiRemoteCommand.SetCMSkipMode -> {
                    settingsViewModel.setCmSkipMode(command.mode)
                    state.toastMessage = "CMスキップ: ${command.mode.displayLabel}"
                }
                HonomiRemoteCommand.Play,
                HonomiRemoteCommand.Pause,
                HonomiRemoteCommand.Stop,
                is HonomiRemoteCommand.SeekRelative,
                is HonomiRemoteCommand.SeekTo,
                is HonomiRemoteCommand.SkipChapter,
                HonomiRemoteCommand.SkipCM,
                HonomiRemoteCommand.VolumeUp,
                HonomiRemoteCommand.VolumeDown,
                HonomiRemoteCommand.VolumeMute -> Unit
            }
        }
    }

    // =========================================================================================
    // ★ 追加: アプリのバックグラウンド移行（スリープ）と復帰を検知する機構
    // =========================================================================================
    val appWakeState = rememberAppWakeState(channelViewModel::setAppAwake)

    val timeFormat by settingsViewModel.timeFormat.collectAsState()
    val geminiApiKey by settingsViewModel.geminiApiKey.collectAsState(initial = "")
    val hideAppsTab by settingsViewModel.hideAppsTab.collectAsState(initial = false)
    val deepTv by settingsViewModel.deepTvFeatureSettings.collectAsState()

    val closeAiConcierge = { restoreFocus: Boolean ->
        state.isAiConciergeOpen = false
        aiConciergeViewModel.resetState()

        if (restoreFocus) {
            if (state.currentTabIndex == state.getVisibleTabs(hideAppsTab).indexOf("番組表")) {
                epgViewModel.triggerRestore()
            } else {
                state.aiFocusReturnTick++
            }
        }
        epgViewModel.clearSearch()
    }

    val backendType by settingsViewModel.configuredBackend.collectAsState(initial = null)
    val tif = backendType == "TIF"
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

    val tabs = state.getVisibleTabs(hideAppsTab)

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
                        state.playbackState.isPlayerMiniListOpen = false; state.playbackState.playerIsSubMenuOpen = false
                        state.playbackState.isPlayerSubMenuOpen = false; state.playbackState.isPlayerSceneSearchOpen = false
                        state.playbackState.enterLive(target)
                        homeViewModel.saveLastChannel(target)
                    }
                }

                is AiConciergeAction.PlayRecorded -> {
                    closeAiConcierge(false)
                    val target =
                        recordViewModel.recentRecordings.value.find { it.id == action.videoId }
                    if (target != null) {
                        state.playbackState.isPlayerMiniListOpen = false; state.playbackState.playerIsSubMenuOpen = false
                        state.playbackState.isPlayerSubMenuOpen = false; state.playbackState.isPlayerSceneSearchOpen = false
                        state.playbackState.enterRecorded(target)
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
                    reserveViewModel.addReserve(action.programId) { result ->
                        when (result) {
                            SingleReservationResult.Success ->
                                state.toastMessage = "番組の録画予約を完了しました"
                            is SingleReservationResult.Failure ->
                                state.toastMessage = "${result.failure.message} [${result.failure.code}]"
                            SingleReservationResult.AlreadySubmitting -> Unit
                        }
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
    val isChannelRecovering by channelViewModel.isRecovering.collectAsState()
    val isHomeLoading by homeViewModel.isLoading.collectAsState()
    val isChannelError by channelViewModel.connectionError.collectAsState()
    val channelErrorReason by channelViewModel.channelError.collectAsState()
    val isSettingsInitialized by settingsViewModel.isSettingsInitialized.collectAsState()
    val upgradeCheckState by settingsViewModel.upgradeCheckState.collectAsState()
    val upgradeTourSteps = remember { UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE) }
    val spotlightTargets = rememberUpgradeSpotlightTargets()
    var isUpgradeTourDismissed by remember { mutableStateOf(false) }
    var isManualUpgradeTourOpen by remember { mutableStateOf(false) }
    var isUpgradeTourControllingSettings by remember { mutableStateOf(false) }

    LaunchedEffect(upgradeCheckState) {
        val ready = upgradeCheckState as? SettingsViewModel.UpgradeCheckState.Ready ?: return@LaunchedEffect
        val shouldShow = shouldShowUpgradeLandingTour(
            lastSeenVersionCode = ready.lastSeenVersionCode,
            currentVersionCode = BuildConfig.VERSION_CODE,
            isSettingsInitialized = ready.isSettingsInitialized,
            hasTourForCurrentVersion = upgradeTourSteps.isNotEmpty(),
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
    val screensaverEnabled by settingsViewModel.screensaverEnabled.collectAsState()
    val screensaverTimeout by settingsViewModel.screensaverTimeoutMinutes.collectAsState()
    val playerTimeout by settingsViewModel.screensaverPlayerTimeoutMinutes.collectAsState()
    val playerStyle by settingsViewModel.screensaverPlayerStyle.collectAsState()
    val endedHoldMinutes by settingsViewModel.playerEndSleepMinutes.collectAsState()
    val pausedPlayback by com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker.pausedPlayback.collectAsState()
    val playbackEnded by com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker.playbackEnded.collectAsState()
    val systemSleepAllowed by com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker.systemSleepAllowed.collectAsState()
    val usePlayerSaver = pausedPlayback != null && playerStyle == "PLAYER"
    com.beeregg2001.komorebi.ui.screensaver.EndedPlaybackScreenHold(endedHoldMinutes, appWakeState)
    val screensaverInterval by settingsViewModel.screensaverIntervalSeconds.collectAsState()
    val screensaverTransition by settingsViewModel.screensaverTransition.collectAsState()
    val screensaverImages by settingsViewModel.screensaverImageUris.collectAsState()
    val screensaverShowUpcoming by settingsViewModel.screensaverShowUpcoming.collectAsState()
    val screensaverState = rememberInAppScreensaverState(
        enabled = screensaverEnabled,
        timeoutMinutes = screensaverTimeout,
        hasImages = screensaverImages.isNotEmpty(),
        wakeState = appWakeState,
        playerTimeoutMinutes = playerTimeout,
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
        !screensaverState.isVisible
    val osPipAutoEnter = deepTv.backgroundPlaybackMode == com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP
    val effectivePipIsPlaying = if (isTifLiveTarget) true else systemPlaybackIsPlaying
    val backgroundAudioPlaybackEnabled =
        deepTv.backgroundPlaybackMode == com.beeregg2001.komorebi.data.BackgroundPlaybackMode.AUDIO_ONLY &&
            state.playbackState.isPlaybackActive &&
            !isTifLiveTarget &&
            (renderTarget is PlaybackTarget.Live ||
                renderTarget is PlaybackTarget.Recorded ||
                renderTarget is PlaybackTarget.Smb)
    LaunchedEffect(isOsPipEligible, osPipAutoEnter, effectivePipIsPlaying, systemPlaybackCanSeek) {
        onOsPipStateChanged(isOsPipEligible, osPipAutoEnter, effectivePipIsPlaying, systemPlaybackCanSeek)
    }
    DisposableEffect(Unit) {
        onDispose { onOsPipStateChanged(false, false, false, false) }
    }
    LaunchedEffect(osPipDismissVersion) {
        if (osPipDismissVersion > 0) {
            state.playbackState.leavePlayback(returningFromPlayer = false)
        }
    }
    LaunchedEffect(isInOsPip, state.toastMessage) {
        if (isInOsPip && state.toastMessage != null) {
            state.toastMessage = null
        }
    }
    val defaultSystemChannel = remember(groupedChannels) {
        val channels = groupedChannels.values.flatten()
        channels.firstOrNull { it.type == "GR" } ?: channels.firstOrNull()
    }
    val isPlaybackScreenOpen = state.playbackState.isPlaybackActive
    IdleSystemMediaSession(enabled = !isPlaybackScreenOpen) {
        val channel = defaultSystemChannel ?: return@IdleSystemMediaSession
        state.playbackState.enterLive(channel)
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

    val startupChannelSetting = state.startupSettings?.startupChannel
    val startupTabs = state.getVisibleTabs(state.startupSettings?.hideAppsTab ?: hideAppsTab)
    var isLongPressHandled by remember { mutableStateOf(false) }

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
            val savedId = if (desiredStartupChannel == "LAST_WATCHED") lastWatchedId else desiredStartupChannel
            val targetChannel = when {
                !tifActive && savedId?.startsWith("tif:") == true ->
                    flatChannels.firstOrNull { !it.id.startsWith("tif:") } ?: return@LaunchedEffect
                else -> flatChannels.find { it.id == savedId }
                    ?: if (tif || savedId?.startsWith("tif:") == true) return@LaunchedEffect else flatChannels.first()
            }

            state.playbackState.enterLive(targetChannel)
            homeViewModel.saveLastChannel(targetChannel)

            val liveIndex = startupTabs.indexOf("ライブ")
            if (liveIndex != -1) state.currentTabIndex = liveIndex
            state.isUiReady = true
        }
    }

    LaunchedEffect(state.isRecordListOpen) { if (state.isRecordListOpen) recordViewModel.triggerSmartSync() }

    val settingsDataRefreshRevision by settingsViewModel.dataRefreshRevision.collectAsState()
    var settingsDataRevisionAtOpen by remember { mutableIntStateOf(settingsDataRefreshRevision) }
    LaunchedEffect(state.isSettingsOpen) {
        if (state.isSettingsOpen) {
            settingsDataRevisionAtOpen = settingsDataRefreshRevision
        }
    }

    val closeSettings = {
        val needsBackgroundRefresh = shouldRefreshDataAfterSettingsClose(
            dataRevisionChanged = settingsDataRefreshRevision != settingsDataRevisionAtOpen,
            isDataReady = state.isDataReady,
            isOfflineMode = state.isOfflineMode,
            isChannelError = isChannelError,
        )
        state.closeSettings()
        if (needsBackgroundRefresh) {
            state.showConnectionErrorDialog = false
            state.isOfflineMode = false
            channelViewModel.fetchChannels()
            epgViewModel.preloadAllEpgData()
            homeViewModel.refreshHomeData()
            recordViewModel.fetchRecentRecordings(forceRefresh = false)
            reserveViewModel.fetchReserves()
        }
    }

    val clearRootOverlays = {
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
        state.isOnAirOpen = false
        state.isEpgJumpMenuOpen = false
        state.showDeleteConfirmDialog = false
        state.isAiConciergeOpen = false
        state.showAiKeyboardInput = false
    }

    val dismissRootOverlays = {
        if (state.isSettingsOpen) {
            closeSettings()
        }
        clearRootOverlays()
    }

    val returnToLauncherHome = {
        val hasActiveModule = state.hasActiveModuleOrOverlay()
        val preservedTabIndex = state.currentTabIndex

        if (state.isSettingsOpen) {
            closeSettings()
        }

        if (hasActiveModule) {
            state.currentTabIndex = preservedTabIndex
            state.returnToCurrentTabFromModule(hideAppsTab)
        } else {
            state.resetForLauncherHome("ホーム", hideAppsTab)
        }
        if (tabs.getOrNull(state.currentTabIndex) == "番組表") epgViewModel.ensureInitialDataLoaded()
        clearRootOverlays()
    }

    LaunchedEffect(homeIntentVersion) {
        if (homeIntentVersion > 0) {
            if (isUpgradeTourControllingSettings) {
                isUpgradeTourControllingSettings = false
                isManualUpgradeTourOpen = false
                isUpgradeTourDismissed = true
                settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
            }
            returnToLauncherHome()
        }
    }

    LaunchedEffect(settingsIntentVersion) {
        if (settingsIntentVersion > 0) {
            state.isSettingsOpen = true
        }
    }

    LaunchedEffect(state.isSettingsOpen, state.isSmbLibraryOpen) {
        if (state.isSettingsOpen || state.isSmbLibraryOpen) state.toastMessage = null
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
            state.playbackState.isPlaybackActive -> {
                state.playbackState.handleBack()
            }

            state.isSettingsOpen -> closeSettings()
            state.epgSelectedProgram != null -> state.epgSelectedProgram = null
            state.selectedReserve != null -> state.selectedReserve = null
            state.isEpgJumpMenuOpen -> state.isEpgJumpMenuOpen = false

            state.isSmbLibraryOpen -> state.isSmbLibraryOpen = false

            state.isOnAirOpen -> state.closeOnAir()

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
                    state.showConnectionErrorDialog = true; state.isDataReady = false
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
                    state.showConnectionErrorDialog = false; state.isDataReady = true
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

    val isSystemReady =
        ((state.isDataReady && state.isSplashFinished) || (!isSettingsInitialized && state.isSplashFinished)) &&
                state.hasAppliedStartupTab && (startupChannelSetting == "OFF" || state.hasAppliedStartupChannel || state.isOfflineMode || (tif && !isChannelLoading && groupedChannels.isEmpty()))

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

    val latestStartupUpdateGate by rememberUpdatedState(
        startupRenderPlan.showMainContent ||
            state.showConnectionErrorDialog ||
            !isSettingsInitialized
    )
    LaunchedEffect(appWakeState.isAwake, appWakeState.generation) {
        if (appWakeState.isAwake) {
            nhkExclusionRepository.refreshExpiry()
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

    val lateNightWatchMode by settingsViewModel.lateNightWatchMode.collectAsState()
    val spoilerBlurEnabled by settingsViewModel.spoilerBlurEnabled.collectAsState()
    val jikkyoFestivalAlert by settingsViewModel.jikkyoFestivalAlert.collectAsState()
    val cmPanicZapEnabled by settingsViewModel.cmPanicZapEnabled.collectAsState()
    val liveRows by channelViewModel.liveRows.collectAsState()
    val festivalAlertedAt = remember { mutableMapOf<String, Long>() }
    var cmPanicReturnChannel by remember { mutableStateOf<com.beeregg2001.komorebi.data.model.Channel?>(null) }

    LaunchedEffect(state.playbackState.livePlayback) {
        if (state.playbackState.livePlayback == null) {
            cmPanicReturnChannel = null
        }
    }

    LaunchedEffect(jikkyoFestivalAlert, liveRows, state.playbackState.livePlayback?.channel?.id) {
        if (jikkyoFestivalAlert != "ON" || state.isSettingsOpen) return@LaunchedEffect
        val nowMillis = System.currentTimeMillis()
        val alert = com.beeregg2001.komorebi.common.detectJikkyoFestival(
            rows = liveRows,
            currentChannelId = state.playbackState.livePlayback?.channel?.id,
            alertedAtMillis = festivalAlertedAt,
            nowMillis = nowMillis,
        )
        if (alert != null) {
            festivalAlertedAt[alert.channel.id] = nowMillis
            state.toastMessage = alert.bannerText
        }
    }

    CompositionLocalProvider(
        com.beeregg2001.komorebi.common.LocalLateNightWatchEnabled provides (lateNightWatchMode == "ON"),
        com.beeregg2001.komorebi.common.LocalSpoilerBlurEnabled provides (spoilerBlurEnabled == "ON"),
    ) {
    KomorebiTheme(theme = currentTheme) {
        var showExitDialog by remember { mutableStateOf(false) }
        val isUpgradeTourActive = startupRenderPlan.showMainContent &&
            !state.playbackState.isPlaybackActive &&
            (!state.isSettingsOpen || isUpgradeTourControllingSettings) &&
            !state.showConnectionErrorDialog &&
            !state.isRecordListOpen &&
            !state.isSeriesListOpen &&
            !state.isSmbLibraryOpen &&
            !state.isOnAirOpen &&
            state.epgSelectedProgram == null &&
            state.selectedReserve == null &&
            state.editingCondition == null &&
            !showExitDialog &&
            (isManualUpgradeTourOpen || (!isUpgradeTourDismissed &&
            (upgradeCheckState as? SettingsViewModel.UpgradeCheckState.Ready)?.let { ready ->
                shouldShowUpgradeLandingTour(
                    lastSeenVersionCode = ready.lastSeenVersionCode,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    isSettingsInitialized = ready.isSettingsInitialized,
                    hasTourForCurrentVersion = upgradeTourSteps.isNotEmpty(),
                )
            } == true))
        var channelNumberBuffer by remember { mutableStateOf("") }
        var channelNumberInputTick by remember { mutableIntStateOf(0) }
        var channelNumberNotFound by remember { mutableStateOf<String?>(null) }
        var channelNumberNotFoundTick by remember { mutableIntStateOf(0) }
        var consumedShortcutKeyCode by remember { mutableStateOf<Int?>(null) }
        var consumedOsdActionKeyCode by remember { mutableStateOf<Int?>(null) }

        val tunableChannels = remember(groupedChannels, nhkExclusionState) {
            groupedChannels.values.flatten().filterNot(nhkExclusionRepository::isExcluded)
        }
        val liveChannel = state.playbackState.livePlayback?.channel
        val currentLiveType = liveChannel?.type
        val matchedChannelPreview = remember(channelNumberBuffer, tunableChannels, currentLiveType) {
            if (channelNumberBuffer.isEmpty()) null
            else ChannelNumberResolver.resolve(channelNumberBuffer, tunableChannels, currentLiveType)
        }
        val isLiveFullscreen = liveChannel != null && !state.playbackState.isMiniPlayerMode
        val isVideoFullscreen =
            (state.playbackState.recordedPlayback != null || state.playbackState.smbPlayback != null) &&
                !state.playbackState.isMiniPlayerMode
        val isTifPassthrough = liveChannel != null &&
            liveChannel.id.startsWith("tif:") &&
            (state.backendType != "TIF" || liveChannel.id.startsWith("tif:input:"))
        val hasRootOverlay = !startupRenderPlan.showMainContent ||
            state.showConnectionErrorDialog ||
            state.isRecordListOpen ||
            state.isSeriesListOpen ||
            state.isSmbLibraryOpen ||
            state.isOnAirOpen ||
            state.isEpgJumpMenuOpen ||
            state.showDeleteConfirmDialog ||
            state.epgSelectedProgram != null ||
            state.selectedReserve != null ||
            state.editingReserveItem != null ||
            state.editingNewProgram != null ||
            state.reserveToDelete != null ||
            state.selectedProgramForAutoReserve != null ||
            state.editingCondition != null ||
            state.selectedConditionReserveItem != null ||
            showExitDialog ||
            screensaverState.isVisible
        val remoteKeyContext = RemoteKeyContext(
            isInOsPip = isInOsPip,
            isSettingsOpen = state.isSettingsOpen,
            isAiOverlayOpen = state.isAiConciergeOpen || state.showAiKeyboardInput,
            hasRootOverlay = hasRootOverlay,
            isLivePlaybackFullscreen = isLiveFullscreen,
            isTifPassthrough = isTifPassthrough,
            isRecordedPlaybackFullscreen = isVideoFullscreen,
        )

        val commitChannelNumber = {
            val input = channelNumberBuffer
            channelNumberBuffer = ""
            if (input.isNotEmpty()) {
                val resolved = ChannelNumberResolver.resolve(input, tunableChannels, currentLiveType)
                if (resolved != null) {
                    channelNumberNotFound = null
                    state.playbackState.dismissPlayerOverlaysForRemoteControl()
                    state.playbackState.enterLive(
                        channel = resolved,
                        exitMiniPlayer = state.playbackState.livePlayback == null,
                    )
                    homeViewModel.saveLastChannel(resolved)
                } else {
                    channelNumberNotFound = input
                    channelNumberNotFoundTick++
                }
            }
        }

        val handleRemoteShortcut = { shortcut: RemoteShortcut ->
            when (shortcut) {
                RemoteShortcut.GUIDE -> {
                    dismissRootOverlays()
                    val tabIndex = tabs.indexOf("番組表")
                    if (tabIndex != -1) {
                        if (state.playbackState.isPlaybackActive) {
                            state.playbackState.enterMiniPlayer()
                        }
                        state.currentTabIndex = tabIndex
                        epgViewModel.ensureInitialDataLoaded()
                    } else {
                        state.toastMessage =
                            "番組表を開けません：現在の接続先は番組表に対応していません。接続設定でメインシステムを確認してください。"
                    }
                }
                RemoteShortcut.DVR -> {
                    dismissRootOverlays()
                    val tabIndex = tabs.indexOf("ビデオ")
                    if (tabIndex != -1) {
                        if (state.playbackState.isPlaybackActive) {
                            state.playbackState.enterMiniPlayer()
                        }
                        state.currentTabIndex = tabIndex
                        state.isRecordListOpen = true
                    } else {
                        state.toastMessage =
                            "録画リストを開けません：現在の接続先は録画リストに対応していません。接続設定でメインシステムを確認してください。"
                    }
                }
                RemoteShortcut.TV -> {
                    if (state.playbackState.livePlayback != null &&
                        !state.playbackState.isMiniPlayerMode &&
                        !hasRootOverlay &&
                        !state.isSettingsOpen
                    ) {
                        state.playbackState.playerShowOverlay = true
                    } else if (state.playbackState.livePlayback != null) {
                        dismissRootOverlays()
                        state.playbackState.exitMiniPlayer()
                    } else {
                        dismissRootOverlays()
                        val lastCandidate = lastChannels.firstOrNull { ch ->
                            !nhkExclusionRepository.isExcluded(ch) &&
                                (tifActive || !ch.id.startsWith("tif:"))
                        }?.let { last ->
                            tunableChannels.firstOrNull {
                                it.id == last.id || it.displayChannelId == last.displayChannelId
                            } ?: last
                        }
                        val targetChannel = lastCandidate
                            ?: defaultSystemChannel?.takeIf { !nhkExclusionRepository.isExcluded(it) }
                        if (targetChannel != null) {
                            state.playbackState.dismissPlayerOverlaysForRemoteControl()
                            state.playbackState.enterLive(targetChannel)
                            homeViewModel.saveLastChannel(targetChannel)
                        } else {
                            val liveIndex = tabs.indexOf("ライブ")
                            if (liveIndex != -1) state.currentTabIndex = liveIndex
                        }
                    }
                }
            }
        }

        LaunchedEffect(channelNumberBuffer, channelNumberInputTick) {
            if (channelNumberBuffer.isEmpty()) return@LaunchedEffect
            delay(RemoteKeyShortcuts.commitDelayMs(channelNumberBuffer))
            commitChannelNumber()
        }
        LaunchedEffect(channelNumberNotFound, channelNumberNotFoundTick) {
            if (channelNumberNotFound == null) return@LaunchedEffect
            delay(RemoteKeyShortcuts.NOT_FOUND_DISPLAY_MS)
            channelNumberNotFound = null
        }
        LaunchedEffect(isInOsPip) {
            if (isInOsPip) {
                channelNumberBuffer = ""
                channelNumberNotFound = null
                consumedShortcutKeyCode = null
                consumedOsdActionKeyCode = null
            }
        }

        RootSystemMediaSessionHost(
            playbackSessionEpoch = state.playbackState.playbackSessionEpoch,
            remoteContentType = when {
                state.playbackState.livePlayback != null -> "Live"
                state.playbackState.recordedPlayback != null -> "Recorded"
                state.playbackState.renderPlaybackTarget is PlaybackTarget.Smb -> "Smb"
                else -> "Idle"
            },
            isPlaybackSwitching = state.playbackState.playbackPhase is PlaybackPhase.Switching,
            remoteControlClient = remoteControlViewModel.client,
            backgroundAudioPlaybackEnabled = backgroundAudioPlaybackEnabled,
            onSystemPlaybackSnapshotChanged = { isPlaying, canSeek ->
                systemPlaybackIsPlaying = isPlaying
                systemPlaybackCanSeek = canSeek
            },
            onRegisterTransportActions = { togglePlayPause, seekRelativeMs, _ ->
                onRegisterPipTransportActions(togglePlayPause, seekRelativeMs)
            },
        ) {
            val colors = KomorebiTheme.colors
            val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

            Box(
            modifier = Modifier
                .fillMaxSize()
                // ★ 修正: onPreviewKeyEvent（トップダウン）に戻し、グローバルショートカットの確実性を復活
                .onPreviewKeyEvent { event ->
                    val nativeKeyCode = event.nativeKeyEvent.keyCode
                    if (event.type == KeyEventType.KeyUp && consumedOsdActionKeyCode == nativeKeyCode) {
                        consumedOsdActionKeyCode = null
                        return@onPreviewKeyEvent true
                    }
                    if (cmPanicZapEnabled == "ON" &&
                        isLiveFullscreen &&
                        nativeKeyCode == android.view.KeyEvent.KEYCODE_PROG_YELLOW &&
                        event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount == 0
                    ) {
                        val current = liveChannel
                        if (current != null) {
                            val returnTarget = cmPanicReturnChannel
                            if (returnTarget != null && returnTarget.id != current.id) {
                                cmPanicReturnChannel = null
                                state.playbackState.enterLive(returnTarget)
                                homeViewModel.saveLastChannel(returnTarget)
                                state.toastMessage = "元のチャンネルへ復帰: ${returnTarget.name}"
                            } else {
                                val escape = com.beeregg2001.komorebi.common.pickCmEscapeChannel(tunableChannels, current.id)
                                if (escape != null) {
                                    cmPanicReturnChannel = current
                                    state.playbackState.enterLive(escape)
                                    homeViewModel.saveLastChannel(escape)
                                    state.toastMessage = "CM緊急退避 → ${escape.name}（黄ボタンで復帰）"
                                } else {
                                    state.toastMessage = "退避先の実況チャンネルが見つかりません"
                                }
                            }
                            consumedOsdActionKeyCode = nativeKeyCode
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (channelNumberBuffer.isNotEmpty()) {
                        val isBackKey = nativeKeyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            nativeKeyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                        val isConfirmKey = nativeKeyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            nativeKeyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                            nativeKeyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                        if (isBackKey) {
                            if (event.type == KeyEventType.KeyDown) {
                                channelNumberBuffer = ""
                                channelNumberNotFound = null
                                consumedOsdActionKeyCode = nativeKeyCode
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (isConfirmKey) {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                consumedOsdActionKeyCode = nativeKeyCode
                                commitChannelNumber()
                            }
                            return@onPreviewKeyEvent true
                        }
                    }

                    val isCenterKey =
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter

                    // ★ 追加: 「プレイヤーのUI（シークバーやボタン）が表示されている時」は、
                    // 親玉（MainRoot）がイベントを横取りせず、子要素（PlayerControlsの早送り連打など）にイベントを譲る！
                    val isVideoUiVisible =
                        (state.playbackState.recordedPlayback != null || state.playbackState.smbPlayback != null) && state.playbackState.showPlayerControls
                    val isLiveUiVisible = state.playbackState.livePlayback != null && state.playbackState.playerShowOverlay
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
                .onKeyEvent { event ->
                    val nativeKeyCode = event.nativeKeyEvent.keyCode
                    if (event.type == KeyEventType.KeyUp && consumedShortcutKeyCode == nativeKeyCode) {
                        consumedShortcutKeyCode = null
                        return@onKeyEvent true
                    }
                    val shortcut = RemoteKeyShortcuts.shortcutFor(nativeKeyCode)
                    if (shortcut != null &&
                        deepTv.remoteShortcutKeysEnabled &&
                        RemoteKeyShortcuts.shortcutsAllowed(remoteKeyContext)
                    ) {
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                consumedShortcutKeyCode = nativeKeyCode
                                channelNumberBuffer = ""
                                channelNumberNotFound = null
                                handleRemoteShortcut(shortcut)
                            }
                            return@onKeyEvent true
                        }
                    }
                    val digit = RemoteKeyShortcuts.digitFor(nativeKeyCode)
                    if (digit != null &&
                        deepTv.remoteNumberTuningEnabled &&
                        RemoteKeyShortcuts.numberTuningAllowed(remoteKeyContext)
                    ) {
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                consumedShortcutKeyCode = nativeKeyCode
                                channelNumberNotFound = null
                                channelNumberBuffer = RemoteKeyShortcuts.append(channelNumberBuffer, digit)
                                channelNumberInputTick++
                            }
                            return@onKeyEvent true
                        }
                    }
                    false
                }
                .background(colors.background)
                .background(backgroundBrush)
        ) {
            if (startupRenderPlan.showSeasonalDecor) {
                SeasonalDecor(
                    season = themeSeason,
                    isDark = colors.isDark,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }

            if (startupRenderPlan.showMainContent) {
                val triggerScreensaver: () -> Unit = {
                    if (screensaverEnabled && (screensaverImages.isNotEmpty() || pausedPlayback != null)) {
                        screensaverState.showNow()
                    } else {
                        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                            component = android.content.ComponentName(
                                "com.android.systemui",
                                "com.android.systemui.Somnambulator",
                            )
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            onInternalActivityLaunch()
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            if (screensaverImages.isNotEmpty() || pausedPlayback != null) {
                                screensaverState.showNow()
                            }
                        }
                    }
                }
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
                        onTriggerScreensaver = triggerScreensaver,
                        onInternalActivityLaunch = onInternalActivityLaunch,
                        notificationCenterViewModel = notificationCenterViewModel,
                        showUpgradeTour = isUpgradeTourActive,
                        spotlightTargets = spotlightTargets,
                    )

                    if (state.playbackState.isPlaybackActive) {
                        MainRootPlaybackHost(
                            state = state,
                            suppressChrome = (screensaverState.isVisible && usePlayerSaver) || isInOsPip,
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
                            onTriggerScreensaver = triggerScreensaver,
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
                        state.settingsInitialCategoryIndex = 1
                        state.settingsInitialFocusItemIndex = null
                        state.isSettingsOpen = true
                    },
                    modifier = Modifier.zIndex(6f),
                    reason = if (!isNetworkAvailable) {
                        "ネットワーク接続がありません。接続設定を確認してください。接続が戻ると自動で更新します。（DEVICE_OFFLINE）"
                    } else channelErrorReason,
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
                    state.settingsInitialCategoryIndex = 0
                    state.settingsInitialFocusItemIndex = null
                    state.isSettingsOpen = true
                },
                onOpenWhatsNew = {
                    closeSettings()
                    isManualUpgradeTourOpen = true
                },
                isUpgradeTourOverlayActive = isUpgradeTourActive,
                spotlightTargets = spotlightTargets,
            )
            if (isUpgradeTourActive && upgradeTourSteps.isNotEmpty()) {
                UpgradeSpotlightOverlay(
                    steps = upgradeTourSteps,
                    targets = spotlightTargets,
                    onStepChanged = { idx ->
                        val step = upgradeTourSteps.getOrNull(idx)
                        when (step?.targetId) {
                            UpgradeTourTargetId.DEEP_TV_SETTING -> {
                                isUpgradeTourControllingSettings = true
                                state.settingsInitialCategoryIndex = 9
                                state.settingsInitialFocusItemIndex = null
                                state.isSettingsOpen = true
                            }
                            UpgradeTourTargetId.COMMENT_ENGINE_SETTING -> {
                                isUpgradeTourControllingSettings = true
                                state.settingsInitialCategoryIndex = 5
                                state.settingsInitialFocusItemIndex = 6
                                state.isSettingsOpen = true
                            }
                            else -> {
                                if (isUpgradeTourControllingSettings) {
                                    isUpgradeTourControllingSettings = false
                                    state.isSettingsOpen = false
                                }
                            }
                        }
                    },
                    onStepAction = { action ->
                        isUpgradeTourControllingSettings = false
                        isManualUpgradeTourOpen = false
                        isUpgradeTourDismissed = true
                        settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
                        when (action) {
                            UpgradeTourAction.OPEN_COMMENT_ENGINE_SETTINGS -> {
                                state.settingsInitialCategoryIndex = 5
                                state.settingsInitialFocusItemIndex = 6
                                state.isSettingsOpen = true
                            }
                            UpgradeTourAction.OPEN_DEEP_TV_SETTINGS -> {
                                state.settingsInitialCategoryIndex = 9
                                state.settingsInitialFocusItemIndex = null
                                state.isSettingsOpen = true
                            }
                        }
                    },
                    onFinish = {
                        val wasControllingSettings = isUpgradeTourControllingSettings
                        isUpgradeTourControllingSettings = false
                        isManualUpgradeTourOpen = false
                        isUpgradeTourDismissed = true
                        settingsViewModel.markVersionUpgradeSeen(BuildConfig.VERSION_CODE)
                        if (wasControllingSettings) {
                            state.isSettingsOpen = false
                        }
                    },
                    modifier = Modifier.zIndex(50f),
                )
            }
            if (showExitDialog) {
                ExitDialog(onConfirm = onExitApp, onDismiss = { showExitDialog = false })
            }
            ChannelNumberOsd(
                state = ChannelNumberOsdState(
                    inputBuffer = channelNumberBuffer,
                    matchedChannelName = matchedChannelPreview?.name,
                    notFoundInput = channelNumberNotFound,
                ),
                isInOsPip = isInOsPip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 36.dp)
                    .zIndex(5f),
            )
            if (!isInOsPip && appWakeState.isAwake && screensaverState.isVisible && (screensaverImages.isNotEmpty() || pausedPlayback != null)) {
                val upcoming = com.beeregg2001.komorebi.ui.screensaver.rememberUpcomingRecordings(
                    enabled = screensaverShowUpcoming && !usePlayerSaver,
                    visible = true,
                )
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = screensaverState::dismiss,
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    // Preserve the paused video rather than dimming the whole player window.
                    val dialogView = androidx.compose.ui.platform.LocalView.current
                    SideEffect {
                        dialogView.keepScreenOn = pausedPlayback != null || (playbackEnded && !systemSleepAllowed)
                    }
                    DisposableEffect(dialogView, usePlayerSaver) {
                        val window = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                        val previousDim = window?.attributes?.dimAmount ?: 0f
                        if (usePlayerSaver) window?.setDimAmount(0f)
                        onDispose { window?.setDimAmount(previousDim) }
                    }
                    val screensaverFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) {
                        screensaverFocusRequester.requestFocus()
                    }
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(screensaverFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                com.beeregg2001.komorebi.ui.screensaver.handleScreensaverKey(
                                    event.nativeKeyEvent, screensaverState::dismiss,
                                ) { key -> context.getSystemService(android.media.AudioManager::class.java).dispatchMediaKeyEvent(key) }
                            }
                            .clickable { screensaverState.dismiss() }
                    ) {
                        if (usePlayerSaver) {
                            com.beeregg2001.komorebi.ui.screensaver.PlayerScreensaverContent(
                                title = pausedPlayback?.title.orEmpty(),
                                subtitle = pausedPlayback?.subtitle.orEmpty(),
                            )
                        } else com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent(
                            imageUris = screensaverImages,
                            intervalSeconds = screensaverInterval,
                            transition = screensaverTransition,
                            upcomingRecordings = upcoming,
                        )
                    }
                }
            }
            }
        }
    }
    }
}
