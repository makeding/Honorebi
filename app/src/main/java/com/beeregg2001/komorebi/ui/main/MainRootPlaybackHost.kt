@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.media.CastRouteDiscovery
import com.beeregg2001.komorebi.ui.live.LivePlayerScreen
import com.beeregg2001.komorebi.ui.video.player.RecordedProgramSelectionReason
import com.beeregg2001.komorebi.ui.video.player.VideoPlayerScreen

/** Inputs already collected by [MainRootScreen] for the root playback surface. */
data class MainRootPlaybackHostData(
    val groupedChannels: Map<String, List<Channel>>,
    val watchHistory: List<KonomiHistoryProgram>,
    val recentRecordings: List<RecordedProgram>,
    val defaultLiveQuality: String,
    val defaultVideoQuality: String,
    val timeFormat: String,
    val isNetworkAvailable: Boolean,
)

/**
 * The root-owned playback surface.
 *
 * It deliberately remains below [RootSystemMediaSessionHost], so changing a
 * keyed recorded player does not release the MediaSession or Cast discovery.
 */
@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MainRootPlaybackHost(
    state: MainRootState,
    data: MainRootPlaybackHostData,
    onSaveLastChannel: (Channel) -> Unit,
    onPlaybackEnded: () -> Unit,
) {
    // Keep MediaRouter2 route discovery alive for the whole playback session.
    // A recorded-program transition replaces the keyed VideoPlayerScreen below,
    // but must not make Cast targets disappear while the next episode starts.
    CastRouteDiscovery()

    val playerWidth by animateDpAsState(
        targetValue = if (state.isMiniPlayerMode) 320.dp else 1920.dp,
        label = "width",
        animationSpec = tween(400),
    )
    val playerHeight by animateDpAsState(
        targetValue = if (state.isMiniPlayerMode) 180.dp else 1080.dp,
        label = "height",
        animationSpec = tween(400),
    )
    val playerPadding by animateDpAsState(
        targetValue = if (state.isMiniPlayerMode) 32.dp else 0.dp,
        label = "padding",
        animationSpec = tween(400),
    )
    val animeChannels = remember(data.groupedChannels) {
        data.groupedChannels.values
            .flatten()
            .filter { channel ->
                val present = channel.programPresent ?: return@filter false
                val genreHit = present.genres.orEmpty().any {
                    it.major == "アニメ・特撮" || it.major.contains("アニメ")
                }
                genreHit || present.title.contains("アニメ")
            }
            .distinctBy { it.id }
            .take(20)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f)
            .let {
                if (state.isMiniPlayerMode) it
                    .padding(bottom = playerPadding, end = playerPadding)
                    .wrapContentSize(Alignment.BottomEnd)
                else it
            }
            .size(playerWidth, playerHeight)
            .clip(RoundedCornerShape(if (state.isMiniPlayerMode) 12.dp else 0.dp)),
    ) {
        // Switching retains the committed target for the session/Cast lease,
        // while the host materializes the incoming recording until READY.
        when (val target = state.renderPlaybackTarget) {
            is PlaybackTarget.Live -> LivePlayerScreen(
                channel = target.channel,
                initialQuality = data.defaultLiveQuality,
                isMiniListOpen = state.isPlayerMiniListOpen,
                onMiniListToggle = { state.isPlayerMiniListOpen = it },
                showOverlay = state.playerShowOverlay,
                onShowOverlayChange = { state.playerShowOverlay = it },
                isManualOverlay = state.playerIsManualOverlay,
                onManualOverlayChange = { state.playerIsManualOverlay = it },
                isPinnedOverlay = state.playerIsPinnedOverlay,
                onPinnedOverlayChange = { state.playerIsPinnedOverlay = it },
                isSubMenuOpen = state.playerIsSubMenuOpen,
                onSubMenuToggle = { state.playerIsSubMenuOpen = it },
                onChannelSelect = { newChannel ->
                    state.enterLive(
                        newChannel,
                        exitMiniPlayer = false,
                    )
                },
                onChannelPlaybackCommitted = onSaveLastChannel,
                onChasePlaybackSelect = { program ->
                    state.isPlayerMiniListOpen = false
                    state.playerIsSubMenuOpen = false
                    state.isPlayerSubMenuOpen = false
                    state.isPlayerSceneSearchOpen = false
                    state.enterRecorded(program, playbackResumePositionMs(program, data.watchHistory))
                },
                onBackPressed = { state.leavePlayback() },
                onCheckDeviceCapabilities = {
                    state.leavePlayback()
                    state.settingsInitialCategoryIndex = 2
                    state.settingsInitialFocusItemIndex = 9
                    state.settingsOpenDeviceCapabilities = true
                    state.isSettingsOpen = true
                },
                onShowToast = { state.toastMessage = it },
                isPiPMode = state.isMiniPlayerMode,
                onPiPRequested = {
                    if (state.enterMiniPlayer()) state.toastMessage = "ミニプレイヤーに変更しました"
                },
                timeFormat = data.timeFormat,
            )

            is PlaybackTarget.Recorded -> {
                val selectedProgram = target.program
                val playbackToken = state.recordedPlaybackToken
                // Capture commitment for this player instance: its disposal still persists A
                // while B is being prepared, but an uncommitted B never writes history.
                val shouldPersistSelectedProgram =
                    (state.playbackTarget as? PlaybackTarget.Recorded)?.program?.id == selectedProgram.id
                androidx.compose.runtime.key(playbackToken) {
                    VideoPlayerScreen(
                        videoPlayerViewModel = hiltViewModel(),
                        settingsViewModel = hiltViewModel(),
                        program = selectedProgram,
                        initialPositionMs = state.renderInitialPlaybackPositionMs,
                        initialQuality = data.defaultVideoQuality,
                        isNetworkAvailable = data.isNetworkAvailable,
                        showControls = state.showPlayerControls,
                        onShowControlsChange = { state.showPlayerControls = it },
                        isSubMenuOpen = state.isPlayerSubMenuOpen,
                        onSubMenuToggle = { state.isPlayerSubMenuOpen = it },
                        isSceneSearchOpen = state.isPlayerSceneSearchOpen,
                        onSceneSearchToggle = { state.isPlayerSceneSearchOpen = it },
                        recentRecordings = data.recentRecordings,
                        animeChannels = animeChannels,
                        onProgramSelect = { program, selectionReason ->
                            state.isPlayerSubMenuOpen = false
                            state.isPlayerSceneSearchOpen = false
                            val switchReason = when (selectionReason) {
                                RecordedProgramSelectionReason.NextEpisode -> PlaybackSwitchReason.NextEpisode
                                RecordedProgramSelectionReason.PreviousEpisode -> PlaybackSwitchReason.PreviousEpisode
                                RecordedProgramSelectionReason.QuickSelect -> PlaybackSwitchReason.QuickSelect
                            }
                            state.beginRecordedSwitch(program = program, reason = switchReason)
                        },
                        recordedPlaybackToken = playbackToken,
                        isCurrentRecordedPlayback = state::isCurrentRecordedPlayback,
                        isRecordedSwitching = state.playbackPhase is PlaybackPhase.Switching,
                        onProgramReady = { readyProgramId, token ->
                            if (token.programId == readyProgramId && state.isCurrentRecordedPlayback(token)) {
                                state.commitRecordedSwitch(token)
                            }
                        },
                        onRecordedSwitchTerminalFailure = { token, failure ->
                            if (state.isCurrentRecordedPlayback(token) && state.failRecordedSwitch(token)) {
                                state.toastMessage = when (failure) {
                                    com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchTerminalFailure.InitialUrlUnavailable ->
                                        "次の番組のストリームURLを取得できませんでした"
                                    com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchTerminalFailure.SessionRenewalFailed ->
                                        "次の番組の再生セッションを更新できませんでした"
                                    com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchTerminalFailure.PlaybackError ->
                                        "次の番組の再生に失敗しました"
                                }
                            }
                        },
                        shouldPersistWatchHistory = { shouldPersistSelectedProgram },
                        onChannelSelect = { channel ->
                            state.isPlayerSubMenuOpen = false
                            state.isPlayerSceneSearchOpen = false
                            state.showPlayerControls = false
                            state.playerShowOverlay = false
                            state.enterLive(channel)
                            onSaveLastChannel(channel)
                        },
                        onPlaybackEnded = onPlaybackEnded,
                        onBackPressed = { state.leavePlayback() },
                        onShowToast = { state.toastMessage = it },
                        isPiPMode = state.isMiniPlayerMode,
                        onPiPRequested = {
                            if (state.enterMiniPlayer()) state.toastMessage = "ミニプレイヤーに変更しました"
                        },
                    )
                }
            }

            is PlaybackTarget.Smb -> {
                val baseProgram = data.recentRecordings.firstOrNull()
                if (baseProgram != null) {
                    val dummyProgram = baseProgram.copy(
                        id = target.item.path.hashCode(),
                        title = target.item.name,
                        description = "SMBネットワーク再生: ${target.item.path}",
                    )
                    VideoPlayerScreen(
                        videoPlayerViewModel = hiltViewModel(),
                        settingsViewModel = hiltViewModel(),
                        program = dummyProgram,
                        smbItem = target.item,
                        initialPositionMs = state.initialPlaybackPositionMs,
                        initialQuality = data.defaultVideoQuality,
                        isNetworkAvailable = data.isNetworkAvailable,
                        showControls = state.showPlayerControls,
                        onShowControlsChange = { state.showPlayerControls = it },
                        isSubMenuOpen = state.isPlayerSubMenuOpen,
                        onSubMenuToggle = { state.isPlayerSubMenuOpen = it },
                        isSceneSearchOpen = state.isPlayerSceneSearchOpen,
                        onSceneSearchToggle = { state.isPlayerSceneSearchOpen = it },
                        onBackPressed = { state.leavePlayback() },
                        onShowToast = { state.toastMessage = it },
                        isPiPMode = state.isMiniPlayerMode,
                        onPiPRequested = {
                            if (state.enterMiniPlayer()) state.toastMessage = "ミニプレイヤーに変更しました"
                        },
                    )
                } else {
                    LaunchedEffect(Unit) {
                        state.toastMessage = "再生用のダミーデータを生成できませんでした"
                        state.leavePlayback(returningFromPlayer = false)
                    }
                }
            }

            PlaybackTarget.None -> Unit
        }
    }
}
