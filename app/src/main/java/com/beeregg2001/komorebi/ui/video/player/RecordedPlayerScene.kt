@file:OptIn(ExperimentalAnimationApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaMetadata
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.player.B60_INITIAL_MEDIA_PLANE
import com.beeregg2001.komorebi.ui.player.B60MediaPlane
import com.beeregg2001.komorebi.ui.player.DataBroadcastingColorKey
import com.beeregg2001.komorebi.ui.player.DataBroadcastingInputState
import com.beeregg2001.komorebi.ui.player.PlayerSurface
import com.beeregg2001.komorebi.ui.player.PlaybackMediaInfo
import com.beeregg2001.komorebi.ui.player.PlayerCropMode
import com.beeregg2001.komorebi.ui.player.PlayerZoomOrigin
import com.beeregg2001.komorebi.ui.player.DataBroadcastingColorSelectorOverlay
import com.beeregg2001.komorebi.ui.player.DataBroadcastingWebViewOverlay
import com.beeregg2001.komorebi.ui.player.DataBroadcastingRemoteCommand
import com.beeregg2001.komorebi.ui.player.b60MediaPlane
import com.beeregg2001.komorebi.ui.player.isDataBroadcastingToggleKeyEvent
import com.beeregg2001.komorebi.media.SystemMediaSession
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.video.player.policy.normalizeQuickSeriesKey
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun BoxScope.RecordedMediaSurface(
    isDataBroadcastingActive: Boolean,
    dataBroadcastingStore: B60DataBroadcastingStore,
    dataBroadcastingChannel: Channel,
    exoPlayer: ExoPlayer,
    dataBroadcastingRemoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    onMediaPlane: (B60MediaPlane?) -> Unit,
    onBlankModeChanged: (Boolean) -> Unit,
    closeDataBroadcasting: () -> Unit,
    dataBroadcastingMediaPlane: B60MediaPlane?,
    isDataBroadcastingBlank: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    pixelWidthHeightRatio: Float,
    state: VideoPlayerState,
    renderedFrameGeneration: Int,
    mainFocusRequester: FocusRequester,
    isPiPMode: Boolean,
    isSubOverlayOpen: Boolean,
) {
    if (isDataBroadcastingActive) {
        DataBroadcastingWebViewOverlay(
            store = dataBroadcastingStore,
            channel = dataBroadcastingChannel,
            currentMediaTimeSeconds = {
                exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000.0
            },
            remoteCommand = dataBroadcastingRemoteCommand,
            onRemoteCommandConsumed = onRemoteCommandConsumed,
            onStatus = { status -> Log.d("VideoPlayerScreen", "Recorded B60: $status") },
            onMediaPlane = onMediaPlane,
            onBlankModeChanged = onBlankModeChanged,
            onApplicationExited = closeDataBroadcasting,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
        )
    }

    val videoSurfaceModifier = if (isDataBroadcastingActive) {
        val plane = dataBroadcastingMediaPlane
        when {
            isDataBroadcastingBlank -> Modifier
                .fillMaxSize()
                .zIndex(2f)

            plane == null -> Modifier
                .fillMaxSize()
                .b60MediaPlane(B60_INITIAL_MEDIA_PLANE)
                .zIndex(2f)

            plane.visible && plane.width > 0f && plane.height > 0f -> Modifier
                .fillMaxSize()
                .b60MediaPlane(plane)
                .zIndex(2f)

            else -> Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .fillMaxWidth(0.34f)
                .aspectRatio(16f / 9f)
                .zIndex(2f)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.45f),
                    RoundedCornerShape(4.dp)
                )
        }
    } else {
        Modifier.fillMaxSize()
    }

    val resizeMode = if (videoWidth > 0 && videoHeight > 0 &&
        (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat() >= 1.7f
    ) {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    } else {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    PlayerSurface(
        player = exoPlayer,
        resizeMode = resizeMode,
        modifier = videoSurfaceModifier
            .graphicsLayer {
                if (state.crop.isEnabled) {
                    scaleX = state.crop.zoomPercent / 100f
                    scaleY = state.crop.zoomPercent / 100f
                    translationX = size.width * (state.crop.xPercent / 100f)
                    translationY = size.height * (state.crop.yPercent / 100f)
                    transformOrigin = when (state.crop.origin) {
                        PlayerZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                        PlayerZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                        PlayerZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                        PlayerZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                    }
                } else {
                    scaleX = 1f
                    scaleY = 1f
                    translationX = 0f
                    translationY = 0f
                    transformOrigin = TransformOrigin.Center
                }
            }
            .semantics {
                contentDescription = if (renderedFrameGeneration > 0) {
                    "再生映像:$renderedFrameGeneration"
                } else {
                    "映像準備中"
                }
            }
            .focusRequester(mainFocusRequester)
            .focusable(
                !isPiPMode &&
                    !isSubOverlayOpen &&
                    state.crop.mode == PlayerCropMode.HIDDEN
            )
    )
}

@Composable
internal fun BoxScope.RecordedPlaybackOverlays(
    isHeavyUiReady: Boolean,
    state: VideoPlayerState,
    comments: SnapshotStateList<ArchivedComment>,
    currentPositionMs: () -> Long,
    commentPositionMs: () -> Long,
    commentSpeed: Float,
    commentFontSizeScale: Float,
    commentOpacity: Float,
    commentMaxLines: Int,
    isEmulator: Boolean,
    recordedPlaybackFence: RecordedPlaybackFence,
    subtitleCue: NativeCaptionCue?,
    superimposeCue: NativeCaptionCue?,
    isSubtitleBlockingOverlayOpen: Boolean,
    subtitleAvoidanceObstacles: List<Rect>,
    subtitleAvoidanceProgress: Float,
    onAvoidanceObstaclesChanged: (List<Rect>) -> Unit,
    infoFocusRequester: FocusRequester,
    presentation: RecordedProgramPresentation,
    subtitleCommentLayer: String,
    isBuffering: Boolean,
    onLCropClose: () -> Unit,
    cmSkipMode: CmSkipMode,
    isDataBroadcastingActive: Boolean,
    showNextEpisodeCountdown: Boolean,
    nextCountdownProgram: RecordedProgram?,
    nextEpisodeCountdownProgress: Float,
    onPlayNextEpisodeNow: () -> Unit,
    onCancelNextEpisodeCountdown: () -> Unit,
    program: RecordedProgram,
    mediaInfo: PlaybackMediaInfo,
    timeFormat: String,
    tiledThumbnailUrl: String?,
    showControls: Boolean,
    isSubOverlayOpen: Boolean,
    isSeekingPreviewVisible: Boolean,
    isModern: Boolean,
    isPlaying: Boolean,
    chapters: List<ChapterInfo>,
    totalDurationMs: Long,
    initialControlsPositionMs: Long,
    initialBufferedPositionMs: Long,
    controlsPositionMs: () -> Long,
    controlsBufferedPositionMs: () -> Long,
    playerControlsFocusRequester: FocusRequester,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekRequested: (Long) -> Unit,
    onSkipPreviousChapter: () -> Unit,
    onSkipNextChapter: () -> Unit,
    canOpenKeyframeGrid: Boolean,
    onOpenKeyframeGrid: () -> Unit,
    onOpenChapterList: () -> Unit,
    onOpenProgramInfo: () -> Unit,
    onOpenSettings: () -> Unit,
    isProgramInfoOpen: Boolean,
    onCloseProgramInfo: () -> Unit,
    isSceneSearchOpen: Boolean,
    onCloseSceneSearch: () -> Unit,
    isChapterListOpen: Boolean,
    onCloseChapterList: () -> Unit,
    isKeyframeGridOpen: Boolean,
    onCloseKeyframeGrid: () -> Unit,
) {
    val showManualCmSkipPrompt = manualCmSkipTargetMs(
        mode = cmSkipMode,
        currentPositionMs = currentPositionMs(),
        chapters = chapters,
        interactionBlocked = showControls ||
            isSubOverlayOpen ||
            isDataBroadcastingActive ||
            showNextEpisodeCountdown ||
            state.crop.mode != PlayerCropMode.HIDDEN
    ) != null
    val commentLayer = @Composable {
        if (isHeavyUiReady && state.isCommentEnabled) {
            ArchivedCommentOverlay(
                modifier = Modifier.fillMaxSize(),
                comments = comments,
                currentPositionProvider = commentPositionMs,
                isPlaying = state.isPlayerPlaying,
                isCommentEnabled = state.isCommentEnabled,
                commentSpeed = commentSpeed,
                commentFontSizeScale = commentFontSizeScale,
                commentOpacity = commentOpacity,
                commentMaxLines = commentMaxLines,
                useSoftwareRendering = isEmulator,
                recordedPlaybackFence = recordedPlaybackFence
            )
        }
    }
    val subtitleLayer = @Composable {
        if (isHeavyUiReady) {
            NativeCaptionOverlay(
                cue = subtitleCue,
                visible = state.isSubtitleEnabled && !isSubtitleBlockingOverlayOpen,
                modifier = Modifier.fillMaxSize(),
                avoidanceObstacles = subtitleAvoidanceObstacles,
                avoidanceProgress = subtitleAvoidanceProgress,
            )
        }
    }

    if (subtitleCommentLayer == "CommentOnTop") {
        subtitleLayer()
        commentLayer()
    } else {
        commentLayer()
        subtitleLayer()
    }
    if (isHeavyUiReady) {
        NativeCaptionOverlay(
            cue = superimposeCue,
            visible = !isSubtitleBlockingOverlayOpen,
            modifier = Modifier.fillMaxSize()
        )
    }
    if (isBuffering) {
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center),
            color = Color.White
        )
    }

    AnimatedVisibility(
        visible = state.crop.mode != PlayerCropMode.HIDDEN,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        VideoLCropOverlay(state = state, onClose = onLCropClose)
    }

    AnimatedVisibility(
        visible = showManualCmSkipPrompt,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd)
    ) {
        ManualCmSkipPrompt()
    }

    if (nextCountdownProgram != null) {
        AnimatedVisibility(
            visible = showNextEpisodeCountdown && !isSubOverlayOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            NextEpisodeCountdownOverlay(
                program = nextCountdownProgram,
                progress = nextEpisodeCountdownProgress,
                onPlayNow = onPlayNextEpisodeNow,
                onCancel = onCancelNextEpisodeCountdown
            )
        }
    }

    if (showControls && !isSubOverlayOpen && state.crop.mode == PlayerCropMode.HIDDEN) {
        RecordedProgramStatus(program, timeFormat, presentation)
        RecordedWallClock(
            timeFormat, Modifier.align(Alignment.TopStart).padding(24.dp),
            positionMs = controlsPositionMs, durationMs = totalDurationMs,
            speed = state.currentSpeed,
        )
    }

    PlayerControls(
        mediaInfo = mediaInfo,
        timeFormat = timeFormat,
        onAvoidanceObstaclesChanged = onAvoidanceObstaclesChanged,
        infoFocusRequester = infoFocusRequester,
        tiledThumbnailUrl = tiledThumbnailUrl,
        allComments = comments,
        isVisible = showControls && !isSubOverlayOpen && state.crop.mode == PlayerCropMode.HIDDEN,
        isSeekingPreviewVisible = isSeekingPreviewVisible,
        isModernUi = isModern,
        isPlaying = isPlaying,
        hasChapters = chapters.isNotEmpty(),
        externalChapters = chapters,
        initialPositionMs = initialControlsPositionMs,
        totalDurationMs = totalDurationMs,
        initialBufferedPositionMs = initialBufferedPositionMs,
        displayPositionMsProvider = controlsPositionMs,
        displayBufferedPositionMsProvider = controlsBufferedPositionMs,
        controlsFocusRequester = playerControlsFocusRequester,
        onSeekBarFocusChanged = onSeekBarFocusChanged,
        onPlayPauseToggle = onPlayPauseToggle,
        onSeekBack = onSeekBack,
        onSeekForward = onSeekForward,
        onSeekRequested = onSeekRequested,
        onSkipPreviousChapter = onSkipPreviousChapter,
        onSkipNextChapter = onSkipNextChapter,
        canOpenKeyframeGrid = canOpenKeyframeGrid,
        onKeyframeGridToggle = onOpenKeyframeGrid,
        onChapterListToggle = onOpenChapterList,
        onInfoToggle = onOpenProgramInfo,
        onSettingsToggle = onOpenSettings
    )

    AnimatedVisibility(visible = isProgramInfoOpen, enter = fadeIn(), exit = fadeOut()) {
        ProgramInfoOverlay(
            program = program,
            timeFormat = timeFormat,
            presentation = presentation,
            onClose = onCloseProgramInfo
        )
    }
    AnimatedVisibility(
        visible = isSceneSearchOpen,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        SceneSearchOverlay(
            program = program,
            tiledThumbnailUrl = tiledThumbnailUrl,
            currentPositionMs = currentPositionMs(),
            onSeekRequested = {
                onSeekRequested(it)
                onCloseSceneSearch()
            },
            onClose = onCloseSceneSearch
        )
    }
    AnimatedVisibility(
        visible = isChapterListOpen,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        ChapterListOverlay(
            program = program,
            chapters = chapters,
            tiledThumbnailUrl = tiledThumbnailUrl,
            currentPositionMs = currentPositionMs(),
            onSeekRequested = {
                onSeekRequested(it)
                onCloseChapterList()
            },
            onClose = onCloseChapterList
        )
    }
    AnimatedVisibility(
        visible = isKeyframeGridOpen,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        KeyframeGridOverlay(
            program = program,
            tiledThumbnailUrl = tiledThumbnailUrl,
            currentPositionMs = currentPositionMs(),
            onSeekRequested = {
                onSeekRequested(it)
                onCloseKeyframeGrid()
            },
            onClose = onCloseKeyframeGrid
        )
    }
}

@Composable
internal fun RecordedDataBroadcastingColorSelector(
    visible: Boolean,
    selectedKey: DataBroadcastingColorKey,
    onColorSelected: (DataBroadcastingColorKey) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(10f)
    ) {
        DataBroadcastingColorSelectorOverlay(
            selectedKey = selectedKey,
            onColorSelected = onColorSelected,
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun RecordedPlayerMenus(
    isModernSettingsOpen: Boolean,
    program: RecordedProgram,
    seriesPrograms: List<RecordedProgram>,
    quickPrograms: List<RecordedProgram>,
    animeChannels: List<Channel>,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    state: VideoPlayerState,
    subtitleLanguages: List<NativeCaptionLanguage>,
    currentSubtitleLanguageId: Int,
    cmSkipMode: CmSkipMode,
    hdrRenderMode: String,
    isHdrRenderModeSupported: Boolean,
    isDataBroadcastingAvailable: Boolean,
    isDataBroadcastingActive: Boolean,
    availableQualities: List<StreamQuality>,
    subMenuFocusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onProgramInfo: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onSubtitleLanguageToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onCmSkipModeToggle: () -> Unit,
    onHdrRenderModeToggle: () -> Unit,
    onDataBroadcastingToggle: () -> Unit,
    onVideoSelect: (RecordedProgram) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    canOpenKeyframeGrid: Boolean,
    onOpenKeyframeGrid: () -> Unit,
    openQuickVideosInitially: Boolean,
    isSubMenuOpen: Boolean,
    onCloseModernSettings: () -> Unit,
    onCloseSubMenu: () -> Unit,
    isModern: Boolean,
) {
    AnimatedVisibility(
        visible = isModernSettingsOpen,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        ModernVideoSettingsOverlay(
            currentAudioMode = state.currentAudioMode,
            currentSpeed = state.currentSpeed,
            isSubtitleEnabled = state.isSubtitleEnabled,
            currentQuality = state.currentQuality,
            isCommentEnabled = state.isCommentEnabled,
            isLCropEnabled = state.crop.isEnabled,
            cmSkipMode = cmSkipMode,
            availableQualities = availableQualities,
            onAudioToggle = onAudioToggle,
            onSpeedToggle = onSpeedToggle,
            onSubtitleToggle = onSubtitleToggle,
            onQualitySelect = onQualitySelect,
            onCommentToggle = onCommentToggle,
            onLCropToggle = onLCropToggle,
            onCmSkipModeToggle = onCmSkipModeToggle,
            onClose = onCloseModernSettings
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VideoTopSubMenuUI(
            mediaInfo = PlaybackMediaInfo.recorded(program),
            currentProgram = program,
            seriesPrograms = seriesPrograms,
            quickPrograms = quickPrograms,
            animeChannels = animeChannels,
            backendType = backendType,
            konomiIp = konomiIp,
            konomiPort = konomiPort,
            currentAudioMode = state.currentAudioMode,
            currentSpeed = state.currentSpeed,
            isSubtitleEnabled = state.isSubtitleEnabled,
            subtitleLanguages = subtitleLanguages,
            currentSubtitleLanguageId = currentSubtitleLanguageId,
            currentQuality = state.currentQuality,
            isCommentEnabled = state.isCommentEnabled,
            isLCropEnabled = state.crop.isEnabled,
            cmSkipMode = cmSkipMode,
            hdrRenderMode = hdrRenderMode,
            isHdrRenderModeSupported = isHdrRenderModeSupported,
            isDataBroadcastingAvailable = isDataBroadcastingAvailable,
            isDataBroadcastingActive = isDataBroadcastingActive,
            availableQualities = availableQualities,
            focusRequester = subMenuFocusRequester,
            onAudioToggle = onAudioToggle,
            onSpeedToggle = onSpeedToggle,
            onProgramInfo = onProgramInfo,
            onSubtitleToggle = onSubtitleToggle,
            onSubtitleLanguageToggle = onSubtitleLanguageToggle,
            onQualitySelect = onQualitySelect,
            onCommentToggle = onCommentToggle,
            onLCropToggle = onLCropToggle,
            onCmSkipModeToggle = onCmSkipModeToggle,
            onHdrRenderModeToggle = onHdrRenderModeToggle,
            onDataBroadcastingToggle = onDataBroadcastingToggle,
            onVideoSelect = onVideoSelect,
            onChannelSelect = onChannelSelect,
            canOpenKeyframeGrid = canOpenKeyframeGrid,
            onKeyframeGridToggle = onOpenKeyframeGrid,
            openQuickVideosInitially = openQuickVideosInitially,
            isVisible = isSubMenuOpen,
            onCloseMenu = onCloseSubMenu
        )
    }

    if (!isModern) PlaybackIndicator(state.indicatorState)
}

internal fun toggleRecordedAudio(
    state: VideoPlayerState,
    onShowToast: (String) -> Unit
) {
    state.currentAudioMode = if (state.currentAudioMode == AudioMode.MAIN) {
        AudioMode.SUB
    } else {
        AudioMode.MAIN
    }
    onShowToast(
        "音声: ${if (state.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}"
    )
}

internal fun cycleRecordedPlaybackSpeed(
    state: VideoPlayerState,
    player: ExoPlayer,
    onShowToast: (String) -> Unit
) {
    val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f)
    state.currentSpeed = speeds[(speeds.indexOf(state.currentSpeed) + 1) % speeds.size]
    player.setPlaybackSpeed(state.currentSpeed)
    onShowToast("速度: ${state.currentSpeed}x")
}

internal fun toggleRecordedSubtitle(
    state: VideoPlayerState,
    onShowToast: (String) -> Unit
) {
    state.isSubtitleEnabled = !state.isSubtitleEnabled
    onShowToast("字幕: ${if (state.isSubtitleEnabled) "表示" else "非表示"}")
}

internal fun toggleRecordedComment(
    state: VideoPlayerState,
    onShowToast: (String) -> Unit
) {
    state.isCommentEnabled = !state.isCommentEnabled
    onShowToast("実況: ${if (state.isCommentEnabled) "表示" else "非表示"}")
}

internal fun toggleRecordedLCrop(
    state: VideoPlayerState,
    onEnterAdjustment: () -> Unit
) {
    state.crop.isEnabled = !state.crop.isEnabled
    if (state.crop.isEnabled) {
        state.crop.mode = PlayerCropMode.MENU
        onEnterAdjustment()
    } else {
        state.crop.mode = PlayerCropMode.HIDDEN
        state.crop.zoomPercent = 100f
        state.crop.xPercent = 0f
        state.crop.yPercent = 0f
        state.crop.origin = PlayerZoomOrigin.TopRight
    }
}

internal fun closeRecordedLCrop(state: VideoPlayerState) {
    state.crop.mode = PlayerCropMode.HIDDEN
    if (!state.crop.isEnabled) {
        state.crop.zoomPercent = 100f
        state.crop.xPercent = 0f
        state.crop.yPercent = 0f
        state.crop.origin = PlayerZoomOrigin.TopRight
    }
}

internal fun recordedDataBroadcastingRemoteKey(colorKey: DataBroadcastingColorKey): String =
    when (colorKey) {
        DataBroadcastingColorKey.Blue -> "blue"
        DataBroadcastingColorKey.Red -> "red"
        DataBroadcastingColorKey.Green -> "green"
        DataBroadcastingColorKey.Yellow -> "yellow"
    }

internal fun recordedCountdownProgress(
    startMs: Long,
    endMs: Long,
    remainingMs: Long
): Float = if (endMs <= startMs) {
    1f
} else {
    1f - remainingMs.toFloat() / (endMs - startMs).toFloat()
}.coerceIn(0f, 1f)

internal fun nextRecordedSubtitleLanguage(
    currentLanguageId: Int,
    languages: List<NativeCaptionLanguage>
): Pair<Int, String> {
    val nextLanguageId = if (currentLanguageId == 1) 2 else 1
    val displayName = languages.firstOrNull { it.id == nextLanguageId }?.displayName
    return nextLanguageId to (
        "字幕言語: 第${nextLanguageId}言語" + (displayName?.let { "・$it" } ?: "")
    )
}

internal fun nextRecordedHdrRenderMode(currentMode: String): String =
    if (currentMode == HdrToneMapping.RENDER_MODE_SDR) {
        HdrToneMapping.RENDER_MODE_ORIGINAL
    } else {
        HdrToneMapping.RENDER_MODE_SDR
    }

internal fun recordedHdrRenderModeMessage(mode: String): String =
    if (mode == HdrToneMapping.RENDER_MODE_SDR) {
        "HDR 表示：ハードウェア SDR 変換を確認中"
    } else {
        "HDR 表示：HLG そのまま"
    }

internal fun handleRecordedPlayerKeyEvent(
    keyEvent: KeyEvent,
    isPiPMode: Boolean,
    isDataBroadcastingAvailable: Boolean,
    isDataBroadcastingActive: Boolean,
    isDataBroadcastingBlank: Boolean,
    dataBroadcastingInput: DataBroadcastingInputState,
    scope: CoroutineScope,
    dispatchDataBroadcastingRemoteKey: (String) -> Unit,
    dispatchDataBroadcastingColorKey: (DataBroadcastingColorKey) -> Unit,
    openDataBroadcasting: () -> Unit,
    onShowToast: (String) -> Unit,
    isProgramInfoOpen: Boolean,
    openProgramInfo: () -> Unit,
    closeProgramInfo: () -> Unit,
    isSubOverlayOpen: Boolean,
    state: VideoPlayerState,
    cmSkipMode: CmSkipMode,
    currentPositionMs: () -> Long,
    chapters: List<ChapterInfo>,
    showControls: Boolean,
    showNextEpisodeCountdown: Boolean,
    armedManualCmSkipTargetMs: Long?,
    onArmedManualCmSkipTargetChange: (Long?) -> Unit,
    performSeek: (Long) -> Unit,
    isModern: Boolean,
    canOpenSceneSearch: Boolean,
    totalDurationMs: Long,
    triggerSeekingPreview: () -> Unit,
    onShowControlsChange: (Boolean) -> Unit,
    onPiPRequested: () -> Unit,
    onBackPressed: () -> Unit,
    onSceneSearchToggle: (Boolean) -> Unit,
    onSettingsMenuToggle: () -> Unit,
    onChapterListToggle: (Boolean) -> Unit,
    onSubMenuToggle: (Boolean) -> Unit,
    onQuickMenuRequested: () -> Unit,
    exoPlayerIsPlaying: Boolean,
    onPause: () -> Unit,
    onPlay: () -> Unit,
    onSkipPreviousChapter: () -> Unit,
    onSkipNextChapter: () -> Unit,
): Boolean {
    if (isPiPMode || isSubOverlayOpen || isProgramInfoOpen) state.resetMediaKeys()
    if (isPiPMode) return false
    if (isDataBroadcastingToggleKeyEvent(keyEvent)) {
        dataBroadcastingInput.reset()
        when {
            !isDataBroadcastingAvailable -> {
                onShowToast("録画データ放送は Raw MMT/TLV の BS4K/BS8K で利用できます")
            }
            !isDataBroadcastingActive -> openDataBroadcasting()
            else -> dispatchDataBroadcastingRemoteKey("data")
        }
        return true
    }

    if (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_INFO) {
        if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) {
            if (isProgramInfoOpen) closeProgramInfo() else openProgramInfo()
        }
        return true
    }
    if (isSubOverlayOpen) return false

    if (
        isDataBroadcastingActive &&
        !isDataBroadcastingBlank &&
        dataBroadcastingInput.handleKeyEvent(
            keyEvent = keyEvent,
            scope = scope,
            onBack = { dispatchDataBroadcastingRemoteKey("back") },
            onBlank = { dispatchDataBroadcastingRemoteKey("data") },
            onColorKey = dispatchDataBroadcastingColorKey,
            onRemoteKey = dispatchDataBroadcastingRemoteKey,
        )
    ) {
        return true
    }

    if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
        state.lastInteractionTime = System.currentTimeMillis()
    }
    val manualSkipTargetMs = manualCmSkipTargetMs(
        mode = cmSkipMode,
        currentPositionMs = currentPositionMs(),
        chapters = chapters,
        interactionBlocked = showControls ||
            isSubOverlayOpen ||
            isDataBroadcastingActive ||
            showNextEpisodeCountdown ||
            state.crop.mode != PlayerCropMode.HIDDEN
    )
    val isConfirmKey = keyEvent.nativeKeyEvent.keyCode in listOf(
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_ENTER
    )
    if (
        isConfirmKey &&
        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
        manualSkipTargetMs != null
    ) {
        onArmedManualCmSkipTargetChange(manualSkipTargetMs)
        return true
    }
    if (
        isConfirmKey &&
        keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
        armedManualCmSkipTargetMs != null
    ) {
        onArmedManualCmSkipTargetChange(null)
        performSeek(armedManualCmSkipTargetMs)
        onShowToast("CMをスキップしました")
        return true
    }

    return state.handleKeyEvent(
        keyEvent = keyEvent,
        isPiPMode = isPiPMode,
        isModern = isModern,
        showControls = showControls,
        isSubOverlayOpen = isSubOverlayOpen,
        chapters = chapters,
        canOpenSceneSearch = canOpenSceneSearch,
        totalDurationMs = totalDurationMs,
        getCurrentPositionMs = currentPositionMs,
        performSeek = performSeek,
        triggerSeekingPreview = triggerSeekingPreview,
        onShowControlsChange = onShowControlsChange,
        onPiPRequested = onPiPRequested,
        onBackPressed = onBackPressed,
        onSceneSearchToggle = onSceneSearchToggle,
        onSettingsMenuToggle = onSettingsMenuToggle,
        onChapterListToggle = onChapterListToggle,
        onSubMenuToggle = onSubMenuToggle,
        onQuickMenuRequested = onQuickMenuRequested,
        exoPlayerIsPlaying = exoPlayerIsPlaying,
        onPause = onPause,
        onPlay = onPlay,
        onSkipPreviousChapter = onSkipPreviousChapter,
        onSkipNextChapter = onSkipNextChapter
    )
}

internal fun buildRecordedSeriesQuickPrograms(
    currentProgram: RecordedProgram,
    recentRecordings: List<RecordedProgram>
): List<RecordedProgram> {
    val seriesName = currentProgram.seriesName?.trim().orEmpty()
    val displayTitle = seriesName.ifBlank {
        TitleNormalizer.extractDisplayTitle(currentProgram.title)
    }
    val normalizedSeries = normalizeQuickSeriesKey(displayTitle)
    return (listOf(currentProgram) + recentRecordings)
        .distinctBy { it.id }
        .filter { candidate ->
            val candidateSeries = candidate.seriesName?.trim().orEmpty()
            val candidateDisplay = candidateSeries.ifBlank {
                TitleNormalizer.extractDisplayTitle(candidate.title)
            }
            normalizeQuickSeriesKey(candidateDisplay) == normalizedSeries ||
                (displayTitle.isNotBlank() && candidate.title.contains(displayTitle))
        }
        .sortedByDescending { it.startTime }
        .take(24)
}

internal fun buildRecordedRecentQuickPrograms(
    currentProgram: RecordedProgram,
    recentRecordings: List<RecordedProgram>
): List<RecordedProgram> =
    (listOf(currentProgram) + recentRecordings)
        .distinctBy { it.id }
        .sortedByDescending { it.startTime }
        .take(24)

internal fun chooseRecordedQuickPrograms(
    currentProgramId: Int,
    fetchedSourceProgramId: Int?,
    fetchedPrograms: List<RecordedProgram>,
    fallbackPrograms: List<RecordedProgram>
): List<RecordedProgram> =
    if (fetchedSourceProgramId == currentProgramId && fetchedPrograms.isNotEmpty()) {
        fetchedPrograms
    } else {
        fallbackPrograms
    }

internal fun nextRecordedSeriesProgram(
    currentProgramId: Int,
    seriesPrograms: List<RecordedProgram>
): RecordedProgram? {
    val newestFirst = seriesPrograms.distinctBy { it.id }.sortedByDescending { it.startTime }
    val currentIndex = newestFirst.indexOfFirst { it.id == currentProgramId }
    return if (currentIndex > 0) newestFirst[currentIndex - 1] else null
}

internal fun previousRecordedSeriesProgram(
    currentProgramId: Int,
    seriesPrograms: List<RecordedProgram>
): RecordedProgram? {
    val newestFirst = seriesPrograms.distinctBy { it.id }.sortedByDescending { it.startTime }
    val currentIndex = newestFirst.indexOfFirst { it.id == currentProgramId }
    return if (currentIndex >= 0 && currentIndex + 1 < newestFirst.size) {
        newestFirst[currentIndex + 1]
    } else {
        null
    }
}

@Composable
internal fun RecordedSystemMediaSession(
    player: ExoPlayer,
    program: RecordedProgram,
    artworkUrl: String?,
    isLoading: Boolean,
    previousProgram: RecordedProgram?,
    nextProgram: RecordedProgram?,
    onProgramSelect: (RecordedProgram, RecordedProgramSelectionReason) -> Unit,
    currentPositionMs: () -> Long,
    performSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    SystemMediaSession(
        player = player,
        title = program.title,
        subtitle = program.channel?.name,
        artworkUrl = artworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_TV_SHOW,
        isLoading = isLoading,
        onPrevious = previousProgram?.let { target ->
            { onProgramSelect(target, RecordedProgramSelectionReason.PreviousEpisode) }
        },
        onNext = nextProgram?.let { target ->
            { onProgramSelect(target, RecordedProgramSelectionReason.NextEpisode) }
        },
        onSeekRelative = { deltaMilliseconds ->
            performSeek(currentPositionMs() + deltaMilliseconds)
        },
        onStop = onStop
    )
}
