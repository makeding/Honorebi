@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.subtitle.rememberNativeCaptionCue
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "VideoPlayerScreen"
private const val PLAYBACK_END_FALLBACK_WINDOW_MS = 10_000L
private const val PLAYBACK_END_FALLBACK_GRACE_MS = 750L
private const val NEXT_EPISODE_COUNTDOWN_WINDOW_MS = 15_000L

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    smbItem: SmbItem? = null,
    initialPositionMs: Long = 0,
    initialQuality: String = "1080p-60fps",
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    isSceneSearchOpen: Boolean,
    onSceneSearchToggle: (Boolean) -> Unit,
    recentRecordings: List<RecordedProgram> = emptyList(),
    animeChannels: List<Channel> = emptyList(),
    onProgramSelect: (RecordedProgram) -> Unit = {},
    onChannelSelect: (Channel) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()

    var currentProgram by remember { mutableStateOf(program) }
    val fetchedDetail by videoPlayerViewModel.programDetail.collectAsState()

    val tiledThumbnailUrl by videoPlayerViewModel.tiledThumbnailUrl.collectAsState()
    val chapters by videoPlayerViewModel.chapters.collectAsState()
    val isLiveStream by videoPlayerViewModel.isLiveStream.collectAsState()

    val availableQualities by videoPlayerViewModel.availableQualities.collectAsState()
    val isQualitiesLoaded by videoPlayerViewModel.isQualitiesLoaded.collectAsState()
    val quickVideoCandidates by videoPlayerViewModel.quickVideoCandidates.collectAsState()
    val currentVideoQualityStr by settingsViewModel.videoQuality.collectAsState()

    val isModern = false
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(program.id) {
        currentProgram = program
        if (smbItem == null) {
            videoPlayerViewModel.fetchProgramDetail(program.id)
            videoPlayerViewModel.fetchAvailableQualities()
        }
    }

    LaunchedEffect(fetchedDetail) {
        if (fetchedDetail != null && fetchedDetail?.id == program.id) {
            currentProgram = fetchedDetail!!
        }
    }

    val isRecordingChasePlayback =
        currentProgram.isRecording || currentProgram.recordedVideo.status == "Recording"
    val effectiveInitialPositionMs = remember(
        currentProgram.id,
        currentProgram.recordingStartMargin,
        isRecordingChasePlayback,
        initialPositionMs
    ) {
        if (isRecordingChasePlayback && initialPositionMs <= 0L) {
            ((currentProgram.recordingStartMargin + 2.0).coerceAtLeast(0.0) * 1000.0).toLong()
        } else {
            initialPositionMs
        }
    }
    var playbackPositionMs by remember(currentProgram.id) {
        mutableLongStateOf(effectiveInitialPositionMs.coerceAtLeast(0L))
    }
    var playbackDurationMs by remember(currentProgram.id) { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember(currentProgram.id) { mutableLongStateOf(0L) }

    val vs = rememberVideoPlayerState()

    val autoCmSkipStr by settingsViewModel.autoCmSkip.collectAsState()
    LaunchedEffect(autoCmSkipStr) {
        vs.isAutoCmSkipEnabled = (autoCmSkipStr == "ON")
    }

    LaunchedEffect(availableQualities, isQualitiesLoaded, currentVideoQualityStr) {
        if (isQualitiesLoaded && availableQualities.isNotEmpty()) {
            val matched = availableQualities.find { it.value == currentVideoQualityStr }
            if (matched != null) {
                vs.currentQuality = matched
            } else {
                val fallback = availableQualities.first()
                vs.currentQuality = fallback
                videoPlayerViewModel.saveVideoQuality(fallback.value)
            }
        }
    }

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val videoSubtitleDefaultStr by settingsViewModel.videoSubtitleDefault.collectAsState()

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    LaunchedEffect(commentDefaultDisplayStr) {
        vs.isCommentEnabled = commentDefaultDisplayStr == "ON"
    }
    LaunchedEffect(videoSubtitleDefaultStr) {
        vs.isSubtitleEnabled = videoSubtitleDefaultStr == "ON"
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val allComments = remember { mutableStateListOf<ArchivedComment>() }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    val currentSessionId = remember(currentProgram.id, vs.currentQuality.value, isRecordingChasePlayback) {
        UUID.randomUUID().toString()
    }
    val subtitleEvents = remember {
        MutableSharedFlow<NativeCaptionCue>(
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val subtitleCue = rememberNativeCaptionCue(
        events = subtitleEvents,
        enabled = vs.isSubtitleEnabled,
        clockRunning = vs.isPlayerPlaying
    )

    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val playerControlsFocusRequester = remember { FocusRequester() }

    var isProgramInfoOpen by remember { mutableStateOf(false) }
    var isModernSettingsOpen by remember { mutableStateOf(false) }

    var videoWidth by remember { mutableStateOf(0) }
    var videoHeight by remember { mutableStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableStateOf(1f) }

    var isChapterListOpen by remember { mutableStateOf(false) }
    var isKeyframeGridOpen by remember { mutableStateOf(false) }
    var isSeekingPreviewVisible by remember { mutableStateOf(false) }
    var seekingPreviewJob by remember { mutableStateOf<Job?>(null) }

    val isSubOverlayOpen =
        isSubMenuOpen || isSceneSearchOpen || isChapterListOpen || isKeyframeGridOpen || isProgramInfoOpen || isModernSettingsOpen

    val triggerSeekingPreview: () -> Unit = {
        isSeekingPreviewVisible = true
        seekingPreviewJob?.cancel()
        seekingPreviewJob = scope.launch { delay(2000); isSeekingPreviewVisible = false }
    }

    LaunchedEffect(currentProgram.recordedVideo.id) {
        if (smbItem == null) {
            allComments.clear()
            allComments.addAll(videoPlayerViewModel.getArchivedComments(currentProgram.recordedVideo.id))
        }
    }

    var smbDurationMs by remember { mutableLongStateOf(0L) }
    val isBackground = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasHandledPlaybackEnd by remember(currentProgram.id) { mutableStateOf(false) }
    var hasAutoStartedNextEpisode by remember(currentProgram.id) { mutableStateOf(false) }
    var isNextEpisodeCountdownCancelled by remember(currentProgram.id) { mutableStateOf(false) }
    var nextEpisodeProgramForEnd by remember(currentProgram.id) { mutableStateOf<RecordedProgram?>(null) }
    var openQuickVideosOnSubMenuOpen by remember(currentProgram.id) { mutableStateOf(false) }

    val handlePlaybackEnded: () -> Unit = {
        if (!hasHandledPlaybackEnd && smbItem == null) {
            hasHandledPlaybackEnd = true
            val nextEpisode = nextEpisodeProgramForEnd
            if (nextEpisode != null && !hasAutoStartedNextEpisode && !isNextEpisodeCountdownCancelled) {
                hasAutoStartedNextEpisode = true
                onProgramSelect(nextEpisode)
            } else {
                openQuickVideosOnSubMenuOpen = true
                onPlaybackEnded()
                onSubMenuToggle(true)
                onShowControlsChange(false)
            }
        }
    }

    val exoPlayer = rememberManagedExoPlayer(
        program = currentProgram,
        vs = vs,
        isLiveStream = isLiveStream,
        scope = scope,
        onSubtitleCue = { subtitleEvents.tryEmit(it) },
        onVideoSizeChanged = { w, h, ratio ->
            videoWidth = w
            videoHeight = h
            pixelWidthHeightRatio = ratio
        },
        onBufferingChanged = { isBuffering = it },
        onDurationChanged = { smbDurationMs = it },
        onPlaybackEnded = {
            handlePlaybackEnded()
        },
        onStopOrDispose = { player ->
            if (smbItem == null) {
                val posMs =
                    if (isLiveStream) vs.playbackOffsetMs + player.currentPosition else player.currentPosition
                videoPlayerViewModel.updateWatchHistory(program, posMs / 1000.0)
            }
        }
    )

    val getCurrentPositionMs: () -> Long = {
        val rawPosition = exoPlayer.currentPosition
        if (rawPosition == C.TIME_UNSET) {
            playbackPositionMs
        } else if (isLiveStream && !isRecordingChasePlayback) {
            vs.playbackOffsetMs + rawPosition
        } else {
            rawPosition
        }.coerceAtLeast(0L)
    }

    LaunchedEffect(exoPlayer, currentProgram.id, isLiveStream, isRecordingChasePlayback) {
        while (isActive) {
            val currentPosition = getCurrentPositionMs()
            if (vs.pendingSeekPositionMs == null) {
                playbackPositionMs = currentPosition
            }

            val rawBufferedPosition = exoPlayer.bufferedPosition
            bufferedPositionMs = if (rawBufferedPosition == C.TIME_UNSET) {
                playbackPositionMs
            } else if (isLiveStream && !isRecordingChasePlayback) {
                vs.playbackOffsetMs + rawBufferedPosition
            } else {
                rawBufferedPosition
            }.coerceAtLeast(playbackPositionMs)

            val rawDuration = exoPlayer.duration
            if (rawDuration != C.TIME_UNSET && rawDuration > 0L) {
                playbackDurationMs = if (isLiveStream && !isRecordingChasePlayback) {
                    vs.playbackOffsetMs + rawDuration
                } else {
                    rawDuration
                }.coerceAtLeast(playbackDurationMs)
            }

            delay(250L)
        }
    }

    val backendType by settingsViewModel.backendType.collectAsState()
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")

    val getEffectivePositionMs = { vs.pendingSeekPositionMs ?: getCurrentPositionMs() }

    val buildVideoMediaItem: (String) -> MediaItem = { url ->
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (url.contains("/api/streams/") || url.contains("/api/videos/") || url.contains("konomi.tv") || url.contains(
                "m3u8"
            )
        ) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            if (isRecordingChasePlayback) {
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build()
                )
            }
        }
        mediaItemBuilder.build()
    }

    val totalDurationForControls =
        if (smbItem != null) {
            smbDurationMs.coerceAtLeast(0L)
        } else {
            maxOf(
                (currentProgram.recordedVideo.duration * 1000).toLong(),
                playbackDurationMs,
                playbackPositionMs,
                bufferedPositionMs
            ).coerceAtLeast(0L)
        }

    LaunchedEffect(isSubMenuOpen, currentProgram.id) {
        if (!isSubMenuOpen) openQuickVideosOnSubMenuOpen = false
    }

    LaunchedEffect(currentProgram.id, smbItem, totalDurationForControls) {
        if (smbItem != null || totalDurationForControls <= PLAYBACK_END_FALLBACK_WINDOW_MS) {
            return@LaunchedEffect
        }
        while (isActive && !hasHandledPlaybackEnd) {
            val remainingMs = totalDurationForControls - getCurrentPositionMs()
            if (remainingMs in 0..PLAYBACK_END_FALLBACK_WINDOW_MS) {
                delay(remainingMs + PLAYBACK_END_FALLBACK_GRACE_MS)
                if (!hasHandledPlaybackEnd && getCurrentPositionMs() >= totalDurationForControls - 1_500L) {
                    handlePlaybackEnded()
                }
            }
            delay(500L)
        }
    }

    val canOpenSceneSearch =
        !isRecordingChasePlayback &&
                currentProgram.recordedVideo.hasKeyFrames != false &&
                !tiledThumbnailUrl.isNullOrBlank() &&
                totalDurationForControls > 0L

    val performSeek: (Long) -> Unit = { targetMs: Long ->
        val safeTarget = targetMs.coerceIn(
            0L,
            if (totalDurationForControls > 0) totalDurationForControls else Long.MAX_VALUE
        )
        // 一瞬だけpendingSeekに記録してUI表示をサクサク進める
        vs.pendingSeekPositionMs = safeTarget
        scope.launch {
            delay(800)
            if (vs.pendingSeekPositionMs == safeTarget) {
                vs.pendingSeekPositionMs = null
            }
        }

        if (isLiveStream && !isRecordingChasePlayback && smbItem == null) {
            scope.launch {
                isBuffering = true; exoPlayer.pause()
                vs.playbackOffsetMs = safeTarget
                val newOffsetSec = safeTarget / 1000.0
                val newUrl = videoPlayerViewModel.resolveStreamUrl(
                    currentProgram.id,
                    vs.currentQuality.value,
                    currentSessionId,
                    newOffsetSec,
                    isRecordingChasePlayback
                )
                if (newUrl.isNotEmpty()) {
                    exoPlayer.setMediaItem(buildVideoMediaItem(newUrl))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    if (fetchedDetail != null) onShowToast("シーク先ストリームの取得に失敗しました")
                }
            }
        } else {
            exoPlayer.seekTo(safeTarget)
        }
        Unit
    }

    val skipToNextChapter = {
        val basePos = getEffectivePositionMs()
        val nextChapter = chapters.find { it.startTimeMs > basePos + 3000 }
        if (nextChapter != null) {
            performSeek(nextChapter.startTimeMs)
        } else {
            onShowToast("次のチャプターはありません")
        }
    }

    val skipToPreviousChapter = {
        val basePos = getEffectivePositionMs()
        val reversedChapters = chapters.sortedByDescending { it.startTimeMs }
        val prevChapter = reversedChapters.find { it.startTimeMs < basePos - 5000 }
        if (prevChapter != null) {
            performSeek(prevChapter.startTimeMs)
        } else {
            performSeek(0L)
        }
    }

    LaunchedEffect(vs.isAutoCmSkipEnabled, chapters) {
        var hasWarnedEmptyChapters = false
        while (isActive) {
            if (vs.isAutoCmSkipEnabled && exoPlayer.isPlaying) {
                if (chapters.isNotEmpty()) {
                    val currentPos = getCurrentPositionMs()
                    val cmChapter =
                        chapters.find { it.isCm && currentPos >= it.startTimeMs && currentPos < (it.endTimeMs - 1500) }
                    if (cmChapter != null) {
                        performSeek(cmChapter.endTimeMs)
                        onShowToast("自動CMスキップ: 本編へ移動しました")
                        delay(3000)
                    }
                } else {
                    if (!hasWarnedEmptyChapters) {
                        hasWarnedEmptyChapters = true
                    }
                }
            } else {
                hasWarnedEmptyChapters = false
            }
            delay(500)
        }
    }

    var isFirstLoad by remember { mutableStateOf(true) }
    var preparedPlaybackKey by remember { mutableStateOf<String?>(null) }
    val qualityOptionsKey = remember(availableQualities) {
        availableQualities.joinToString(separator = "|") { it.value }
    }

    LaunchedEffect(currentProgram.id, smbItem?.path) {
        isFirstLoad = true
        preparedPlaybackKey = null
        vs.playbackOffsetMs = 0L
        vs.pendingSeekPositionMs = null
        playbackPositionMs = effectiveInitialPositionMs.coerceAtLeast(0L)
        playbackDurationMs = 0L
        bufferedPositionMs = 0L
    }

    LaunchedEffect(
        currentProgram.id,
        smbItem?.path,
        vs.currentQuality.value,
        qualityOptionsKey,
        isQualitiesLoaded,
        isRecordingChasePlayback
    ) {
        val playbackKey = if (smbItem != null) {
            "smb:${smbItem.path}"
        } else {
            "video:${currentProgram.id}:${vs.currentQuality.value}:$isRecordingChasePlayback"
        }
        if (preparedPlaybackKey == playbackKey && exoPlayer.mediaItemCount > 0) {
            return@LaunchedEffect
        }

        if (smbItem != null) {
            isBuffering = true
            vs.playbackOffsetMs = 0L
            val mediaItem = MediaItem.fromUri(smbItem.path)
            exoPlayer.setMediaItem(mediaItem)
            if (isFirstLoad && effectiveInitialPositionMs > 0) {
                exoPlayer.seekTo(effectiveInitialPositionMs)
            }
            isFirstLoad = false
            exoPlayer.prepare()
            preparedPlaybackKey = playbackKey
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }

        if (currentProgram.id == 0 || !isQualitiesLoaded || vs.currentQuality.value.isBlank()) return@LaunchedEffect
        if (availableQualities.isNotEmpty() && availableQualities.none { it.value == vs.currentQuality.value }) return@LaunchedEffect

        isBuffering = true
        val offsetSec = if (isFirstLoad && effectiveInitialPositionMs > 0) {
            vs.playbackOffsetMs = effectiveInitialPositionMs; effectiveInitialPositionMs / 1000.0
        } else {
            val currentPos = getCurrentPositionMs()
            vs.playbackOffsetMs = currentPos; currentPos / 1000.0
        }

        val url = videoPlayerViewModel.resolveStreamUrl(
            currentProgram.id,
            vs.currentQuality.value,
            currentSessionId,
            offsetSec,
            isRecordingChasePlayback
        )

        if (url.isNotEmpty()) {
            exoPlayer.setMediaItem(buildVideoMediaItem(url))
            if (isFirstLoad && effectiveInitialPositionMs > 0 && (!isLiveStream || isRecordingChasePlayback)) {
                exoPlayer.seekTo(effectiveInitialPositionMs)
            }
            isFirstLoad = false
            exoPlayer.prepare()
            preparedPlaybackKey = playbackKey
            exoPlayer.playWhenReady = true
        } else {
            if (fetchedDetail != null) onShowToast("ストリームURLの取得に失敗しました")
        }
    }

    LaunchedEffect(isSceneSearchOpen, isChapterListOpen, isKeyframeGridOpen) {
        if (isSceneSearchOpen || isChapterListOpen || isKeyframeGridOpen) {
            vs.wasPlayingBeforeSceneSearch = exoPlayer.isPlaying
            if (vs.wasPlayingBeforeSceneSearch) exoPlayer.pause()
        } else if (vs.wasPlayingBeforeSceneSearch) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(vs.indicatorState) {
        if (vs.indicatorState != null) {
            delay(2000); vs.indicatorState = null
        }
    }

    DisposableEffect(vs.currentQuality.value, currentSessionId, smbItem) {
        if (smbItem == null) {
            videoPlayerViewModel.startStreamMaintenance(
                program,
                vs.currentQuality.value,
                currentSessionId
            ) {
                exoPlayer.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.toString()
            }
        }
        onDispose { if (smbItem == null) videoPlayerViewModel.stopStreamMaintenance() }
    }

    LaunchedEffect(
        showControls,
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        isProgramInfoOpen,
        isModernSettingsOpen,
        vs.lCropMode,
        vs.lastInteractionTime,
        vs.isSeekBarFocused
    ) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen && !isProgramInfoOpen && !isModernSettingsOpen && !vs.isSeekBarFocused && vs.lCropMode == LCropMode.HIDDEN) {
            delay(5000); onShowControlsChange(false)
        }
    }

    var wasControlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        isProgramInfoOpen,
        isModernSettingsOpen,
        showControls
    ) {
        if (isPiPMode) return@LaunchedEffect
        delay(150)

        if (isSubMenuOpen) {
            subMenuFocusRequester.safeRequestFocus(TAG)
        } else if (showControls && isModern && !isSubOverlayOpen) {
            if (!wasControlsVisible) {
                playerControlsFocusRequester.safeRequestFocus(TAG)
            }
        } else if (!showControls && vs.lCropMode == LCropMode.HIDDEN) {
            mainFocusRequester.safeRequestFocus(TAG)
        }

        wasControlsVisible = showControls
    }

    val safeHouseFocusRequester = remember { FocusRequester() }
    val sceneSearchFocusRequester = remember { FocusRequester() }
    var isLongPressHandled by remember { mutableStateOf(false) }
    val seriesQuickPrograms = remember(
        currentProgram.id,
        currentProgram.title,
        currentProgram.seriesName,
        recentRecordings
    ) {
        val seriesName = currentProgram.seriesName?.trim().orEmpty()
        val displayTitle = seriesName.ifBlank { TitleNormalizer.extractDisplayTitle(currentProgram.title) }
        val normalizedSeries = normalizeQuickSeriesKey(displayTitle)
        val candidates = (listOf(currentProgram) + recentRecordings)
            .distinctBy { it.id }
        candidates
            .filter { candidate ->
                val candidateSeries = candidate.seriesName?.trim().orEmpty()
                val candidateDisplay =
                    candidateSeries.ifBlank { TitleNormalizer.extractDisplayTitle(candidate.title) }
                normalizeQuickSeriesKey(candidateDisplay) == normalizedSeries ||
                        (displayTitle.isNotBlank() && candidate.title.contains(displayTitle))
            }
            .distinctBy { it.id }
            .sortedByDescending { it.startTime }
            .take(24)
    }
    val recentQuickPrograms = remember(currentProgram.id, recentRecordings) {
        (listOf(currentProgram) + recentRecordings)
            .distinctBy { it.id }
            .sortedByDescending { it.startTime }
            .take(24)
    }
    val quickMenuSeriesPrograms = remember(
        currentProgram.id,
        seriesQuickPrograms,
        quickVideoCandidates
    ) {
        if (
            quickVideoCandidates.sourceProgramId == currentProgram.id &&
            quickVideoCandidates.seriesPrograms.isNotEmpty()
        ) {
            quickVideoCandidates.seriesPrograms
        } else {
            seriesQuickPrograms
        }
    }
    val quickMenuRecentPrograms = remember(
        currentProgram.id,
        recentQuickPrograms,
        quickVideoCandidates
    ) {
        if (
            quickVideoCandidates.sourceProgramId == currentProgram.id &&
            quickVideoCandidates.recentPrograms.isNotEmpty()
        ) {
            quickVideoCandidates.recentPrograms
        } else {
            recentQuickPrograms
        }
    }
    val nextSeriesProgram = remember(currentProgram.id, quickMenuSeriesPrograms) {
        val newestFirst = quickMenuSeriesPrograms
            .distinctBy { it.id }
            .sortedByDescending { it.startTime }
        val currentIndex = newestFirst.indexOfFirst { it.id == currentProgram.id }
        if (currentIndex > 0) newestFirst[currentIndex - 1] else null
    }
    LaunchedEffect(nextSeriesProgram?.id) {
        nextEpisodeProgramForEnd = nextSeriesProgram
    }
    val refreshQuickMenuVideos: () -> Unit = {
        videoPlayerViewModel.refreshQuickVideoCandidates(currentProgram, recentRecordings)
    }
    LaunchedEffect(currentProgram.id, recentRecordings) {
        refreshQuickMenuVideos()
    }

    val nextEpisodeRemainingMs =
        (totalDurationForControls - getEffectivePositionMs()).coerceAtLeast(0L)
    val showNextEpisodeCountdown =
        nextSeriesProgram != null &&
                !isRecordingChasePlayback &&
                !hasAutoStartedNextEpisode &&
                !isNextEpisodeCountdownCancelled &&
                totalDurationForControls > NEXT_EPISODE_COUNTDOWN_WINDOW_MS &&
                nextEpisodeRemainingMs <= NEXT_EPISODE_COUNTDOWN_WINDOW_MS
    val nextEpisodeCountdownProgress =
        (1f - (nextEpisodeRemainingMs.toFloat() / NEXT_EPISODE_COUNTDOWN_WINDOW_MS.toFloat()))
            .coerceIn(0f, 1f)

    LaunchedEffect(showNextEpisodeCountdown, nextEpisodeRemainingMs, nextSeriesProgram?.id) {
        val nextEpisode = nextSeriesProgram
        if (
            showNextEpisodeCountdown &&
            nextEpisodeRemainingMs <= 500L &&
            !hasAutoStartedNextEpisode &&
            !isNextEpisodeCountdownCancelled
        ) {
            nextEpisode?.let {
                hasAutoStartedNextEpisode = true
                hasHandledPlaybackEnd = true
                onProgramSelect(it)
            }
        }
    }
    val playNextEpisodeNow: () -> Unit = {
        val nextEpisode = nextSeriesProgram
        if (nextEpisode != null && !hasAutoStartedNextEpisode) {
            hasAutoStartedNextEpisode = true
            hasHandledPlaybackEnd = true
            onProgramSelect(nextEpisode)
        }
    }
    val cancelNextEpisodeCountdown: () -> Unit = {
        isNextEpisodeCountdownCancelled = true
    }

    BackHandler(enabled = showNextEpisodeCountdown) {
        cancelNextEpisodeCountdown()
    }

    val openKeyframeGrid: () -> Unit = {
        onShowControlsChange(true)
        if (canOpenSceneSearch) {
            onSceneSearchToggle(false)
            onSubMenuToggle(false)
            isChapterListOpen = false
            isKeyframeGridOpen = true
        } else {
            onShowToast(
                if (isRecordingChasePlayback) {
                    "録画中のためサムネイルはまだ生成されていません"
                } else {
                    "サムネイルが生成されていません"
                }
            )
        }
    }

    LaunchedEffect(isSubMenuOpen, currentProgram.id, recentRecordings) {
        if (isSubMenuOpen) {
            refreshQuickMenuVideos()
        }
    }

    BackHandler(enabled = isPiPMode) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                // ★ UIのボタンにフォーカスがある場合に操作していてもUIが消えてしまう問題の修正
                // キー操作が行われるたびに最終インタラクション時間を更新し、非表示タイマーをリセットする
                if (keyEvent.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    vs.lastInteractionTime = System.currentTimeMillis()
                }

                vs.handleKeyEvent(
                    keyEvent = keyEvent,
                    isPiPMode = isPiPMode,
                    isModern = isModern,
                    showControls = showControls,
                    isSubOverlayOpen = isSubOverlayOpen,
                    chapters = chapters,
                    canOpenSceneSearch = canOpenSceneSearch,
                    totalDurationMs = totalDurationForControls,
                    getCurrentPositionMs = getCurrentPositionMs,
                    performSeek = performSeek,
                    triggerSeekingPreview = triggerSeekingPreview,
                    onShowControlsChange = onShowControlsChange,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                    onSceneSearchToggle = { onSceneSearchToggle(it) },
                    onSettingsMenuToggle = {
                        isModernSettingsOpen = true
                        onShowControlsChange(true)
                    },
                    onChapterListToggle = { isChapterListOpen = it },
                    onSubMenuToggle = onSubMenuToggle,
                    onQuickMenuRequested = refreshQuickMenuVideos,
                    exoPlayerIsPlaying = exoPlayer.playWhenReady,
                    onPause = { exoPlayer.pause() },
                    onPlay = { exoPlayer.play() }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    keepScreenOn = true
                    val surfaceView =
                        SurfaceView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
                    addView(surfaceView)
                }
            },
            update = { view ->
                val surfaceView = view.getChildAt(0) as SurfaceView
                exoPlayer.setVideoSurfaceView(surfaceView)
                if (videoWidth > 0 && videoHeight > 0) {
                    val ratio =
                        (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
                    view.setAspectRatio(ratio)
                    val targetMode =
                        if (ratio >= 1.7f) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (view.resizeMode != targetMode) view.resizeMode = targetMode
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (vs.lCropEnabled) {
                        scaleX = vs.lCropZoom / 100f; scaleY = vs.lCropZoom / 100f
                        translationX = size.width * (vs.lCropX / 100f); translationY =
                            size.height * (vs.lCropY / 100f)
                        transformOrigin = when (vs.lCropOrigin) {
                            ZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                            ZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                            ZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                            ZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                        }
                    } else {
                        scaleX = 1f; scaleY = 1f; translationX = 0f; translationY =
                            0f; transformOrigin = TransformOrigin.Center
                    }
                }
                .focusRequester(mainFocusRequester)
                .focusable(!isPiPMode && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN)
        )

        if (!isPiPMode) {
            val commentLayer = @Composable {
                if (isHeavyUiReady && vs.isCommentEnabled) {
                    ArchivedCommentOverlay(
                        Modifier.fillMaxSize(), allComments, { getCurrentPositionMs() },
                        vs.isPlayerPlaying, vs.isCommentEnabled, commentSpeed,
                        commentFontSizeScale, commentOpacity, commentMaxLines, isEmulator
                    )
                }
            }
            val subtitleLayer = @Composable {
                if (isHeavyUiReady) {
                    NativeCaptionOverlay(
                        cue = subtitleCue.value,
                        visible = vs.isSubtitleEnabled && !isSubOverlayOpen,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (subtitleCommentLayer == "CommentOnTop") {
                subtitleLayer(); commentLayer()
            } else {
                commentLayer(); subtitleLayer()
            }
            if (isBuffering) CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )

            val nextCountdownProgram = nextSeriesProgram
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
                        onPlayNow = playNextEpisodeNow,
                        onCancel = cancelNextEpisodeCountdown
                    )
                }
            }

            PlayerControls(
                program = currentProgram,
                tiledThumbnailUrl = tiledThumbnailUrl,
                allComments = allComments,
                isVisible = showControls && !isSubOverlayOpen && vs.lCropMode == LCropMode.HIDDEN,
                isSeekingPreviewVisible = isSeekingPreviewVisible,
                isModernUi = isModern,
                isPlaying = exoPlayer.playWhenReady,
                hasChapters = chapters.isNotEmpty(),
                externalChapters = chapters,
                currentPositionMs = getEffectivePositionMs(),
                totalDurationMs = totalDurationForControls,
                bufferedPositionMs = bufferedPositionMs,
                controlsFocusRequester = playerControlsFocusRequester,
                onSeekBarFocusChanged = { vs.isSeekBarFocused = it },
                onPlayPauseToggle = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    vs.togglePlayPause(exoPlayer.playWhenReady)
                    if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
                },
                onSeekBack = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos - 10_000).coerceAtLeast(0L))
                },
                onSeekForward = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    val basePos = getEffectivePositionMs()
                    performSeek((basePos + 30_000).coerceAtMost(totalDurationForControls))
                },
                onSeekRequested = { performSeek(it) }, // ★ 追加: シークバー操作によるシーク実行
                onSkipPreviousChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToPreviousChapter()
                },
                onSkipNextChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToNextChapter()
                },
                canOpenKeyframeGrid = canOpenSceneSearch,
                onKeyframeGridToggle = openKeyframeGrid,
                onChapterListToggle = { isChapterListOpen = true; onShowControlsChange(true) },
                onInfoToggle = { isProgramInfoOpen = true; onShowControlsChange(true) },
                onSettingsToggle = {
                    if (isModern) isModernSettingsOpen = true else onSubMenuToggle(
                        true
                    )
                }
            )

            AnimatedVisibility(visible = isProgramInfoOpen, enter = fadeIn(), exit = fadeOut()) {
                ProgramInfoOverlay(
                    program = currentProgram,
                    onClose = { isProgramInfoOpen = false })
            }

            AnimatedVisibility(
                isSceneSearchOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()) {
                SceneSearchOverlay(
                    program = currentProgram,
                    tiledThumbnailUrl = tiledThumbnailUrl,
                    currentPositionMs = getEffectivePositionMs(),
                    onSeekRequested = { performSeek(it); onSceneSearchToggle(false) },
                    onClose = { onSceneSearchToggle(false) })
            }

            AnimatedVisibility(
                isChapterListOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()) {
                ChapterListOverlay(
                    program = currentProgram,
                    chapters = chapters,
                    tiledThumbnailUrl = tiledThumbnailUrl,
                    currentPositionMs = getEffectivePositionMs(),
                    onSeekRequested = { performSeek(it); isChapterListOpen = false },
                    onClose = { isChapterListOpen = false })
            }

            AnimatedVisibility(
                isKeyframeGridOpen,
                enter = fadeIn(),
                exit = fadeOut()) {
                KeyframeGridOverlay(
                    program = currentProgram,
                    tiledThumbnailUrl = tiledThumbnailUrl,
                    currentPositionMs = getEffectivePositionMs(),
                    onSeekRequested = { performSeek(it); isKeyframeGridOpen = false },
                    onClose = { isKeyframeGridOpen = false })
            }

            AnimatedVisibility(visible = isModernSettingsOpen, enter = fadeIn(), exit = fadeOut()) {
                ModernVideoSettingsOverlay(
                    currentAudioMode = vs.currentAudioMode,
                    currentSpeed = vs.currentSpeed,
                    isSubtitleEnabled = vs.isSubtitleEnabled,
                    currentQuality = vs.currentQuality,
                    isCommentEnabled = vs.isCommentEnabled,
                    isLCropEnabled = vs.lCropEnabled,
                    isAutoCmSkipEnabled = vs.isAutoCmSkipEnabled,
                    availableQualities = availableQualities,
                    onAudioToggle = {
                        // Stateを変更するだけ。実際の適用は VideoPlayerManager の LaunchedEffect が検知して行います。
                        vs.currentAudioMode =
                            if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                        onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                    },
                    onSpeedToggle = {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); vs.currentSpeed =
                        speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(
                        vs.currentSpeed
                    ); onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    onSubtitleToggle = {
                        vs.isSubtitleEnabled =
                            !vs.isSubtitleEnabled; onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                    },
                    onQualitySelect = {
                        if (smbItem != null) {
                            onShowToast("SMB再生中は画質の変更はできません")
                            isModernSettingsOpen = false
                            return@ModernVideoSettingsOverlay
                        }
                        if (vs.currentQuality != it) {
                            vs.playbackOffsetMs = getCurrentPositionMs()
                            vs.currentQuality = it
                            videoPlayerViewModel.saveVideoQuality(it.value)
                            val player = exoPlayer
                            val currentPos = getCurrentPositionMs()
                            if (isEdcbDirect) {
                                scope.launch {
                                    isBuffering = true;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        currentProgram.id,
                                        it.value,
                                        currentSessionId,
                                        0.0,
                                        isRecordingChasePlayback
                                    ); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare(); player.seekTo(
                                    currentPos
                                ); player.play()
                                }
                            } else {
                                vs.playbackOffsetMs =
                                    currentPos - effectiveInitialPositionMs
                                scope.launch {
                                    isBuffering = true;
                                    val offsetSec = currentPos / 1000.0;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        currentProgram.id,
                                        it.value,
                                        currentSessionId,
                                        offsetSec,
                                        isRecordingChasePlayback
                                    ); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare()
                                    if (isRecordingChasePlayback) player.seekTo(currentPos)
                                    player.play()
                                }
                            }
                            onShowToast("画質を ${it.label} に変更しました")
                        }
                        isModernSettingsOpen = false; vs.lastInteractionTime =
                        System.currentTimeMillis()
                    },
                    onCommentToggle = {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode =
                                LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f; vs.lCropOrigin = ZoomOrigin.TopRight
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        if (vs.isAutoCmSkipEnabled && chapters.size <= 1) onShowToast("チャプター情報がないためスキップできません") else onShowToast(
                            "自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}"
                        )
                    },
                    onClose = { isModernSettingsOpen = false }
                )
            }

            AnimatedVisibility(
                isSubMenuOpen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()) {
                VideoTopSubMenuUI(
                    currentProgram = currentProgram,
                    seriesPrograms = quickMenuSeriesPrograms,
                    quickPrograms = quickMenuRecentPrograms,
                    animeChannels = animeChannels,
                    backendType = backendType,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    currentAudioMode = vs.currentAudioMode,
                    currentSpeed = vs.currentSpeed,
                    isSubtitleEnabled = vs.isSubtitleEnabled,
                    currentQuality = vs.currentQuality,
                    isCommentEnabled = vs.isCommentEnabled,
                    isLCropEnabled = vs.lCropEnabled,
                    isAutoCmSkipEnabled = vs.isAutoCmSkipEnabled,
                    availableQualities = availableQualities,
                    focusRequester = subMenuFocusRequester,
                    onAudioToggle = {
                        // Stateを変更するだけ。実際の適用は VideoPlayerManager の LaunchedEffect が検知して行います。
                        vs.currentAudioMode =
                            if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                        onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                    },
                    onSpeedToggle = {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); vs.currentSpeed =
                        speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(
                        vs.currentSpeed
                    ); onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    onSubtitleToggle = {
                        vs.isSubtitleEnabled =
                            !vs.isSubtitleEnabled; onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                    },
                    onQualitySelect = {
                        if (smbItem != null) {
                            onShowToast("SMB再生中は画質の変更はできません")
                            onSubMenuToggle(false)
                            return@VideoTopSubMenuUI
                        }
                        if (vs.currentQuality != it) {
                            vs.playbackOffsetMs = getCurrentPositionMs()
                            vs.currentQuality = it
                            videoPlayerViewModel.saveVideoQuality(it.value)
                            val player = exoPlayer
                            val currentPos = getCurrentPositionMs()
                            if (isEdcbDirect) {
                                scope.launch {
                                    isBuffering = true;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        currentProgram.id,
                                        it.value,
                                        currentSessionId,
                                        0.0,
                                        isRecordingChasePlayback
                                    ); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare(); player.seekTo(
                                    currentPos
                                ); player.play()
                                }
                            } else {
                                vs.playbackOffsetMs =
                                    currentPos - effectiveInitialPositionMs
                                scope.launch {
                                    isBuffering = true;
                                    val offsetSec = currentPos / 1000.0;
                                    val newUrl = videoPlayerViewModel.resolveStreamUrl(
                                        currentProgram.id,
                                        it.value,
                                        currentSessionId,
                                        offsetSec,
                                        isRecordingChasePlayback
                                    ); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare()
                                    if (isRecordingChasePlayback) player.seekTo(currentPos)
                                    player.play()
                                }
                            }
                            onShowToast("画質を ${it.label} に変更しました")
                        }
                        onSubMenuToggle(false); vs.lastInteractionTime = System.currentTimeMillis()
                    },
                    onCommentToggle = {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    },
                    onLCropToggle = {
                        vs.lCropEnabled = !vs.lCropEnabled
                        if (vs.lCropEnabled) {
                            vs.lCropMode =
                                LCropMode.MENU; onSubMenuToggle(false); onShowControlsChange(false)
                        } else {
                            vs.lCropMode = LCropMode.HIDDEN; vs.lCropZoom = 100f; vs.lCropX =
                                0f; vs.lCropY = 0f; vs.lCropOrigin = ZoomOrigin.TopRight
                        }
                    },
                    onAutoCmSkipToggle = {
                        vs.isAutoCmSkipEnabled = !vs.isAutoCmSkipEnabled
                        if (vs.isAutoCmSkipEnabled && chapters.size <= 1) onShowToast("チャプター情報がないためスキップできません") else onShowToast(
                            "自動CMスキップ: ${if (vs.isAutoCmSkipEnabled) "ON" else "OFF"}"
                        )
                    },
                    onVideoSelect = {
                        if (it.id != currentProgram.id) {
                            onProgramSelect(it)
                        }
                    },
                    onChannelSelect = onChannelSelect,
                    canOpenKeyframeGrid = canOpenSceneSearch,
                    onKeyframeGridToggle = openKeyframeGrid,
                    openQuickVideosInitially = openQuickVideosOnSubMenuOpen,
                    onCloseMenu = { onSubMenuToggle(false) },
                )
            }

            if (!isModern) {
                PlaybackIndicator(vs.indicatorState)
            }
        }
    }
}

private fun normalizeQuickSeriesKey(value: String): String =
    value
        .trim()
        .replace(Regex("[\\s　]+"), "")
        .lowercase()

@Composable
private fun NextEpisodeCountdownOverlay(
    program: RecordedProgram,
    progress: Float,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    val playNowRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        playNowRequester.safeRequestFocus(TAG)
    }

    Box(
        modifier = Modifier
            .padding(end = 48.dp, bottom = 64.dp)
            .width(420.dp)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(18.dp)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                ) {
                    onCancel()
                    true
                } else {
                    false
                }
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "次のエピソードを再生します",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayNow,
                    modifier = Modifier.focusRequester(playNowRequester),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("今すぐ")
                }
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("キャンセル")
                }
            }
        }
    }
}
