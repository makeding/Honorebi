@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.notification.InternalNowPlayingState
import com.beeregg2001.komorebi.notification.NotificationCenterViewModel
import com.beeregg2001.komorebi.ui.home.HomeLauncherScreen
import com.beeregg2001.komorebi.ui.home.UpgradeSpotlightTargets
import com.beeregg2001.komorebi.ui.home.rememberUpgradeSpotlightTargets
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.ui.reserve.ConditionEditDialog
import com.beeregg2001.komorebi.ui.video.RecordListScreen
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootBackground(
    state: MainRootState,
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel,
    settingsViewModel: SettingsViewModel,
    groupedChannels: Map<String, List<Channel>>,
    watchHistory: List<KonomiHistoryProgram>,
    conditions: List<ReservationCondition>,
    reserves: List<ReserveItem>,
    autoReserveKeywords: List<String>,
    mirakurunIp: String,
    mirakurunPort: String,
    konomiIp: String,
    konomiPort: String,
    timeFormat: String,
    networkConnectionStatus: NetworkConnectionStatus,
    safeTabIndex: Int,
    isSyncingInitial: Boolean,
    hasBackendFailure: Boolean,
    backendFailureReason: String?,
    isBackendRecoveryInProgress: Boolean,
    onExitApp: () -> Unit,
    onTriggerScreensaver: () -> Unit = {},
    onInternalActivityLaunch: () -> Unit = {},
    notificationCenterViewModel: NotificationCenterViewModel = hiltViewModel(),
    showUpgradeTour: Boolean = false,
    spotlightTargets: UpgradeSpotlightTargets = rememberUpgradeSpotlightTargets(),
) {
    val scope = rememberCoroutineScope()
    val onAirInitialFocusRequester = remember { FocusRequester() }

    // ★ 修正: SMBアイテムが選択されている場合も背面を非表示（ホームレイヤー維持）にする
    val showHomeLayer = !state.playbackState.isPlaybackActive || state.playbackState.isMiniPlayerMode

    if (showHomeLayer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            when {
                state.isRecordListOpen -> {
                    RecordListScreen(
                        konomiIp = konomiIp, konomiPort = konomiPort,
                        customTitle = state.openedSeriesTitle,
                        onProgramClick = { program, forcedPosition ->
                            val isRecordingProgram =
                                program.isRecording || program.recordedVideo.status == "Recording"
                            val isAnalyzed = isRecordingProgram || (program.recordedVideo.hasKeyFrames ?: true)
                            if (!isAnalyzed) return@RecordListScreen
                            state.playbackState.enterRecorded(
                                program,
                                playbackResumePositionMs(program, watchHistory, forcedPosition),
                            )
                        },
                        onBack = {
                            state.isRecordListOpen = false
                            if (state.openedSeriesTitle != null) {
                                state.isSeriesListOpen = true; state.openedSeriesTitle = null
                            }
                            recordViewModel.searchRecordings("")
                        },
                        isReturningFromPlayer = state.playbackState.isReturningFromPlayer,
                        lastPlayedProgramId = state.playbackState.lastPlayedRecordingId,
                        onReturnFocusConsumed = { state.playbackState.isReturningFromPlayer = false },
                        timeFormat = timeFormat,
                        autoReserveKeywords = autoReserveKeywords,
                        onAutoReserveClick = { program ->
                            state.selectedProgramForAutoReserve = program
                        },
                        aiFocusReturnTick = state.aiFocusReturnTick,
                        onAiReturnConsumed = { state.aiFocusReturnTick = 0 },
                    )
                }

                state.isSmbLibraryOpen -> {
                    com.beeregg2001.komorebi.ui.video.smb.SmbLibraryScreen(
                        onBack = { state.isSmbLibraryOpen = false },
                        onFileClick = { item ->
                            // ★ 修正: SMBファイルをクリックしたら、プレイヤーを起動する
                            state.playbackState.enterSmb(item)
                        },
                        // ★ 追加: 再生画面から戻ってきた際のフォーカス復帰用パラメータ
                        isReturningFromPlayer = state.playbackState.isReturningFromPlayer,
                        lastPlayedPath = state.playbackState.lastPlayedSmbPath,
                        onReturnFocusConsumed = { state.playbackState.isReturningFromPlayer = false }
                    )
                }

                state.isOnAirOpen -> {
                    com.beeregg2001.komorebi.ui.onair.OnAirScreen(
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        timeFormat = timeFormat,
                        onProgramClick = { program ->
                            val isRecordingProgram =
                                program.isRecording || program.recordedVideo.status == "Recording"
                            val isAnalyzed =
                                isRecordingProgram || (program.recordedVideo.hasKeyFrames ?: true)
                            if (isAnalyzed) {
                                state.playbackState.enterRecorded(
                                    program,
                                    playbackResumePositionMs(program, watchHistory),
                                )
                            }
                        },
                        onBack = { state.closeOnAir() },
                        onSettings = {
                            state.settingsInitialCategoryIndex = 1
                            state.settingsInitialFocusItemIndex = null
                            state.settingsOpenDeviceCapabilities = false
                            state.isSettingsOpen = true
                        },
                        initialFocusRequester = onAirInitialFocusRequester,
                        isReturningFromPlayer = state.playbackState.isReturningFromPlayer,
                        onReturnFocusConsumed = { state.playbackState.isReturningFromPlayer = false },
                        topFocusRequester = FocusRequester.Cancel,
                    )
                }

                state.editingCondition != null -> {
                    val currentCondition = conditions.find { it.id == state.editingCondition!!.id }
                        ?: state.editingCondition!!
                    val relatedReserves =
                        reserves.filter { it.comment.contains(currentCondition.programSearchCondition.keyword) }

                    ConditionEditDialog(
                        condition = currentCondition,
                        relatedReserves = relatedReserves,
                        onConfirmUpdate = { isEnabled, keyword, daysOfWeek, startH, startM, endH, endM, exc, tOnly, bType, fuzzy, dup, pri, relay, exact ->
                            reserveViewModel.updateEpgReserve(
                                originalCondition = currentCondition,
                                isEnabled = isEnabled,
                                keyword = keyword,
                                daysOfWeek = daysOfWeek,
                                startHour = startH,
                                startMinute = startM,
                                endHour = endH,
                                endMinute = endM,
                                excludeKeyword = exc,
                                isTitleOnly = tOnly,
                                broadcastType = bType,
                                isFuzzySearch = fuzzy,
                                duplicateScope = dup,
                                priority = pri,
                                isEventRelay = relay,
                                isExactRecord = exact,
                                onSuccess = {
                                    scope.launch {
                                        state.editingCondition = null
                                        delay(300)
                                        state.toastMessage = "予約条件を更新しました"
                                    }
                                }
                            )
                        },
                        onConfirmDelete = { deleteRelated ->
                            reserveViewModel.deleteConditionWithCleanup(
                                condition = currentCondition, deleteRelatedReserves = deleteRelated,
                                onSuccess = {
                                    scope.launch {
                                        state.editingCondition = null
                                        delay(300)
                                        state.toastMessage =
                                            if (deleteRelated) "条件と関連する予約をすべて削除しました" else "予約条件を削除しました"
                                    }
                                }
                            )
                        },
                        onDismiss = { state.editingCondition = null },
                        onReserveItemClick = { state.selectedConditionReserveItem = it },
                        timeFormat = timeFormat
                    )
                }

                else -> {
                    val internalNowPlaying = if (state.playbackState.isMiniPlayerMode) {
                        when (val target = state.playbackState.renderPlaybackTarget) {
                            is PlaybackTarget.Live -> {
                                val programTitle = target.channel.programPresent?.title.orEmpty()
                                InternalNowPlayingState(
                                    title = programTitle.ifBlank { target.channel.name },
                                    subtitle = if (programTitle.isNotBlank()) target.channel.name else "",
                                    badgeLabel = "再生中",
                                    isPlaying = true,
                                )
                            }
                            is PlaybackTarget.Recorded -> InternalNowPlayingState(
                                title = target.program.title,
                                subtitle = target.program.channel?.name.orEmpty(),
                                badgeLabel = "再生中",
                                isPlaying = true,
                            )
                            is PlaybackTarget.Smb -> InternalNowPlayingState(
                                title = target.item.name,
                                subtitle = "SMB",
                                badgeLabel = "再生中",
                                isPlaying = true,
                            )
                            PlaybackTarget.None -> null
                        }
                    } else {
                        null
                    }
                    HomeLauncherScreen(
                        channelViewModel = channelViewModel,
                        homeViewModel = homeViewModel,
                        epgViewModel = epgViewModel,
                        recordViewModel = recordViewModel,
                        reserveViewModel = reserveViewModel,
                        groupedChannels = groupedChannels,
                        mirakurunIp = mirakurunIp,
                        mirakurunPort = mirakurunPort,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        initialTabIndex = safeTabIndex,
                        startupFocusTarget = state.startupSettings?.startupFocusTarget
                            ?: com.beeregg2001.komorebi.data.StartupFocusTarget.SIDEBAR,
                        launcherHomeFocusTick = state.launcherHomeFocusTick,
                        onTabChange = { state.currentTabIndex = it },
                        selectedChannel = state.playbackState.livePlayback?.channel,
                        onChannelClick = { channel ->
                            if (channel != null) {
                                state.playbackState.enterLive(channel)
                                if (!channel.id.startsWith("tif:")) homeViewModel.saveLastChannel(channel)
                            } else if (state.playbackState.livePlayback != null) {
                                state.playbackState.leavePlayback(returningFromPlayer = false)
                            }
                        },
                        selectedProgram = state.playbackState.recordedPlayback?.program,
                        onProgramSelected = { program ->
                            if (program != null) {
                                val isRecordingProgram =
                                    program.isRecording || program.recordedVideo.status == "Recording"
                                val isAnalyzed = isRecordingProgram || (program.recordedVideo.hasKeyFrames ?: true)
                                if (!isAnalyzed) return@HomeLauncherScreen
                                state.playbackState.enterRecorded(program, playbackResumePositionMs(program, watchHistory))
                            }
                        },
                        onReserveSelected = { reserveItem -> state.selectedReserve = reserveItem },
                        onConditionClick = { condition -> state.editingCondition = condition },
                        isReserveOverlayOpen = state.selectedReserve != null || state.editingCondition != null,
                        epgSelectedProgram = state.epgSelectedProgram,
                        onEpgProgramSelected = { state.epgSelectedProgram = it },
                        isEpgJumpMenuOpen = state.isEpgJumpMenuOpen,
                        onEpgJumpMenuStateChanged = { state.isEpgJumpMenuOpen = it },
                        triggerBack = state.triggerHomeBack,
                        onBackTriggered = { state.triggerHomeBack = false },
                        onUiReady = { state.isUiReady = true },
                        onNavigateToPlayer = { channelId, _, _ ->
                            val channel =
                                groupedChannels.values.flatten().find { ch -> ch.id == channelId }
                            if (channel != null) {
                                state.playbackState.enterLive(channel)
                                homeViewModel.saveLastChannel(channel)
                                state.epgSelectedProgram = null; state.isEpgJumpMenuOpen = false
                            }
                        },
                        lastPlayerChannelId = state.playbackState.lastSelectedChannelId,
                        lastPlayerProgramId = state.playbackState.lastSelectedProgramId,
                        isSettingsOpen = state.isSettingsOpen,
                        onSettingsToggle = { open ->
                            if (open) {
                                state.settingsInitialCategoryIndex = 0
                                state.settingsInitialFocusItemIndex = null
                            }
                            state.isSettingsOpen = open
                        },
                        onOpenAppInfo = {
                            state.settingsInitialCategoryIndex = 11
                            state.settingsInitialFocusItemIndex = 0
                            state.isSettingsOpen = true
                        },
                        isRecordListOpen = state.isRecordListOpen,
                        onShowAllRecordings = { state.isRecordListOpen = true },
                        onCloseRecordList = { state.isRecordListOpen = false },
                        onShowSeriesList = { state.isSeriesListOpen = true },
                        onShowSmbLibrary = { state.isSmbLibraryOpen = true },
                        onShowOnAir = { state.isOnAirOpen = true },
                        isReturningFromPlayer = state.playbackState.isReturningFromPlayer,
                        onReturnFocusConsumed = { state.playbackState.isReturningFromPlayer = false },
                        isUiReadyFlag = state.isUiReady,
                        settingsViewModel = settingsViewModel,
                        timeFormat = timeFormat,
                        networkConnectionStatus = networkConnectionStatus,
                        hasBackendFailure = hasBackendFailure,
                        backendFailureReason = backendFailureReason,
                        isBackendRecoveryInProgress = isBackendRecoveryInProgress,
                        hasActivePlayer = state.playbackState.isMiniPlayerMode,
                        internalNowPlaying = internalNowPlaying,
                        onReturnToPlayerClick = { state.playbackState.isMiniPlayerMode = false },
                        onInternalActivityLaunch = onInternalActivityLaunch,
                        onClockClick = onTriggerScreensaver,
                        aiFocusReturnTick = state.aiFocusReturnTick,
                        onAiReturnConsumed = { state.aiFocusReturnTick = 0 },
                        overlayReturnFocusTick = state.overlayReturnFocusTick,
                        onOverlayReturnConsumed = { state.overlayReturnFocusTick = 0 },
                        showUpgradeTour = showUpgradeTour,
                        spotlightTargets = spotlightTargets,
                        notificationCenterViewModel = notificationCenterViewModel,
                    )
                }
            }

            if (!state.playbackState.isPlaybackActive && !isSyncingInitial) {
                SyncProgressIndicator(
                    recordViewModel = recordViewModel,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 40.dp, bottom = 40.dp)
                )
            }
        }
    }
}
