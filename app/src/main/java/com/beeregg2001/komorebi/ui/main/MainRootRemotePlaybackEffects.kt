package com.beeregg2001.komorebi.ui.main

import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.navigation.PlaybackOpenRequest
import com.beeregg2001.komorebi.navigation.PlaybackOpenSource
import com.beeregg2001.komorebi.ui.player.PlaybackSwitchReason
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.ui.player.shouldDismissPlayerOverlaysForRemoteCommand
import com.beeregg2001.komorebi.ui.video.player.displayLabel
import com.beeregg2001.komorebi.viewmodel.AiConciergeAction
import com.beeregg2001.komorebi.viewmodel.AiConciergeViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.ProgramOpenLookup
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.RemoteControlViewModel
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.SingleReservationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.OffsetDateTime

internal fun excludeCurrentNHKContent(
    state: MainRootState,
    isExclusionActive: Boolean,
    isChannelExcluded: (Channel) -> Boolean,
    isProgramExcluded: (RecordedProgram) -> Boolean,
    isChannelIdExcluded: (String) -> Boolean,
    isReserveExcluded: (ReserveItem) -> Boolean,
    onSendIdleState: () -> Unit,
    invalidatePendingOpens: Boolean = true,
): Boolean {
    fun isExcludedTarget(target: PlaybackTarget): Boolean = when (target) {
        is PlaybackTarget.Live -> isChannelExcluded(target.channel)
        is PlaybackTarget.Recorded -> isProgramExcluded(target.program)
        else -> false
    }
    val excluded = isExcludedTarget(state.playbackState.renderPlaybackTarget) ||
        isExcludedTarget(state.playbackState.playbackTarget)
    if (excluded) {
        state.playbackState.resetPlayback()
        onSendIdleState()
    }
    if (isExclusionActive) {
        if (invalidatePendingOpens) {
            state.playbackState.invalidatePlaybackOpenIntents()
        }
        if (state.epgSelectedProgram?.channel_id?.let(isChannelIdExcluded) == true) {
            state.epgSelectedProgram = null
        }
        if (state.selectedProgramForAutoReserve?.let(isProgramExcluded) == true) {
            state.selectedProgramForAutoReserve = null
        }
        if (state.selectedReserve?.let(isReserveExcluded) == true) state.selectedReserve = null
        if (state.editingReserveItem?.let(isReserveExcluded) == true) state.editingReserveItem = null
        if (state.reserveToDelete?.let(isReserveExcluded) == true) state.reserveToDelete = null
        if (state.selectedConditionReserveItem?.let(isReserveExcluded) == true) state.selectedConditionReserveItem = null
        if (state.editingNewProgram?.channel_id?.let(isChannelIdExcluded) == true) state.editingNewProgram = null
    }
    return excluded
}

internal fun excludeCurrentNHKContent(
    state: MainRootState,
    nhkExclusionRepository: NHKExclusionRepository,
    onSendIdleState: () -> Unit,
    invalidatePendingOpens: Boolean = true,
): Boolean = excludeCurrentNHKContent(
    state = state,
    isExclusionActive = nhkExclusionRepository.state.value.isActive,
    isChannelExcluded = nhkExclusionRepository::isExcluded,
    isProgramExcluded = nhkExclusionRepository::isExcluded,
    isChannelIdExcluded = nhkExclusionRepository::isExcludedChannelId,
    isReserveExcluded = nhkExclusionRepository::isExcluded,
    onSendIdleState = onSendIdleState,
    invalidatePendingOpens = invalidatePendingOpens,
)

internal fun playbackOpenFailureMessage(request: PlaybackOpenRequest): String = when (request) {
    is PlaybackOpenRequest.Live -> when (request.source) {
        PlaybackOpenSource.Remote -> "リモートで指定されたチャンネルを開けませんでした"
        PlaybackOpenSource.DeepLink -> "指定されたチャンネルを開けませんでした"
    }
    is PlaybackOpenRequest.Recording -> when (request.source) {
        PlaybackOpenSource.Remote -> "リモートで指定された録画を開けませんでした"
        PlaybackOpenSource.DeepLink -> "指定された録画を開けませんでした"
    }
}

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun MainRootRemotePlaybackEffects(
    state: MainRootState,
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    recordViewModel: RecordViewModel,
    remoteControlViewModel: RemoteControlViewModel,
    launchedFromDeepLink: Boolean,
    deepLinkRequests: Flow<PlaybackOpenRequest>,
    onRemotePlaybackOpened: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnRemotePlaybackOpened by rememberUpdatedState(onRemotePlaybackOpened)

    val hideEmptyInputs by settingsViewModel.tifHideEmptyInputs.collectAsState()
    val integrationActive by settingsViewModel.tifIntegrationActive.collectAsState()
    val showTuners by settingsViewModel.tifShowTunerInputs.collectAsState()
    var inputGeneration by remember { mutableIntStateOf(0) }
    var remoteInputs by remember { mutableStateOf<RemoteInputs?>(null) }
    val selectedInputId = (state.playbackState.renderPlaybackTarget as? PlaybackTarget.Live)?.channel?.id

    DisposableEffect(context) {
        val manager = context.getSystemService(TvInputManager::class.java)
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) { inputGeneration++ }
            override fun onInputRemoved(inputId: String) { inputGeneration++ }
            override fun onInputUpdated(inputId: String) { inputGeneration++ }
            override fun onInputStateChanged(inputId: String, state: Int) { inputGeneration++ }
        }
        manager?.registerCallback(callback, Handler(Looper.getMainLooper()))
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

    LaunchedEffect(integrationActive) {
        if (!integrationActive) {
            (state.playbackState.renderPlaybackTarget as? PlaybackTarget.Live)
                ?.takeIf { it.channel.id.startsWith("tif:") }
                ?.let { state.playbackState.leavePlayback(returningFromPlayer = false) }
        }
    }

    LaunchedEffect(nhkExclusionState, state.playbackState.renderPlaybackTarget) {
        val newlyActivated = wasNHKExclusionLoaded && !wasNHKExclusionActive && nhkExclusionState.isActive
        wasNHKExclusionLoaded = nhkExclusionState.isLoaded
        wasNHKExclusionActive = nhkExclusionState.isActive
        excludeCurrentNHKContent(
            state = state,
            nhkExclusionRepository = nhkExclusionRepository,
            onSendIdleState = { remoteControlViewModel.client.sendState(contentType = "Idle") },
            invalidatePendingOpens = newlyActivated,
        )
        remoteControlViewModel.client.refreshNHKExclusionState()
    }

    fun openPlayback(request: PlaybackOpenRequest) {
        val intent = state.playbackState.beginPlaybackOpenIntent()
        scope.launch {
            val exclusionLoaded = withTimeoutOrNull(10_000) {
                nhkExclusionRepository.state.first { it.isLoaded }
            }
            if (exclusionLoaded == null) {
                if (state.playbackState.failPlaybackOpenIntent(intent)) {
                    state.showToast(playbackOpenFailureMessage(request))
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
                                state.showToast(playbackOpenFailureMessage(request))
                            }
                        }
                        nhkExclusionRepository.isExcluded(channel) -> {
                            state.playbackState.failPlaybackOpenIntent(intent)
                        }
                        state.playbackState.completePlaybackOpenIntent(intent) -> {
                            state.bringRemotePlaybackToFront()
                            currentOnRemotePlaybackOpened()
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
                                currentOnRemotePlaybackOpened()
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
                                state.showToast(playbackOpenFailureMessage(request))
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
                    if (command.sourceId == KOMOREBI_SELF_SOURCE_ID) {
                        val isOnTifInput = (state.playbackState.renderPlaybackTarget as? PlaybackTarget.Live)
                            ?.channel?.id?.startsWith("tif:") == true
                        if (isOnTifInput) {
                            state.playbackState.leavePlayback(returningFromPlayer = false)
                            remoteControlViewModel.client.sendState(contentType = "Idle")
                        }
                        remoteControlViewModel.client.reportSourceResult(command.commandId, true, null)
                    } else {
                        val channel = remoteInputs?.channels?.get(command.sourceId)
                        if (channel == null) {
                            remoteControlViewModel.client.reportSourceResult(command.commandId, false, "SOURCE_UNAVAILABLE")
                        } else {
                            val contentType = if (com.beeregg2001.komorebi.ui.live.usesLiveOsd(channel.id, channel.type)) "Live" else "Input"
                            remoteControlViewModel.client.sendState(contentType = contentType, title = channel.name, isBuffering = true)
                            remoteControlViewModel.client.reportInputSignal(channel.id, "Loading", null)
                            state.bringRemotePlaybackToFront()
                            currentOnRemotePlaybackOpened()
                            state.playbackState.enterLive(channel)
                            homeViewModel.saveLastChannel(channel)
                            remoteControlViewModel.client.reportSourceResult(command.commandId, true, null)
                        }
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
                        excludeCurrentNHKContent(
                            state = state,
                            nhkExclusionRepository = nhkExclusionRepository,
                            onSendIdleState = { remoteControlViewModel.client.sendState(contentType = "Idle") },
                        )
                        withFrameNanos { }
                        remoteControlViewModel.client.reportNHKExclusionResult(command.commandId, applied = true)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        remoteControlViewModel.client.reportNHKExclusionResult(command.commandId, applied = false)
                        state.showToast("N〇K除外モードを保存できませんでした。もう一度操作してください。（NHK_EXCLUSION_SAVE_FAILED）")
                    }
                }
                is HonomiRemoteCommand.SetCMSkipMode -> {
                    settingsViewModel.setCmSkipMode(command.mode)
                    state.showToast("CMスキップ: ${command.mode.displayLabel}")
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
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun MainRootAiConciergeEffect(
    enabled: Boolean,
    state: MainRootState,
    tabs: List<String>,
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    homeViewModel: HomeViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    aiConciergeViewModel: AiConciergeViewModel,
    onCloseAiConcierge: (restoreFocus: Boolean) -> Unit,
) {
    if (!enabled) return
    val scope = rememberCoroutineScope()
    val currentTabs by rememberUpdatedState(tabs)
    val currentOnCloseAiConcierge by rememberUpdatedState(onCloseAiConcierge)

    LaunchedEffect(Unit) {
        aiConciergeViewModel.pendingAction.collect { action ->
            when (action) {
                is AiConciergeAction.PlayLive -> {
                    currentOnCloseAiConcierge(false)
                    val target = channelViewModel.groupedChannels.value.values.flatten()
                        .find { it.id == action.channelId }
                    if (target != null) {
                        state.playbackState.isPlayerMiniListOpen = false
                        state.playbackState.playerIsSubMenuOpen = false
                        state.playbackState.isPlayerSubMenuOpen = false
                        state.playbackState.isPlayerSceneSearchOpen = false
                        state.playbackState.enterLive(target)
                        homeViewModel.saveLastChannel(target)
                    }
                }

                is AiConciergeAction.PlayRecorded -> {
                    currentOnCloseAiConcierge(false)
                    val target =
                        recordViewModel.recentRecordings.value.find { it.id == action.videoId }
                    if (target != null) {
                        state.playbackState.isPlayerMiniListOpen = false
                        state.playbackState.playerIsSubMenuOpen = false
                        state.playbackState.isPlayerSubMenuOpen = false
                        state.playbackState.isPlayerSceneSearchOpen = false
                        state.playbackState.enterRecorded(target)
                    }
                }

                is AiConciergeAction.SearchEpg -> {
                    currentOnCloseAiConcierge(false)
                    val isOnlyDate =
                        action.keyword.isBlank() && action.genre.isBlank() && action.date.isNotBlank() && !action.isLiveOnly && action.channelName.isBlank()
                    if (isOnlyDate) {
                        try {
                            val parsedDate = LocalDate.parse(action.date.replace("/", "-"))
                            val jumpTime = OffsetDateTime.now().withYear(parsedDate.year)
                                .withMonth(parsedDate.monthValue)
                                .withDayOfMonth(parsedDate.dayOfMonth).withHour(4).withMinute(0)
                                .withSecond(0).withNano(0)
                            epgViewModel.updateTargetTime(jumpTime)
                            val tabIndex = currentTabs.indexOf("番組表")
                            if (tabIndex != -1) state.currentTabIndex = tabIndex
                            state.showToast("${action.date} の番組表に移動しました")
                        } catch (e: Exception) {
                            state.showToast("日付の指定が正しくありません")
                        }
                    } else {
                        epgViewModel.executeSearch(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName,
                        )
                        val tabIndex = currentTabs.indexOf("番組表")
                        if (tabIndex != -1) state.currentTabIndex = tabIndex
                        state.showToast(
                            if (action.keyword.isNotBlank()) "「${action.keyword}」の検索結果を表示します" else "検索結果を表示します"
                        )
                    }
                }

                is AiConciergeAction.SearchRecord -> {
                    currentOnCloseAiConcierge(false)
                    val tabIndex = currentTabs.indexOf("ビデオ")
                    if (tabIndex != -1) {
                        state.currentTabIndex = tabIndex
                        state.isRecordListOpen = true
                        if (action.keyword.isNotBlank()) recordViewModel.searchRecordings(
                            action.keyword.split(",").firstOrNull()?.trim() ?: ""
                        )
                        if (action.genre.isNotBlank()) recordViewModel.updateGenre(action.genre)
                        state.showToast(
                            if (action.keyword.isNotBlank()) "「${
                                action.keyword.split(",").firstOrNull()
                            }」の録画を検索します" else "録画リストを表示します"
                        )
                    } else state.showToast(
                        "現在設定されているシステムでは録画検索は利用できません"
                    )
                }

                is AiConciergeAction.ReqEpgSearch -> {
                    scope.launch {
                        val results = epgViewModel.searchSilently(
                            action.keyword,
                            action.genre,
                            action.date,
                            action.isLiveOnly,
                            action.channelName,
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
                                program.title.contains(kw, ignoreCase = true) ||
                                    program.description.contains(kw, ignoreCase = true)
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
                    currentOnCloseAiConcierge(true)
                    reserveViewModel.addReserve(action.programId) { result ->
                        when (result) {
                            SingleReservationResult.Success ->
                                state.showToast("番組の録画予約を完了しました")
                            is SingleReservationResult.Failure ->
                                state.showToast("${result.failure.message} [${result.failure.code}]")
                            SingleReservationResult.AlreadySubmitting -> Unit
                        }
                    }
                }

                is AiConciergeAction.ReserveAuto -> {
                    currentOnCloseAiConcierge(true)
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
                            state.showToast("「${action.keyword}」の自動録画条件を登録しました")
                        },
                    )
                }
            }
        }
    }
}
