@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.common.Player
import com.beeregg2001.komorebi.media.SystemMediaSession
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
    var subtitleAvoidanceObstacles by remember { mutableStateOf(emptyList<Rect>()) }
    val subtitleAvoidanceProgress by animateFloatAsState(
        targetValue = if (
            showControls && !isSubMenuOpen && !isProgramInfoOpen &&
                !state.crop.isEnabled && subtitleAvoidanceObstacles.isNotEmpty()
        ) 1f else 0f,
        label = "smbControlsSubtitleAvoidance",
    )
    val subMenuFocusRequester = remember { FocusRequester() }
    val controlsFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showControls, isSubMenuOpen) {
        if (isSubMenuOpen) subMenuFocusRequester.requestFocus()
        else if (showControls) controlsFocusRequester.requestFocus()
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
        onBufferingChanged = { buffering = it },
        onDurationChanged = { durationMs = it.coerceAtLeast(0L) },
        onPlaybackEnded = { onShowControlsChange(true) },
        onStopOrDispose = {},
        settingsViewModel = settingsViewModel,
    )
    LaunchedEffect(player, metadata.stableId, initialPositionMs) {
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
    BackHandler(enabled = isPiPMode) {}
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                state.handleKeyEvent(
                    keyEvent = event,
                    isPiPMode = isPiPMode,
                    isModern = false,
                    showControls = showControls,
                    isSubOverlayOpen = isSubMenuOpen || isProgramInfoOpen || state.crop.mode != com.beeregg2001.komorebi.ui.player.PlayerCropMode.HIDDEN,
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
                    onSettingsMenuToggle = {},
                    onChapterListToggle = {},
                    onSubMenuToggle = onSubMenuToggle,
                    exoPlayerIsPlaying = player.isPlaying,
                    onPause = player::pause,
                    onPlay = player::play,
                    onSkipPreviousChapter = {},
                    onSkipNextChapter = {},
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
        PlayerControls(
            mediaInfo = PlaybackMediaInfo.smb(metadata),
            capabilities = PlaybackUiCapabilities.Smb,
            timeFormat = "24h",
            allComments = emptyList(),
            tiledThumbnailUrl = metadata.thumbnailUrl,
            isVisible = showControls,
            isSeekingPreviewVisible = false,
            isModernUi = false,
            isPlaying = state.isPlayerPlaying,
            hasChapters = false,
            initialPositionMs = positionMs,
            totalDurationMs = durationMs,
            initialBufferedPositionMs = player.bufferedPosition.coerceAtLeast(positionMs),
            displayPositionMsProvider = { currentPositionMs(player, positionMs) },
            displayBufferedPositionMsProvider = { player.bufferedPosition.coerceAtLeast(currentPositionMs(player, positionMs)) },
            controlsFocusRequester = controlsFocusRequester,
            onSeekBarFocusChanged = {},
            onPlayPauseToggle = { if (player.isPlaying) player.pause() else player.play() },
            onSeekBack = { captions.seek(); player.seekTo((currentPositionMs(player, positionMs) - 10_000L).coerceAtLeast(0L)) },
            onSeekForward = { captions.seek(); player.seekTo(smbSeekTarget(currentPositionMs(player, positionMs) + 30_000L, durationMs)) },
            onSeekRequested = { captions.seek(); player.seekTo(smbSeekTarget(it, durationMs)) },
            onChapterListToggle = {},
            onInfoToggle = { isProgramInfoOpen = true; onSubMenuToggle(false) },
            onSettingsToggle = { onSubMenuToggle(true) },
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
            onSpeedToggle = { cycleRecordedPlaybackSpeed(state, player, onShowToast) },
            onProgramInfo = {
                isProgramInfoOpen = true
                onSubMenuToggle(false)
            },
            onSubtitleToggle = { toggleRecordedSubtitle(state, onShowToast) },
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
    }
}

private fun currentPositionMs(player: Player, fallback: Long): Long =
    player.currentPosition.takeUnless { it == C.TIME_UNSET || it < 0L } ?: fallback

private fun smbSeekTarget(targetMs: Long, durationMs: Long): Long =
    if (durationMs > 0L) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)
