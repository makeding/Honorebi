@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.media.SystemMediaSession
import com.beeregg2001.komorebi.ui.player.PlaybackSourceFeedback
import com.beeregg2001.komorebi.ui.player.PlaybackSourceRequest
import com.beeregg2001.komorebi.ui.player.PlayerSurface
import com.beeregg2001.komorebi.ui.player.PlaybackMediaInfo
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.ui.player.PlayerZoomOrigin
import com.beeregg2001.komorebi.ui.player.PlayerProgramPanel
import com.beeregg2001.komorebi.ui.player.PlayerProgramPresentation
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.subtitle.UpdateRecordedCaptionState
import com.beeregg2001.komorebi.ui.subtitle.rememberRecordedCaptionState
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.ui.video.smb.SmbPlaybackMetadata
import com.beeregg2001.komorebi.ui.video.player.policy.SmbPlaybackRecoveryDecision
import com.beeregg2001.komorebi.ui.video.player.policy.SmbPlaybackRecoveryPolicy
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel

/** Local-file playback deliberately has no RecordedProgram, provider URL, EPG or watch-history path. */
@Composable
internal fun SmbPlayerScreen(
    item: SmbItem,
    metadata: SmbPlaybackMetadata,
    initialPositionMs: Long,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    isPiPMode: Boolean,
    onPiPRequested: () -> Unit,
    settingsViewModel: SettingsViewModel,
) {
    val state = rememberVideoPlayerState()
    val fence = remember(metadata.stableId) {
        RecordedPlaybackFence(token = null, isCurrent = { true }, identity = metadata.stableId)
    }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember(metadata.stableId) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0L)) }
    var durationMs by remember(metadata.stableId) { mutableLongStateOf(0L) }
    val captions = rememberRecordedCaptionState(metadata.stableId)
    var subtitleLanguages by remember(metadata.stableId) { mutableStateOf(emptyList<NativeCaptionLanguage>()) }
    var subtitleLanguageId by remember(metadata.stableId) { mutableLongStateOf(1L) }
    var isProgramInfoOpen by remember(metadata.stableId) { mutableStateOf(false) }
    var isModernSettingsOpen by remember(metadata.stableId) { mutableStateOf(false) }
    var sourceRequest by remember(metadata.stableId) {
        mutableStateOf<PlaybackSourceRequest>(PlaybackSourceRequest.Pending())
    }
    val recoveryPolicy = remember(metadata.stableId) { SmbPlaybackRecoveryPolicy() }
    val timeFormat by settingsViewModel.timeFormat.collectAsState()
    val playerClockMode by settingsViewModel.playerClockMode.collectAsState()
    val playerUiMode by settingsViewModel.playerUiMode.collectAsState()
    val videoPlaybackSpeedStr by settingsViewModel.videoPlaybackSpeed.collectAsState()
    val smbSubtitleEnabledStr by settingsViewModel.smbSubtitleEnabled.collectAsState()
    val isModern = playerUiMode == "MODERN"
    var subtitleAvoidanceObstacles by remember { mutableStateOf(emptyList<Rect>()) }
    val subtitleAvoidanceProgress by animateFloatAsState(
        targetValue = if (
            showControls && !isSubMenuOpen && !isModernSettingsOpen && !isProgramInfoOpen &&
                sourceRequest == PlaybackSourceRequest.Ready &&
                !state.crop.isEnabled && subtitleAvoidanceObstacles.isNotEmpty()
        ) 1f else 0f,
        label = "smbControlsSubtitleAvoidance",
    )
    val subMenuFocusRequester = remember { FocusRequester() }
    val controlsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showControls, isSubMenuOpen, isModernSettingsOpen, sourceRequest) {
        if (isSubMenuOpen) subMenuFocusRequester.requestFocus()
        else if (!isModernSettingsOpen && sourceRequest == PlaybackSourceRequest.Ready && showControls) {
            controlsFocusRequester.requestFocus()
        }
    }
    val player = rememberManagedExoPlayer(
        program = null,
        recordedPlaybackFence = fence,
        isLiveStream = false,
        vs = state,
        scope = androidx.compose.runtime.rememberCoroutineScope(),
        onSubtitleCue = { cue -> captions.timeline.offer(captions.timeline.generation, cue) },
        subtitleLanguageId = subtitleLanguageId.toInt(),
        subtitleResetSerial = captions.resetSerial,
        onSubtitleLanguagesChanged = { subtitleLanguages = it },
        onVideoSizeChanged = { _, _, _ -> },
        onBufferingChanged = { isBuffering ->
            buffering = isBuffering
        },
        onDurationChanged = {
            durationMs = it.coerceAtLeast(0L)
            if (sourceRequest !is PlaybackSourceRequest.Failed) {
                sourceRequest = PlaybackSourceRequest.Ready
                recoveryPolicy.onReady()
            }
        },
        onPlaybackEnded = { onShowControlsChange(true) },
        onPlayerErrorRecovery = { failedPlayer, error ->
            positionMs = currentPositionMs(failedPlayer, positionMs)
            val failure = smbPlaybackFailure(error)
            when (recoveryPolicy.onPlayerError()) {
                SmbPlaybackRecoveryDecision.Reprepare -> {
                    sourceRequest = PlaybackSourceRequest.Pending(failure)
                    PlayerErrorRecovery.Reprepare
                }
                SmbPlaybackRecoveryDecision.ShowFailure -> {
                    buffering = false
                    sourceRequest = PlaybackSourceRequest.Failed(failure)
                    isProgramInfoOpen = false
                    isModernSettingsOpen = false
                    onSubMenuToggle(false)
                    onShowControlsChange(true)
                    PlayerErrorRecovery.Handled
                }
            }
        },
        onStopOrDispose = { stoppedPlayer ->
            positionMs = currentPositionMs(stoppedPlayer, positionMs)
        },
        settingsViewModel = settingsViewModel,
    )
    com.beeregg2001.komorebi.ui.screensaver.TrackPlayerIdle(player, metadata.title, metadata.location)

    LaunchedEffect(player, metadata.stableId, initialPositionMs) {
        sourceRequest = PlaybackSourceRequest.Pending()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(item.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(metadata.title).build())
                .build(),
            initialPositionMs.coerceAtLeast(0L),
        )
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(player, videoPlaybackSpeedStr) {
        val saved = videoPlaybackSpeedStr.toFloatOrNull()?.takeIf { it in PLAYBACK_SPEEDS } ?: 1.0f
        state.currentSpeed = saved
        player.setPlaybackSpeed(saved)
    }
    LaunchedEffect(smbSubtitleEnabledStr) {
        state.isSubtitleEnabled = smbSubtitleEnabledStr == "ON"
    }
    LaunchedEffect(isModern) {
        if (isModern) onSubMenuToggle(false) else isModernSettingsOpen = false
    }
    SystemMediaSession(
        player = player,
        title = metadata.title,
        subtitle = metadata.location,
        artworkUrl = metadata.thumbnailUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_VIDEO,
        isLoading = buffering,
        onSeekRelative = { delta -> player.seekTo((currentPositionMs(player, positionMs) + delta).coerceAtLeast(0L)) },
        onStop = onBackPressed,
    )
    UpdateRecordedCaptionState(captions, state.isSubtitleEnabled, subtitleLanguageId.toInt()) {
        currentPositionMs(player, positionMs)
    }
    RunRecordedDirectionalSeek(
        state = state,
        playbackIdentity = player,
        enabled = !isPiPMode && !isSubMenuOpen && !isModernSettingsOpen && !isProgramInfoOpen &&
            sourceRequest == PlaybackSourceRequest.Ready &&
            state.crop.mode == com.beeregg2001.komorebi.ui.player.PlayerCropMode.HIDDEN,
        durationMs = durationMs,
    )
    LaunchedEffect(showControls) { if (!showControls) state.directionalSeek.cancel() }
    BackHandler(enabled = isPiPMode) {}
    BackHandler(enabled = !isPiPMode && sourceRequest != PlaybackSourceRequest.Ready) {
        onBackPressed()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                state.handleKeyEvent(
                    keyEvent = event,
                    isPiPMode = isPiPMode,
                    isModern = isModern,
                    showControls = showControls,
                    isSubOverlayOpen = isSubMenuOpen || isModernSettingsOpen || isProgramInfoOpen ||
                        sourceRequest != PlaybackSourceRequest.Ready ||
                        state.crop.mode != com.beeregg2001.komorebi.ui.player.PlayerCropMode.HIDDEN,
                    chapters = emptyList(),
                    canOpenSceneSearch = false,
                    totalDurationMs = durationMs,
                    getCurrentPositionMs = { currentPositionMs(player, positionMs) },
                    performSeek = { target -> captions.seek(); player.seekTo(target.coerceAtLeast(0L)) },
                    triggerSeekingPreview = {},
                    onShowControlsChange = onShowControlsChange,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                    onSceneSearchToggle = {},
                    onSettingsMenuToggle = { isModernSettingsOpen = true },
                    onChapterListToggle = {},
                    onSubMenuToggle = onSubMenuToggle,
                    exoPlayerIsPlaying = player.isPlaying,
                    onPause = player::pause,
                    onPlay = player::play,
                    onSkipPreviousChapter = {},
                    onSkipNextChapter = {},
                    onSpeedUp = {
                        val idx = PLAYBACK_SPEEDS.indexOf(state.currentSpeed).coerceAtLeast(0)
                        val next = PLAYBACK_SPEEDS[(idx + 1).coerceAtMost(PLAYBACK_SPEEDS.lastIndex)]
                        state.currentSpeed = next
                        player.setPlaybackSpeed(next)
                        onShowToast("速度: ${next}x")
                        settingsViewModel.updateVideoPlaybackSpeed(next)
                    },
                    onSpeedDown = {
                        val idx = PLAYBACK_SPEEDS.indexOf(state.currentSpeed).coerceAtLeast(0)
                        val next = PLAYBACK_SPEEDS[(idx - 1).coerceAtLeast(0)]
                        state.currentSpeed = next
                        player.setPlaybackSpeed(next)
                        onShowToast("速度: ${next}x")
                        settingsViewModel.updateVideoPlaybackSpeed(next)
                    },
                )
            },
    ) {
        PlayerSurface(
            player = player,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (state.crop.isEnabled) {
                        scaleX = state.crop.zoomPercent / 100f
                        scaleY = state.crop.zoomPercent / 100f
                        translationX = size.width * state.crop.xPercent / 100f
                        translationY = size.height * state.crop.yPercent / 100f
                        transformOrigin = when (state.crop.origin) {
                            PlayerZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                            PlayerZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                            PlayerZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                            PlayerZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                        }
                    }
                },
        )
        NativeCaptionOverlay(
            cue = captions.caption.value,
            visible = state.isSubtitleEnabled,
            modifier = Modifier.fillMaxSize(),
            avoidanceObstacles = subtitleAvoidanceObstacles,
            avoidanceProgress = subtitleAvoidanceProgress,
        )
        NativeCaptionOverlay(
            cue = captions.superimpose.value,
            visible = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (
            buffering && sourceRequest == PlaybackSourceRequest.Ready &&
            !isSubMenuOpen && !isModernSettingsOpen && !isProgramInfoOpen
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                Text("読み込み中…", color = Color.White)
            }
        }
        SmbWallClock(
            timeFormat = timeFormat, clockMode = playerClockMode,
            showControls = showControls, isSubMenuOpen = isSubMenuOpen || isModernSettingsOpen,
            isProgramInfoOpen = isProgramInfoOpen, cropMode = state.crop.mode,
            isPiPMode = isPiPMode,
        )
        PlayerControls(
            mediaInfo = PlaybackMediaInfo.smb(metadata),
            capabilities = PlaybackUiCapabilities.Smb,
            timeFormat = timeFormat,
            allComments = emptyList(),
            tiledThumbnailUrl = metadata.thumbnailUrl,
            isVisible = showControls,
            isSeekingPreviewVisible = false,
            isModernUi = isModern,
            isPlaying = state.isPlayerPlaying,
            playbackSpeed = state.currentSpeed,
            hasChapters = false,
            initialPositionMs = positionMs,
            totalDurationMs = durationMs,
            initialBufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs),
            displayPositionMsProvider = { currentPositionMs(player, positionMs) },
            seekPreviewPositionMs = { state.directionalSeek.previewPositionMs },
            displayBufferedPositionMsProvider = { player.bufferedPosition.coerceAtLeast(currentPositionMs(player, positionMs)) },
            controlsFocusRequester = controlsFocusRequester,
            onSeekBarFocusChanged = {},
            onPlayPauseToggle = { if (player.isPlaying) player.pause() else player.play() },
            onSeekBack = { captions.seek(); player.seekTo((currentPositionMs(player, positionMs) - 10_000L).coerceAtLeast(0L)) },
            onSeekForward = { captions.seek(); player.seekTo(smbSeekTarget(currentPositionMs(player, positionMs) + 30_000L, durationMs)) },
            onChapterListToggle = {},
            onInfoToggle = { isProgramInfoOpen = true; onSubMenuToggle(false) },
            onSettingsToggle = {
                if (isModern) isModernSettingsOpen = true else onSubMenuToggle(true)
            },
            onAvoidanceObstaclesChanged = { obstacles ->
                if (obstacles.isNotEmpty()) subtitleAvoidanceObstacles = obstacles
            },
        )
        VideoTopSubMenuUI(
            mediaInfo = PlaybackMediaInfo.smb(metadata),
            currentProgram = null,
            seriesPrograms = emptyList(),
            quickPrograms = emptyList(),
            backendType = "",
            konomiIp = "",
            konomiPort = "",
            currentAudioMode = state.currentAudioMode,
            currentSpeed = state.currentSpeed,
            isSubtitleEnabled = state.isSubtitleEnabled,
            subtitleLanguages = subtitleLanguages,
            currentSubtitleLanguageId = subtitleLanguageId.toInt(),
            currentQuality = state.currentQuality,
            isCommentEnabled = false,
            isLCropEnabled = state.crop.isEnabled,
            cmSkipMode = CmSkipMode.OFF,
            hdrRenderMode = "",
            isHdrRenderModeSupported = false,
            isDataBroadcastingAvailable = false,
            isDataBroadcastingActive = false,
            availableQualities = emptyList(),
            focusRequester = subMenuFocusRequester,
            onAudioToggle = { toggleRecordedAudio(state, onShowToast) },
            onSpeedToggle = {
                cycleRecordedPlaybackSpeed(state, player, onShowToast)
                settingsViewModel.updateVideoPlaybackSpeed(state.currentSpeed)
            },
            onProgramInfo = {
                isProgramInfoOpen = true
                onSubMenuToggle(false)
            },
            onSubtitleToggle = {
                toggleRecordedSubtitle(state, onShowToast)
                settingsViewModel.updateSmbSubtitleEnabled(state.isSubtitleEnabled)
            },
            onSubtitleLanguageToggle = {
                val (id, message) = nextRecordedSubtitleLanguage(subtitleLanguageId.toInt(), subtitleLanguages)
                subtitleLanguageId = id.toLong()
                onShowToast(message)
            },
            onQualitySelect = {},
            onCommentToggle = {},
            onLCropToggle = { toggleRecordedLCrop(state) { onSubMenuToggle(false) } },
            onCmSkipModeToggle = {},
            onHdrRenderModeToggle = {},
            onDataBroadcastingToggle = {},
            onVideoSelect = {},
            isVisible = isSubMenuOpen,
            onCloseMenu = { onSubMenuToggle(false) },
            capabilities = PlaybackUiCapabilities.Smb,
        )
        AnimatedVisibility(
            visible = isModernSettingsOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ModernVideoSettingsOverlay(
                currentAudioMode = state.currentAudioMode,
                currentSpeed = state.currentSpeed,
                isSubtitleEnabled = state.isSubtitleEnabled,
                currentQuality = state.currentQuality,
                isCommentEnabled = false,
                isLCropEnabled = state.crop.isEnabled,
                cmSkipMode = CmSkipMode.OFF,
                availableQualities = emptyList(),
                onAudioToggle = { toggleRecordedAudio(state, onShowToast) },
                onSpeedToggle = {
                    cycleRecordedPlaybackSpeed(state, player, onShowToast)
                    settingsViewModel.updateVideoPlaybackSpeed(state.currentSpeed)
                },
                onSubtitleToggle = {
                    toggleRecordedSubtitle(state, onShowToast)
                    settingsViewModel.updateSmbSubtitleEnabled(state.isSubtitleEnabled)
                },
                onQualitySelect = {},
                onCommentToggle = {},
                onLCropToggle = { toggleRecordedLCrop(state) { isModernSettingsOpen = false } },
                onCmSkipModeToggle = {},
                capabilities = PlaybackUiCapabilities.Smb,
                onClose = { isModernSettingsOpen = false },
            )
        }
        if (isProgramInfoOpen) {
            val presentation = PlayerProgramPresentation.smb(metadata, "24h")
            BackHandler { isProgramInfoOpen = false }
            PlayerProgramPanel(
                channelName = presentation.channelName,
                logoUrl = presentation.logoUrl,
                shouldCropLogo = presentation.shouldCropLogo,
                title = presentation.title,
                description = presentation.description,
                detail = presentation.detail,
                metadata = presentation.metadata,
                scrollState = rememberScrollState(),
                feedback = {
                    Button(
                        onClick = { isProgramInfoOpen = false },
                        modifier = Modifier.width(120.dp),
                    ) { Text("閉じる") }
                },
            )
        }
        if (
            !isPiPMode && sourceRequest != PlaybackSourceRequest.Ready &&
            !isSubMenuOpen && !isModernSettingsOpen && !isProgramInfoOpen
        ) {
            PlaybackSourceFeedback(
                request = sourceRequest,
                modifier = Modifier.align(Alignment.Center),
                loadingText = "ファイルを読み込んでいます…",
                failureText = "ファイルを再生できませんでした。",
                onRetry = retry@{
                    val failure = (sourceRequest as? PlaybackSourceRequest.Failed)?.failure
                        ?: return@retry
                    if (!recoveryPolicy.beginManualRetry()) return@retry
                    positionMs = currentPositionMs(player, positionMs)
                    sourceRequest = PlaybackSourceRequest.Pending(failure)
                    buffering = true
                    player.seekTo(positionMs)
                    player.prepare()
                    player.playWhenReady = true
                },
                onBack = onBackPressed,
            )
        }
    }
}

private fun currentPositionMs(player: Player, fallback: Long): Long =
    player.currentPosition.takeUnless { it == C.TIME_UNSET || it < 0L } ?: fallback

private fun smbSeekTarget(targetMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)

internal fun smbPlaybackFailure(error: PlaybackException): UserFacingFailure {
    val message = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "SMB サーバーと通信できません。接続を確認して再試行してください。"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "ファイルが見つかりません。保存場所を確認してください。"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "ファイルを読み取る権限がありません。SMB の接続設定を確認してください。"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "この映像または音声をデコードできません。"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "このファイル形式を解析できません。"
        else -> "ファイルの再生に失敗しました。再試行するか、一覧に戻ってください。"
    }
    return UserFacingFailure(message, "SMB_${error.errorCodeName}")
}
