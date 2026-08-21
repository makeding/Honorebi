@file:OptIn(UnstableApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.ui.main.RecordedPlaybackToken
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.live.B60MediaPlane
import com.beeregg2001.komorebi.ui.live.DataBroadcastingColorKey
import com.beeregg2001.komorebi.ui.live.DataBroadcastingRemoteCommand
import com.beeregg2001.komorebi.ui.live.rememberLivePlayerState
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.subtitle.rememberNativeCaptionCue
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.ui.video.player.policy.NEXT_EPISODE_COUNTDOWN_WINDOW_MS
import com.beeregg2001.komorebi.ui.video.player.policy.calculateNextEpisodeCountdownStartMs
import com.beeregg2001.komorebi.ui.video.player.policy.isNextEpisodeLandingEligible
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchFailureBudget
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchFailureDecision
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchTerminalFailure
import com.beeregg2001.komorebi.ui.video.player.policy.resolveCompletedRecordingAutomationDurationMs
import com.beeregg2001.komorebi.ui.video.player.policy.resolveCompletedRecordingTimelineDurationMs
import com.beeregg2001.komorebi.ui.video.player.policy.isBangumiPlaybackProgressEligible
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedNetworkRecoveryDecision
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedNetworkRecoveryGate
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedNetworkRetry
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel as CommentChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

private const val TAG = "VideoPlayerScreen"
private const val PLAYER_CONTROLS_SUBTITLE_AVOIDANCE_START_FRACTION = 0.75f
private val PLAYER_CONTROLS_SUBTITLE_OFFSET = 96.dp

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    smbItem: SmbItem? = null,
    initialPositionMs: Long = 0,
    initialQuality: String = "1080p-60fps",
    isNetworkAvailable: Boolean = true,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    isSceneSearchOpen: Boolean,
    onSceneSearchToggle: (Boolean) -> Unit,
    recentRecordings: List<RecordedProgram> = emptyList(),
    animeChannels: List<Channel> = emptyList(),
    onProgramSelect: (RecordedProgram, RecordedProgramSelectionReason) -> Unit = { _, _ -> },
    recordedPlaybackToken: RecordedPlaybackToken? = null,
    isCurrentRecordedPlayback: (RecordedPlaybackToken) -> Boolean = { true },
    isRecordedSwitching: Boolean = false,
    onProgramReady: (programId: Int, token: RecordedPlaybackToken) -> Unit = { _, _ -> },
    onRecordedSwitchTerminalFailure: (RecordedPlaybackToken, RecordedSwitchTerminalFailure) -> Unit = { _, _ -> },
    shouldPersistWatchHistory: () -> Boolean = { true },
    onChannelSelect: (Channel) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val recordedPlaybackFence = remember(recordedPlaybackToken, smbItem?.path) {
        RecordedPlaybackFence(
            token = recordedPlaybackToken,
            isCurrent = isCurrentRecordedPlayback,
            identity = recordedPlaybackToken ?: "smb:${smbItem?.path.orEmpty()}:${program.id}",
        )
    }
    val scope = rememberCoroutineScope()

    var currentProgram by remember { mutableStateOf(program) }
    val fetchedDetail by videoPlayerViewModel.programDetail.collectAsState()

    val tiledThumbnailUrl by videoPlayerViewModel.tiledThumbnailUrl.collectAsState()
    val chapters by videoPlayerViewModel.chapters.collectAsState()
    val isLiveStream by videoPlayerViewModel.isLiveStream.collectAsState()

    val availableQualities by videoPlayerViewModel.availableQualities.collectAsState()
    val isQualitiesLoaded by videoPlayerViewModel.isQualitiesLoaded.collectAsState()
    val quickVideoCandidates by videoPlayerViewModel.quickVideoCandidates.collectAsState()
    val hdrRenderMode by videoPlayerViewModel.hdrRenderMode.collectAsState()
    val currentVideoQualityStr by settingsViewModel.videoQuality.collectAsState()
    val preferOriginalMpegTs by settingsViewModel.preferOriginalMpegTs.collectAsState()

    val isModern = false
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(program.id, recordedPlaybackToken) {
        if (!recordedPlaybackFence.accepts()) return@LaunchedEffect
        currentProgram = program
        if (smbItem == null) {
            videoPlayerViewModel.fetchProgramDetail(program.id)
            videoPlayerViewModel.fetchAvailableQualities(program)
        }
    }

    LaunchedEffect(fetchedDetail, recordedPlaybackToken) {
        if (recordedPlaybackFence.accepts() && fetchedDetail != null && fetchedDetail?.id == program.id) {
            currentProgram = fetchedDetail!!
            if (smbItem == null) videoPlayerViewModel.fetchAvailableQualities(fetchedDetail)
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
    var hdrModeResumePositionMs by remember(currentProgram.id) {
        mutableStateOf<Long?>(null)
    }
    var armedManualCmSkipTargetMs by remember(currentProgram.id) {
        mutableStateOf<Long?>(null)
    }
    val requiresRawMmtsPlayback = currentProgram.requiresRawMmtsPlayback
    val isHdrRenderModeSupported =
        requiresRawMmtsPlayback && videoPlayerViewModel.isHdrToSdrToneMappingSupported
    val enableHdrToSdrToneMapping =
        isHdrRenderModeSupported && hdrRenderMode == HdrToneMapping.RENDER_MODE_SDR

    val vs = rememberVideoPlayerState()
    val dataBroadcastingInput = rememberLivePlayerState(LocalContext.current)
    val dataBroadcastingChannel = remember(currentProgram) {
        currentProgram.toDataBroadcastingChannel()
    }
    val dataBroadcastingStore = remember(currentProgram.id) {
        B60DataBroadcastingStore().apply {
            beginSession(dataBroadcastingChannel.id)
        }
    }
    var isDataBroadcastingMode by rememberSaveable(currentProgram.id) {
        mutableStateOf(false)
    }
    var isDataBroadcastingBlank by rememberSaveable(currentProgram.id) {
        mutableStateOf(false)
    }
    var dataBroadcastingMediaPlane by remember(currentProgram.id) {
        mutableStateOf<B60MediaPlane?>(null)
    }
    var dataBroadcastingRemoteSequence by rememberSaveable { mutableLongStateOf(0L) }
    var dataBroadcastingRemoteCommand by remember(currentProgram.id) {
        mutableStateOf<DataBroadcastingRemoteCommand?>(null)
    }
    var hasShownDataBroadcastingHint by rememberSaveable { mutableStateOf(false) }
    val isDataBroadcastingAvailable = requiresRawMmtsPlayback && vs.currentQuality.isRawMmts
    val isDataBroadcastingActive = isDataBroadcastingAvailable && isDataBroadcastingMode
    val dispatchDataBroadcastingRemoteKey: (String) -> Unit = { key ->
        dataBroadcastingRemoteSequence += 1L
        dataBroadcastingRemoteCommand = DataBroadcastingRemoteCommand(
            id = dataBroadcastingRemoteSequence,
            key = key
        )
    }
    val dispatchDataBroadcastingColorKey: (DataBroadcastingColorKey) -> Unit = { colorKey ->
        dispatchDataBroadcastingRemoteKey(recordedDataBroadcastingRemoteKey(colorKey))
    }
    val closeDataBroadcasting: () -> Unit = {
        isDataBroadcastingMode = false
        isDataBroadcastingBlank = false
        dataBroadcastingMediaPlane = null
        dataBroadcastingRemoteCommand = null
        dataBroadcastingInput.resetDataBroadcastingInput()
    }
    val openDataBroadcasting: () -> Unit = {
        if (isDataBroadcastingAvailable && !isDataBroadcastingMode) {
            isDataBroadcastingMode = true
            isDataBroadcastingBlank = false
            dataBroadcastingInput.resetDataBroadcastingInput()
            onSubMenuToggle(false)
            onShowControlsChange(false)
            if (!hasShownDataBroadcastingHint) {
                hasShownDataBroadcastingHint = true
                onShowToast(
                    "録画データ放送：方向キー2回で色ボタン（↑青 / →赤 / ↓緑 / ←黄）"
                )
            }
        }
    }

    val autoCmSkipStr by settingsViewModel.autoCmSkip.collectAsState()
    val cmSkipMode = CmSkipMode.fromPreference(autoCmSkipStr)

    LaunchedEffect(
        availableQualities,
        isQualitiesLoaded,
        currentVideoQualityStr,
        preferOriginalMpegTs,
        requiresRawMmtsPlayback
    ) {
        if (isQualitiesLoaded && availableQualities.isNotEmpty()) {
            val preferredOriginal = if (preferOriginalMpegTs == "ON") {
                availableQualities.firstOrNull {
                    it.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE
                }
            } else {
                null
            }
            val matched = if (requiresRawMmtsPlayback) {
                availableQualities.firstOrNull { it.isRawMmts }
            } else {
                preferredOriginal
                ?: availableQualities.find { it.value == vs.currentQuality.value }
                ?: availableQualities.find { it.value == currentVideoQualityStr }
            }
            if (matched != null) {
                vs.currentQuality = matched
            } else {
                val fallback = availableQualities.firstOrNull {
                    it.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
                } ?: availableQualities.first()
                vs.currentQuality = fallback
                if (
                    !fallback.isRawMmts &&
                    fallback.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
                ) {
                    videoPlayerViewModel.saveVideoQuality(fallback.value)
                }
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
    val timeFormat by settingsViewModel.timeFormat.collectAsState()

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
    val commentKeys = remember(recordedPlaybackToken) {
        HashSet<String>()
    }
    val pendingWebSocketComments = remember(recordedPlaybackToken) {
        CommentChannel<ArchivedComment>(
            capacity = CHASE_COMMENT_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    var currentSessionId by remember(recordedPlaybackToken, vs.currentQuality.value, isRecordingChasePlayback) {
        mutableStateOf(UUID.randomUUID().toString())
    }
    val currentStreamUrlRef = remember(recordedPlaybackToken) { AtomicReference<String?>(null) }
    val subtitleEvents = remember {
        MutableSharedFlow<NativeCaptionCue>(
            extraBufferCapacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val captionEvents = remember(subtitleEvents) {
        subtitleEvents.filter { it.type == NativeCaptionCue.TYPE_CAPTION }
    }
    val superimposeEvents = remember(subtitleEvents) {
        subtitleEvents.filter { it.type == NativeCaptionCue.TYPE_SUPERIMPOSE }
    }
    var subtitleLanguages by remember(recordedPlaybackToken) {
        mutableStateOf(emptyList<NativeCaptionLanguage>())
    }
    var currentSubtitleLanguageId by remember(recordedPlaybackToken) { mutableIntStateOf(1) }
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

    val openProgramInfo: () -> Unit = {
        isProgramInfoOpen = true
        onShowControlsChange(true)
    }
    val closeProgramInfo: () -> Unit = {
        isProgramInfoOpen = false
        onShowControlsChange(true)
        scope.launch {
            delay(150)
            mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    val isSubOverlayOpen =
        isSubMenuOpen || isSceneSearchOpen || isChapterListOpen || isKeyframeGridOpen || isProgramInfoOpen || isModernSettingsOpen
    val isSubtitleBlockingOverlayOpen =
        isSceneSearchOpen || isChapterListOpen || isKeyframeGridOpen || isProgramInfoOpen || isModernSettingsOpen
    val subtitleOffset by animateDpAsState(
        targetValue = if (
            showControls &&
            !isSubOverlayOpen &&
            vs.lCropMode == LCropMode.HIDDEN
        ) {
            PLAYER_CONTROLS_SUBTITLE_OFFSET
        } else {
            0.dp
        },
        animationSpec = tween(durationMillis = 180),
        label = "playerControlsSubtitleOffset"
    )

    val buildVideoMediaItem: (String) -> MediaItem = { url ->
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (
            url.contains("/raw-mmts/mpegts") ||
            url.substringBefore('?').endsWith("/download")
        ) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
        } else if (url.contains("/api/streams/") || url.contains("/api/videos/") || url.contains("konomi.tv") || url.contains(
                "m3u8"
            )
        ) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            if (isRecordingChasePlayback) {
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(CHASE_PLAYBACK_TARGET_LIVE_OFFSET_MS)
                        .setMinOffsetMs(CHASE_PLAYBACK_MIN_LIVE_OFFSET_MS)
                        .setMaxOffsetMs(CHASE_PLAYBACK_MAX_LIVE_OFFSET_MS)
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build()
                )
            }
        }
        mediaItemBuilder.build()
    }

    val triggerSeekingPreview: () -> Unit = {
        isSeekingPreviewVisible = true
        seekingPreviewJob?.cancel()
        seekingPreviewJob = scope.launch { delay(2000); isSeekingPreviewVisible = false }
    }

    LaunchedEffect(currentProgram.recordedVideo.id, smbItem, isRecordingChasePlayback, recordedPlaybackToken) {
        if (smbItem != null) {
            return@LaunchedEffect
        }

        if (isRecordingChasePlayback) {
            allComments.clear()
            commentKeys.clear()
            Log.i(
                TAG,
                "Reset chase comments for new video. [video=${currentProgram.recordedVideo.id}]"
            )
        }

        val fetchedComments = if (isRecordingChasePlayback) {
            videoPlayerViewModel.getChaseArchivedComments(currentProgram)
        } else {
            videoPlayerViewModel.getArchivedComments(currentProgram.recordedVideo.id)
        }
        if (!recordedPlaybackFence.accepts()) return@LaunchedEffect
        if (!isRecordingChasePlayback) {
            allComments.clear()
            commentKeys.clear()
            allComments.addAll(fetchedComments)
            commentKeys.addAll(fetchedComments.map { it.stableCommentKey() })
        } else {
            // 追いかけ再生はここで A-B の過去ログだけを取得し、B 以降は WebSocket で埋める。
            appendUniqueArchivedComments(allComments, commentKeys, fetchedComments)
        }
        Log.i(
            TAG,
            "Loaded archived comments. [video=${currentProgram.recordedVideo.id}, chase=$isRecordingChasePlayback, total=${allComments.size}]"
        )
    }

    LaunchedEffect(pendingWebSocketComments, recordedPlaybackToken) {
        try {
            while (isActive) {
                val firstComment = pendingWebSocketComments.receiveCatching().getOrNull()
                    ?: return@LaunchedEffect
                val batch = ArrayList<ArchivedComment>(32)
                batch.add(firstComment)
                delay(250L)
                while (true) {
                    val nextComment = pendingWebSocketComments.tryReceive().getOrNull() ?: break
                    batch.add(nextComment)
                }

                if (!recordedPlaybackFence.accepts()) continue
                val oldSize = allComments.size
                appendUniqueArchivedComments(allComments, commentKeys, batch)
                val addedCount = allComments.size - oldSize
                if (addedCount > 0) {
                    Log.i(
                        TAG,
                        "Merged chase websocket batch. [video=${currentProgram.recordedVideo.id}, " +
                            "received=${batch.size}, added=$addedCount, total=${allComments.size}]"
                    )
                }
            }
        } finally {
            pendingWebSocketComments.close()
        }
    }

    LaunchedEffect(currentProgram.id, smbItem, isRecordingChasePlayback, recordedPlaybackToken) {
        if (smbItem != null || !isRecordingChasePlayback) return@LaunchedEffect
        val programStartUnix = currentProgram.programStartUnixOrNull() ?: return@LaunchedEffect
        val watchSessionUrl = videoPlayerViewModel.getChaseJikkyoWatchSessionUrl(currentProgram)
            ?: return@LaunchedEffect
        if (!recordedPlaybackFence.accepts()) return@LaunchedEffect
        val processedCommentKeys = mutableSetOf<String>()
        var receivedCommentCount = 0
        val client = JikkyoClient(watchSessionUrl)

        try {
            client.start { jsonText ->
                if (!recordedPlaybackFence.accepts()) return@start
                val comment = parseChaseWsArchivedComment(jsonText, programStartUnix) ?: return@start
                receivedCommentCount++
                val key = comment.stableCommentKey()
                if (!processedCommentKeys.add(key)) return@start
                if (processedCommentKeys.size > 4000) processedCommentKeys.clear()

                if (pendingWebSocketComments.trySend(comment).isFailure) {
                    Log.w(
                        TAG,
                        "Dropped chase websocket comment because the queue is closed. [video=${currentProgram.recordedVideo.id}]"
                    )
                } else if (receivedCommentCount <= 3 || receivedCommentCount % 50 == 0) {
                    Log.i(
                        TAG,
                        "Queued chase websocket comment. [video=${currentProgram.recordedVideo.id}, " +
                            "received=$receivedCommentCount, time=${comment.time}]"
                    )
                }
            }
            Log.i(TAG, "Started chase jikkyo websocket. [video=${currentProgram.recordedVideo.id}]")
            awaitCancellation()
        } finally {
            client.stop()
            Log.i(TAG, "Stopped chase jikkyo websocket. [video=${currentProgram.recordedVideo.id}]")
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
    val isNextEpisodeLandingEligible = remember(currentProgram.id, currentProgram.genres) {
        isNextEpisodeLandingEligible(currentProgram)
    }

    val handlePlaybackEnded: () -> Unit = {
        if (!hasHandledPlaybackEnd && smbItem == null) {
            hasHandledPlaybackEnd = true
            val nextEpisode = nextEpisodeProgramForEnd
            if (
                isNextEpisodeLandingEligible &&
                nextEpisode != null &&
                !hasAutoStartedNextEpisode &&
                !isNextEpisodeCountdownCancelled
            ) {
                hasAutoStartedNextEpisode = true
                onProgramSelect(nextEpisode, RecordedProgramSelectionReason.NextEpisode)
            } else {
                openQuickVideosOnSubMenuOpen = true
                onPlaybackEnded()
                onSubMenuToggle(true)
                onShowControlsChange(false)
            }
        }
    }

    val switchFailureBudget = remember(recordedPlaybackToken, isRecordedSwitching) {
        RecordedSwitchFailureBudget().takeIf { isRecordedSwitching }
    }
    val networkAvailableRef = remember { AtomicBoolean(isNetworkAvailable) }
    SideEffect { networkAvailableRef.set(isNetworkAvailable) }
    // Connectivity ownership follows the player/program lifetime so error
    // callbacks and the online-edge effect share one recovery gate.
    val networkRecoveryGate = remember(currentProgram.id, smbItem?.path) {
        RecordedNetworkRecoveryGate(initiallyAvailable = isNetworkAvailable)
    }
    var isWaitingForNetworkRecovery by remember(currentProgram.id, smbItem?.path) {
        mutableStateOf(false)
    }
    var initialUrlRetryNonce by remember(recordedPlaybackToken) { mutableIntStateOf(0) }
    val currentPlaybackFence by rememberUpdatedState(recordedPlaybackFence)
    val currentTerminalSwitchFailure by rememberUpdatedState(onRecordedSwitchTerminalFailure)
    val renewStreamSession: suspend (ExoPlayer) -> Boolean = { player ->
        if (!currentPlaybackFence.accepts() || smbItem != null || currentProgram.id == 0 || vs.currentQuality.value.isBlank()) {
            false
        } else {
            val rawPosition = player.currentPosition
            val resumePositionMs = if (rawPosition == C.TIME_UNSET || rawPosition < 0L) {
                playbackPositionMs
            } else if (isLiveStream && !isRecordingChasePlayback) {
                vs.playbackOffsetMs + rawPosition
            } else {
                rawPosition
            }.coerceAtLeast(0L)

            val newSessionId = UUID.randomUUID().toString()
            currentSessionId = newSessionId
            vs.playbackOffsetMs = resumePositionMs
            isBuffering = true
            val newUrl = videoPlayerViewModel.resolveStreamUrl(
                currentProgram.id, vs.currentQuality.value, newSessionId,
                resumePositionMs / 1000.0, isRecordingChasePlayback,
            )
            if (!currentPlaybackFence.accepts() || newUrl.isEmpty()) {
                false
            } else {
                Log.i(TAG, "Recovered expired stream session for video=${currentProgram.id}, quality=${vs.currentQuality.value}")
                currentStreamUrlRef.set(newUrl)
                val mediaItem = buildVideoMediaItem(newUrl)
                if (resumePositionMs > 0L && (!isLiveStream || isRecordingChasePlayback)) {
                    player.setMediaItem(mediaItem, resumePositionMs)
                } else {
                    player.setMediaItem(mediaItem)
                }
                player.prepare()
                player.playWhenReady = true
                true
            }
        }
    }
    val recoverExpiredStreamSession: suspend (ExoPlayer) -> Boolean = { player ->
        if (
            networkRecoveryGate.onFailure(
                RecordedNetworkRetry.RenewStreamSession,
                networkAvailableRef.get(),
            ) == RecordedNetworkRecoveryDecision.WaitForNetwork
        ) {
            isWaitingForNetworkRecovery = true
            true
        } else {
            renewStreamSession(player)
        }
    }
    val dataBroadcastingCallback = remember(
        dataBroadcastingStore,
        recordedPlaybackFence.identity
    ) {
        FencedB60DataBroadcastingCallback(dataBroadcastingStore, recordedPlaybackFence)
    }
    val exoPlayer = rememberManagedExoPlayer(
        program = currentProgram,
        recordedPlaybackFence = recordedPlaybackFence,
        vs = vs,
        isLiveStream = isLiveStream,
        scope = scope,
        onSubtitleCue = { subtitleEvents.tryEmit(it) },
        subtitleLanguageId = currentSubtitleLanguageId,
        onSubtitleLanguagesChanged = { subtitleLanguages = it },
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
        dataBroadcastingCallback = dataBroadcastingCallback,
        enableHdrToSdrToneMapping = enableHdrToSdrToneMapping,
        isNetworkAvailable = networkAvailableRef::get,
        onStreamSessionExpired = recoverExpiredStreamSession,
        onPlayerErrorRecovery = { player, error ->
            if (HdrToneMapping.rejectionCause(error) != null) {
                val currentPosition = player.currentPosition
                    .takeUnless { it == C.TIME_UNSET || it < 0L }
                    ?: playbackPositionMs
                hdrModeResumePositionMs = currentPosition
                playbackPositionMs = currentPosition
                videoPlayerViewModel.setHdrRenderMode(HdrToneMapping.RENDER_MODE_ORIGINAL)
                onShowToast(
                    "SDR 変換に失敗しました（HDR_TONE_MAPPING_UNSUPPORTED）。" +
                        "テレビのデコーダーが変換要求を受け付けなかったため、" +
                        "HLG そのままに戻して再生します。"
                )
                return@rememberManagedExoPlayer PlayerErrorRecovery.Handled
            }
            val networkRetry = if (smbItem == null) {
                RecordedNetworkRetry.RenewStreamSession
            } else {
                RecordedNetworkRetry.RepreparePlayer
            }
            if (
                networkRecoveryGate.onFailure(networkRetry, networkAvailableRef.get()) ==
                RecordedNetworkRecoveryDecision.WaitForNetwork
            ) {
                isWaitingForNetworkRecovery = true
                return@rememberManagedExoPlayer PlayerErrorRecovery.Handled
            }
            val token = currentPlaybackFence.tokenOrNull()
            if (!currentPlaybackFence.accepts() || token == null) return@rememberManagedExoPlayer PlayerErrorRecovery.UseDefault
            val budget = switchFailureBudget
                ?: return@rememberManagedExoPlayer PlayerErrorRecovery.UseDefault
            val decision = if (error.hasHttpResponseCode(422)) budget.onHttp422() else budget.onPlaybackError()
            when (decision) {
                RecordedSwitchFailureDecision.RenewSession -> {
                    if (renewStreamSession(player)) PlayerErrorRecovery.Handled else {
                        val terminal = budget.onSessionRenewalUrlUnavailable()
                            as RecordedSwitchFailureDecision.Terminal
                        currentTerminalSwitchFailure(token, terminal.failure)
                        PlayerErrorRecovery.Handled
                    }
                }
                RecordedSwitchFailureDecision.Reprepare -> PlayerErrorRecovery.Reprepare
                is RecordedSwitchFailureDecision.Terminal -> {
                    currentTerminalSwitchFailure(token, decision.failure)
                    PlayerErrorRecovery.Handled
                }
                RecordedSwitchFailureDecision.RetryInitialUrl -> PlayerErrorRecovery.Handled
            }
        },
        onStopOrDispose = { player ->
            val pendingPositionMs = vs.pendingSeekPositionMs
            // 画質の初期化中に作られた空の Player は、Raw MMTS Player への
            // 再構築時に dispose される。未準備の0秒でレジューム位置を消さず、
            // シーク中なら底層 Player の旧位置より UI の最終目標を優先する。
            if (shouldPersistWatchHistory() && smbItem == null && (player.mediaItemCount > 0 || pendingPositionMs != null)) {
                val rawPosition = player.currentPosition
                val posMs = resolvePersistablePlaybackPositionMs(
                    pendingSeekPositionMs = pendingPositionMs,
                    rawPlayerPositionMs = rawPosition.takeUnless { it == C.TIME_UNSET },
                    fallbackPositionMs = playbackPositionMs,
                    isPlayerReady = player.playbackState == Player.STATE_READY,
                    isLiveStream = isLiveStream,
                    isRecordingChasePlayback = isRecordingChasePlayback,
                    playbackOffsetMs = vs.playbackOffsetMs
                )
                // A legitimate renderer/source reconstruction must resume the
                // old instance's last UI/pending position instead of asking
                // the new, empty player (which necessarily reports zero).
                playbackPositionMs = posMs
                videoPlayerViewModel.updateWatchHistory(currentProgram, posMs / 1000.0)
            }
        }
    )

    LaunchedEffect(isNetworkAvailable, exoPlayer, networkRecoveryGate, recordedPlaybackToken) {
        when (val decision = networkRecoveryGate.onNetworkChanged(isNetworkAvailable)) {
            is RecordedNetworkRecoveryDecision.Retry -> when (decision.operation) {
                RecordedNetworkRetry.ResolveInitialUrl -> initialUrlRetryNonce++
                RecordedNetworkRetry.RepreparePlayer -> {
                    delay(500L)
                    if (!currentPlaybackFence.accepts()) return@LaunchedEffect
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    isWaitingForNetworkRecovery = false
                }
                RecordedNetworkRetry.RenewStreamSession -> {
                    // The old playlist may have expired while offline. Mint a
                    // fresh session instead of preparing the stale URL.
                    delay(500L)
                    var renewed = renewStreamSession(exoPlayer)
                    if (!renewed && networkAvailableRef.get()) {
                        delay(1_000L)
                        renewed = renewStreamSession(exoPlayer)
                    }
                    if (!renewed) {
                        if (!networkAvailableRef.get()) {
                            networkRecoveryGate.onFailure(
                                RecordedNetworkRetry.RenewStreamSession,
                                networkAvailable = false,
                            )
                        } else {
                            isWaitingForNetworkRecovery = false
                            currentPlaybackFence.tokenOrNull()?.let { token ->
                                if (currentPlaybackFence.accepts()) currentTerminalSwitchFailure(
                                    token, RecordedSwitchTerminalFailure.SessionRenewalFailed,
                                )
                            }
                        }
                    } else {
                        isWaitingForNetworkRecovery = false
                    }
                }
            }
            else -> Unit
        }
    }

    // A player can transition through READY more than once while buffering or
    // renewing a stream. Root only needs the first READY for this program to
    // commit a pending A -> B handoff, so report it exactly once.
    val currentOnProgramReady by rememberUpdatedState(onProgramReady)
    var hasReportedProgramReady by remember(recordedPlaybackToken) { mutableStateOf(false) }
    var renderedFrameGeneration by remember(exoPlayer, recordedPlaybackToken) { mutableIntStateOf(0) }
    DisposableEffect(exoPlayer, recordedPlaybackToken) {
        fun reportReadyOnce() {
            val token = currentPlaybackFence.tokenOrNull()
            if (!hasReportedProgramReady && token != null && currentPlaybackFence.accepts()) {
                hasReportedProgramReady = true
                networkRecoveryGate.onReady()
                isWaitingForNetworkRecovery = false
                currentOnProgramReady(currentProgram.id, token)
            }
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedFrameGeneration++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) reportReadyOnce()
            }
        }
        exoPlayer.addListener(listener)
        if (exoPlayer.playbackState == Player.STATE_READY) reportReadyOnce()
        onDispose { exoPlayer.removeListener(listener) }
    }

    val subtitleCue = rememberNativeCaptionCue(
        events = captionEvents,
        enabled = vs.isSubtitleEnabled,
        resetKey = currentProgram.id to currentSubtitleLanguageId,
        clockRunning = vs.isPlayerPlaying,
        positionMsProvider = { exoPlayer.currentPosition.coerceAtLeast(0L) }
    )
    val superimposeCue = rememberNativeCaptionCue(
        events = superimposeEvents,
        enabled = true,
        resetKey = currentProgram.id,
        clockRunning = vs.isPlayerPlaying,
        positionMsProvider = { exoPlayer.currentPosition.coerceAtLeast(0L) }
    )

    val getCurrentPositionMs: () -> Long = {
        val rawPosition = exoPlayer.currentPosition
        if (
            rawPosition == C.TIME_UNSET ||
            rawPosition < 0L ||
            rawPosition == 0L && playbackPositionMs > 0L &&
            exoPlayer.playbackState != Player.STATE_READY
        ) {
            playbackPositionMs
        } else if (isLiveStream && !isRecordingChasePlayback) {
            vs.playbackOffsetMs + rawPosition
        } else {
            rawPosition
        }.coerceAtLeast(0L)
    }

    val playbackStatePollIntervalMs = if (
        showControls || isSeekingPreviewVisible || cmSkipMode == CmSkipMode.MANUAL
    ) {
        ACTIVE_PLAYBACK_STATE_POLL_MS
    } else {
        IDLE_PLAYBACK_STATE_POLL_MS
    }
    LaunchedEffect(
        exoPlayer,
        currentProgram.id,
        isLiveStream,
        isRecordingChasePlayback,
        playbackStatePollIntervalMs
    ) {
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

            delay(playbackStatePollIntervalMs)
        }
    }

    val backendType by settingsViewModel.backendType.collectAsState()
    val konomiIp by settingsViewModel.konomiIp.collectAsState(initial = "")
    val konomiPort by settingsViewModel.konomiPort.collectAsState(initial = "")
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")

    val systemArtworkUrl = remember(
        currentProgram.id,
        currentProgram.directThumbnailUrl,
        currentProgram.apiThumbnailUrl,
        smbItem?.thumbnailUrl,
        backendType,
        konomiIp,
        konomiPort
    ) {
        smbItem?.thumbnailUrl
            ?: currentProgram.directThumbnailUrl
            ?: currentProgram.apiThumbnailUrl
            ?: if (smbItem == null && currentProgram.id != 0) {
                UrlBuilder.getThumbnailUrl(
                    backendType,
                    konomiIp,
                    konomiPort,
                    currentProgram.id.toString()
                )
            } else null
    }
    val getEffectivePositionMs = { vs.pendingSeekPositionMs ?: getCurrentPositionMs() }

    val usesSerializedRawMmtsSeek = requiresRawMmtsPlayback && vs.currentQuality.isRawMmts
    val usesExtractorByteSeek = isEdcbDirect || usesSerializedRawMmtsSeek || (
        currentProgram.recordedVideo.containerFormat.equals("MPEG-TS", ignoreCase = true) &&
            currentProgram.recordedVideo.videoCodec.equals("MPEG-2", ignoreCase = true) &&
            vs.currentQuality.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE
        )
    val rawMmtsSeekCoordinator = remember(exoPlayer, currentProgram.id) {
        RawMmtsSeekCoordinator()
    }
    var pendingRawMmtsSeekJob by remember(exoPlayer, currentProgram.id) {
        mutableStateOf<Job?>(null)
    }
    var activeRawMmtsSeekPositionMs by remember(exoPlayer, currentProgram.id) {
        mutableStateOf<Long?>(null)
    }

    fun scheduleRawMmtsSeekCommit() {
        pendingRawMmtsSeekJob?.cancel()
        pendingRawMmtsSeekJob = scope.launch {
            delay(RAW_MMTS_SEEK_DEBOUNCE_MS)
            rawMmtsSeekCoordinator.beginNext()?.let { targetMs ->
                activeRawMmtsSeekPositionMs = targetMs
                exoPlayer.seekTo(targetMs)
            }
            pendingRawMmtsSeekJob = null
        }
    }

    LaunchedEffect(exoPlayer, activeRawMmtsSeekPositionMs) {
        val activeTargetMs = activeRawMmtsSeekPositionMs ?: return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        delay(RAW_MMTS_SEEK_MIN_SETTLE_MS)
        while (isActive) {
            val playbackSettled =
                (exoPlayer.playbackState == Player.STATE_READY ||
                    exoPlayer.playbackState == Player.STATE_ENDED)
            if (playbackSettled) break
            if (SystemClock.elapsedRealtime() - startedAt >= RAW_MMTS_SEEK_SETTLE_TIMEOUT_MS) {
                Log.w(TAG, "Raw MMTS seek settle timed out at position_ms=$activeTargetMs")
                break
            }
            delay(50L)
        }

        val hasQueuedTarget = rawMmtsSeekCoordinator.finish(activeTargetMs)
        activeRawMmtsSeekPositionMs = null
        if (hasQueuedTarget) {
            scheduleRawMmtsSeekCommit()
        } else if (vs.pendingSeekPositionMs == activeTargetMs) {
            vs.pendingSeekPositionMs = null
        }
    }

    DisposableEffect(exoPlayer, currentProgram.id) {
        onDispose {
            pendingRawMmtsSeekJob?.cancel()
            pendingRawMmtsSeekJob = null
            rawMmtsSeekCoordinator.reset()
        }
    }

    val totalDurationForControls =
        if (smbItem != null) {
            smbDurationMs.coerceAtLeast(0L)
        } else if (isRecordingChasePlayback) {
            maxOf(
                currentProgram.chaseElapsedDurationMs(),
                playbackDurationMs,
                playbackPositionMs,
                bufferedPositionMs
            ).coerceAtLeast(0L)
        } else {
            resolveCompletedRecordingTimelineDurationMs(
                isRawMmtsPlayback = usesSerializedRawMmtsSeek,
                konomiReportedDurationMs =
                    (currentProgram.recordedVideo.duration * 1000).toLong(),
                nativePlayerDurationMs = playbackDurationMs,
                playbackPositionMs = playbackPositionMs,
                bufferedPositionMs = bufferedPositionMs,
            )
        }
    val trustedPlaybackEndDurationMs =
        if (smbItem != null || isRecordingChasePlayback) {
            0L
        } else {
            resolveCompletedRecordingAutomationDurationMs(
                requiresRawMmtsPlayback = requiresRawMmtsPlayback,
                isRawMmtsPlayback = usesSerializedRawMmtsSeek,
                konomiReportedDurationMs =
                    (currentProgram.recordedVideo.duration * 1000).toLong(),
                nativePlayerDurationMs = playbackDurationMs,
            )
        }

    val bangumiPlaybackCompletionThresholdMs = if (currentProgram.recordedVideo.cmSections.isNullOrEmpty().not()) {
        (currentProgram.recordedVideo.playbackCompletionThreshold * 1000.0).toLong()
    } else {
        trustedPlaybackEndDurationMs * 9L / 10L
    }
    LaunchedEffect(currentProgram.id, bangumiPlaybackCompletionThresholdMs, playbackPositionMs) {
        if (
            smbItem == null &&
            !isRecordingChasePlayback &&
            isBangumiPlaybackProgressEligible(playbackPositionMs, bangumiPlaybackCompletionThresholdMs)
        ) {
            videoPlayerViewModel.reportBangumiPlaybackProgress(
                currentProgram.id,
                playbackPositionMs,
                trustedPlaybackEndDurationMs,
            )
        }
    }

    LaunchedEffect(isSubMenuOpen, currentProgram.id) {
        if (!isSubMenuOpen) openQuickVideosOnSubMenuOpen = false
    }

    LaunchedEffect(currentProgram.id, smbItem, trustedPlaybackEndDurationMs) {
        if (
            smbItem != null ||
            trustedPlaybackEndDurationMs <= PLAYBACK_END_FALLBACK_WINDOW_MS
        ) {
            return@LaunchedEffect
        }
        while (isActive && !hasHandledPlaybackEnd) {
            val remainingMs = trustedPlaybackEndDurationMs - getCurrentPositionMs()
            if (remainingMs in 0..PLAYBACK_END_FALLBACK_WINDOW_MS) {
                delay(remainingMs + PLAYBACK_END_FALLBACK_GRACE_MS)
                if (
                    !hasHandledPlaybackEnd &&
                    exoPlayer.playbackState == Player.STATE_READY &&
                    getCurrentPositionMs() >= trustedPlaybackEndDurationMs - 1_500L
                ) {
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

    val performSeek: (Long) -> Unit = seek@ { targetMs: Long ->
        if (usesExtractorByteSeek && !exoPlayer.isCurrentMediaItemSeekable) {
            onShowToast("シーク情報を準備しています")
            return@seek
        }
        val safeTarget = targetMs.coerceIn(
            0L,
            if (totalDurationForControls > 0) totalDurationForControls else Long.MAX_VALUE
        )
        // 一瞬だけpendingSeekに記録してUI表示をサクサク進める
        vs.pendingSeekPositionMs = safeTarget
        playbackPositionMs = safeTarget
        if (!usesSerializedRawMmtsSeek) {
            scope.launch {
                delay(800)
                if (vs.pendingSeekPositionMs == safeTarget) {
                    vs.pendingSeekPositionMs = null
                }
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
                    currentStreamUrlRef.set(newUrl)
                    exoPlayer.setMediaItem(buildVideoMediaItem(newUrl))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                } else {
                    if (fetchedDetail != null) onShowToast("シーク先ストリームの取得に失敗しました")
                }
            }
        } else {
            val commitSeek = {
                exoPlayer.seekTo(safeTarget)
                if (smbItem == null && currentProgram.id != 0) {
                    videoPlayerViewModel.updateWatchHistory(
                        currentProgram,
                        safeTarget / 1_000.0
                    )
                }
            }
            if (usesSerializedRawMmtsSeek) {
                rawMmtsSeekCoordinator.request(safeTarget)
                if (smbItem == null && currentProgram.id != 0) {
                    // Player がまだ旧位置で BUFFERING 中でも、退出時に失わないよう
                    // 最終 UI 目標を先にチェックポイントする。
                    videoPlayerViewModel.updateWatchHistory(
                        currentProgram,
                        safeTarget / 1_000.0
                    )
                }
                scheduleRawMmtsSeekCommit()
            } else {
                commitSeek()
            }
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

    LaunchedEffect(cmSkipMode, chapters) {
        var hasWarnedEmptyChapters = false
        while (isActive) {
            if (cmSkipMode == CmSkipMode.AUTO && exoPlayer.isPlaying) {
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
    var lastChasePlaylistRefreshAt by remember(
        currentProgram.id,
        vs.currentQuality.value,
        isRecordingChasePlayback
    ) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    val qualityOptionsKey = remember(availableQualities) {
        availableQualities.joinToString(separator = "|") { it.value }
    }

    LaunchedEffect(currentProgram.id, smbItem?.path, recordedPlaybackToken) {
        isFirstLoad = true
        preparedPlaybackKey = null
        currentStreamUrlRef.set(null)
        vs.playbackOffsetMs = 0L
        vs.pendingSeekPositionMs = null
        hdrModeResumePositionMs = null
        playbackPositionMs = effectiveInitialPositionMs.coerceAtLeast(0L)
        playbackDurationMs = 0L
        bufferedPositionMs = 0L
    }

    LaunchedEffect(
        exoPlayer,
        currentProgram.id,
        smbItem?.path,
        vs.currentQuality.value,
        qualityOptionsKey,
        isQualitiesLoaded,
        isRecordingChasePlayback,
        initialUrlRetryNonce,
        recordedPlaybackToken,
    ) {
        val playbackKey = if (smbItem != null) {
            "smb:${smbItem.path}"
        } else {
            "video:${currentProgram.id}:${vs.currentQuality.value}:$isRecordingChasePlayback"
        }
        if (preparedPlaybackKey == playbackKey && exoPlayer.mediaItemCount > 0) {
            return@LaunchedEffect
        }

        if (
            networkRecoveryGate.onFailure(
                RecordedNetworkRetry.ResolveInitialUrl,
                networkAvailableRef.get(),
            ) == RecordedNetworkRecoveryDecision.WaitForNetwork
        ) {
            isBuffering = true
            isWaitingForNetworkRecovery = true
            return@LaunchedEffect
        }

        if (smbItem != null) {
            isBuffering = true
            vs.playbackOffsetMs = 0L
            val mediaItem = MediaItem.fromUri(smbItem.path)
            val startPositionMs = hdrModeResumePositionMs
                ?: effectiveInitialPositionMs.takeIf { isFirstLoad && it > 0L }
            if (startPositionMs != null) {
                exoPlayer.setMediaItem(mediaItem, startPositionMs)
            } else {
                exoPlayer.setMediaItem(mediaItem)
            }
            isFirstLoad = false
            exoPlayer.prepare()
            preparedPlaybackKey = playbackKey
            exoPlayer.playWhenReady = true
            hdrModeResumePositionMs = null
            return@LaunchedEffect
        }

        if (currentProgram.id == 0 || !isQualitiesLoaded || vs.currentQuality.value.isBlank()) return@LaunchedEffect
        if (availableQualities.isNotEmpty() && availableQualities.none { it.value == vs.currentQuality.value }) return@LaunchedEffect

        isBuffering = true
        val resumePositionMs = resolveRecreatedPlayerStartPositionMs(
            explicitResumePositionMs = hdrModeResumePositionMs,
            isFirstLoad = isFirstLoad,
            retainedPlaybackPositionMs = playbackPositionMs,
        )
        val offsetPositionMs = when {
            resumePositionMs != null -> resumePositionMs
            isFirstLoad && effectiveInitialPositionMs > 0 -> effectiveInitialPositionMs
            else -> getCurrentPositionMs()
        }
        vs.playbackOffsetMs = offsetPositionMs
        val offsetSec = offsetPositionMs / 1000.0

        val requestToken = recordedPlaybackToken
        val url = videoPlayerViewModel.resolveStreamUrl(
            currentProgram.id,
            vs.currentQuality.value,
            currentSessionId,
            offsetSec,
            isRecordingChasePlayback
        )

                    if (!currentPlaybackFence.accepts()) return@LaunchedEffect
        if (url.isNotEmpty()) {
            isWaitingForNetworkRecovery = false
            currentStreamUrlRef.set(url)
            val mediaItem = buildVideoMediaItem(url)
            val startPositionMs = when {
                resumePositionMs != null && (!isLiveStream || isRecordingChasePlayback) ->
                    resumePositionMs
                isFirstLoad && effectiveInitialPositionMs > 0 &&
                    (!isLiveStream || isRecordingChasePlayback) -> effectiveInitialPositionMs
                else -> null
            }
            if (startPositionMs != null) {
                exoPlayer.setMediaItem(mediaItem, startPositionMs)
            } else {
                exoPlayer.setMediaItem(mediaItem)
            }
            isFirstLoad = false
            if (isRecordingChasePlayback) {
                lastChasePlaylistRefreshAt = System.currentTimeMillis()
            }
            exoPlayer.prepare()
            preparedPlaybackKey = playbackKey
            exoPlayer.playWhenReady = true
            hdrModeResumePositionMs = null
        } else {
            if (
                networkRecoveryGate.onFailure(
                    RecordedNetworkRetry.ResolveInitialUrl,
                    networkAvailableRef.get(),
                ) == RecordedNetworkRecoveryDecision.WaitForNetwork
            ) {
                isWaitingForNetworkRecovery = true
                return@LaunchedEffect
            }
            val token = requestToken
            val decision = switchFailureBudget?.onInitialUrlUnavailable()
            when (decision) {
                RecordedSwitchFailureDecision.RetryInitialUrl -> {
                    // A freshly-created recorded session can race the backend's
                    // URL minting. Retry just once; ordinary enter keeps its
                    // existing non-terminal behavior because it has no budget.
                    delay(1_000L)
                    if (currentPlaybackFence.accepts()) initialUrlRetryNonce++
                }
                is RecordedSwitchFailureDecision.Terminal -> {
                    if (token != null && currentPlaybackFence.accepts()) currentTerminalSwitchFailure(token, decision.failure)
                }
                null -> if (fetchedDetail != null) onShowToast("ストリームURLの取得に失敗しました")
                else -> Unit
            }
        }
    }

    LaunchedEffect(
        exoPlayer,
        currentProgram.id,
        smbItem,
        vs.currentQuality.value,
        currentSessionId,
        isRecordingChasePlayback,
        recordedPlaybackToken,
    ) {
        if (
            smbItem != null ||
            !isRecordingChasePlayback ||
            vs.currentQuality.isRawMmts ||
            vs.currentQuality.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE
        ) {
            return@LaunchedEffect
        }
        while (isActive) {
            delay(5_000L)
            if (!exoPlayer.playWhenReady || currentProgram.id == 0 || vs.currentQuality.value.isBlank()) {
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastChasePlaylistRefreshAt < CHASE_PLAYBACK_PLAYLIST_REFRESH_INTERVAL_MS) {
                continue
            }

            val currentPos = getCurrentPositionMs()
            if (currentPos <= 0L) {
                lastChasePlaylistRefreshAt = now
                continue
            }
            val remainingBufferMs = (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0L)
            if (
                exoPlayer.playbackState != Player.STATE_BUFFERING &&
                remainingBufferMs > CHASE_PLAYBACK_REFRESH_BUFFER_THRESHOLD_MS
            ) {
                continue
            }

            lastChasePlaylistRefreshAt = now
            val newUrl = videoPlayerViewModel.resolveStreamUrl(
                currentProgram.id,
                vs.currentQuality.value,
                currentSessionId,
                currentPos / 1000.0,
                isRecordingChasePlayback
            )
            if (!currentPlaybackFence.accepts()) return@LaunchedEffect
            if (newUrl.isNotEmpty()) {
                Log.i(
                    TAG,
                    "Refreshing chase playback playlist. [video=${currentProgram.id}, position_ms=$currentPos]"
                )
                isBuffering = true
                currentStreamUrlRef.set(newUrl)
                exoPlayer.setMediaItem(buildVideoMediaItem(newUrl))
                exoPlayer.prepare()
                exoPlayer.seekTo(currentPos)
                exoPlayer.playWhenReady = true
            }
        }
    }

    LaunchedEffect(exoPlayer, currentProgram.id, smbItem, isLiveStream, isRecordingChasePlayback) {
        if (smbItem != null || currentProgram.id == 0) return@LaunchedEffect
        var lastCheckpointPositionMs = -1L
        while (isActive) {
            delay(WATCH_HISTORY_CHECKPOINT_INTERVAL_MS)
            if (exoPlayer.mediaItemCount == 0) continue
            val positionMs = vs.pendingSeekPositionMs ?: getCurrentPositionMs()
            if (positionMs < 5_000L) continue
            if (
                lastCheckpointPositionMs >= 0L &&
                kotlin.math.abs(positionMs - lastCheckpointPositionMs) < 5_000L
            ) {
                continue
            }
            videoPlayerViewModel.updateWatchHistory(currentProgram, positionMs / 1000.0)
            lastCheckpointPositionMs = positionMs
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

    LaunchedEffect(
        isDataBroadcastingAvailable,
        isDataBroadcastingActive,
        isDataBroadcastingBlank,
        isPiPMode
    ) {
        if ((!isDataBroadcastingAvailable || isPiPMode) && isDataBroadcastingMode) {
            closeDataBroadcasting()
        } else if (!isDataBroadcastingActive || isDataBroadcastingBlank) {
            dataBroadcastingInput.resetDataBroadcastingInput()
        }
    }

    LaunchedEffect(
        isDataBroadcastingActive,
        dataBroadcastingInput.isDataBroadcastingColorSelectorVisible,
        dataBroadcastingInput.selectedDataBroadcastingColorKey
    ) {
        if (
            isDataBroadcastingActive &&
            dataBroadcastingInput.isDataBroadcastingColorSelectorVisible
        ) {
            delay(5_000L)
            dataBroadcastingInput.closeDataBroadcastingColorSelector()
        }
    }

    DisposableEffect(
        currentProgram.recordedVideo.id,
        vs.currentQuality.value,
        currentSessionId,
        smbItem,
        isNetworkAvailable,
        isWaitingForNetworkRecovery,
    ) {
        if (
            isNetworkAvailable &&
            !isWaitingForNetworkRecovery &&
            smbItem == null &&
            !vs.currentQuality.isRawMmts &&
            vs.currentQuality.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
        ) {
            videoPlayerViewModel.startStreamMaintenance(
                currentProgram,
                vs.currentQuality.value,
                currentSessionId
            ) { currentStreamUrlRef.get() }
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
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen &&
            !isProgramInfoOpen && !isModernSettingsOpen && !vs.isSeekBarFocused &&
            vs.lCropMode == LCropMode.HIDDEN
        ) {
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

    val seriesQuickPrograms = remember(currentProgram, recentRecordings) {
        buildRecordedSeriesQuickPrograms(currentProgram, recentRecordings)
    }
    val recentQuickPrograms = remember(currentProgram, recentRecordings) {
        buildRecordedRecentQuickPrograms(currentProgram, recentRecordings)
    }
    val quickMenuSeriesPrograms = remember(
        currentProgram.id,
        seriesQuickPrograms,
        quickVideoCandidates
    ) {
        chooseRecordedQuickPrograms(
            currentProgramId = currentProgram.id,
            fetchedSourceProgramId = quickVideoCandidates.sourceProgramId,
            fetchedPrograms = quickVideoCandidates.seriesPrograms,
            fallbackPrograms = seriesQuickPrograms
        )
    }
    val quickMenuRecentPrograms = remember(
        currentProgram.id,
        recentQuickPrograms,
        quickVideoCandidates
    ) {
        chooseRecordedQuickPrograms(
            currentProgramId = currentProgram.id,
            fetchedSourceProgramId = quickVideoCandidates.sourceProgramId,
            fetchedPrograms = quickVideoCandidates.recentPrograms,
            fallbackPrograms = recentQuickPrograms
        )
    }
    val nextSeriesProgram = remember(currentProgram.id, quickMenuSeriesPrograms) {
        nextRecordedSeriesProgram(currentProgram.id, quickMenuSeriesPrograms)
    }
    val previousSeriesProgram = remember(currentProgram.id, quickMenuSeriesPrograms) {
        previousRecordedSeriesProgram(currentProgram.id, quickMenuSeriesPrograms)
    }
    RecordedSystemMediaSession(
        player = exoPlayer,
        program = currentProgram,
        smbTitle = smbItem?.name,
        artworkUrl = systemArtworkUrl,
        isLoading = isBuffering,
        previousProgram = previousSeriesProgram,
        nextProgram = nextSeriesProgram,
        onProgramSelect = onProgramSelect,
        currentPositionMs = getEffectivePositionMs,
        performSeek = performSeek,
        onStop = onBackPressed
    )
    LaunchedEffect(nextSeriesProgram?.id) {
        nextEpisodeProgramForEnd = nextSeriesProgram
    }
    val refreshQuickMenuVideos: () -> Unit = {
        videoPlayerViewModel.refreshQuickVideoCandidates(currentProgram, recentRecordings)
    }
    val recentRecordingsQuickKey = remember(recentRecordings) {
        recentRecordings.take(24).joinToString(separator = "|") { "${it.id}:${it.recordedVideo.status}" }
    }
    LaunchedEffect(currentProgram.id, recentRecordingsQuickKey, showControls, isSubOverlayOpen) {
        if (showControls || isSubOverlayOpen) {
            videoPlayerViewModel.cancelQuickVideoRefresh()
            return@LaunchedEffect
        }
        delay(QUICK_MENU_REFRESH_DEBOUNCE_MS)
        if (showControls || isSubOverlayOpen) {
            return@LaunchedEffect
        }
        refreshQuickMenuVideos()
    }

    val nextEpisodeCountdownStartMs = remember(
        currentProgram.id,
        currentProgram.channel,
        trustedPlaybackEndDurationMs,
        allComments.size
    ) {
        calculateNextEpisodeCountdownStartMs(
            program = currentProgram,
            comments = allComments,
            totalDurationMs = trustedPlaybackEndDurationMs
        )
    }
    val nextEpisodeCountdownEndMs =
        (nextEpisodeCountdownStartMs + NEXT_EPISODE_COUNTDOWN_WINDOW_MS)
            .coerceAtMost(trustedPlaybackEndDurationMs)
            .coerceAtLeast(nextEpisodeCountdownStartMs)
    val nextEpisodeCountdownRemainingMs =
        (nextEpisodeCountdownEndMs - getEffectivePositionMs()).coerceAtLeast(0L)
    val showNextEpisodeCountdown =
        nextSeriesProgram != null &&
                isNextEpisodeLandingEligible &&
                !isRecordingChasePlayback &&
                !hasAutoStartedNextEpisode &&
                !isNextEpisodeCountdownCancelled &&
                trustedPlaybackEndDurationMs > NEXT_EPISODE_COUNTDOWN_WINDOW_MS &&
                exoPlayer.playbackState == Player.STATE_READY &&
                getEffectivePositionMs() >= nextEpisodeCountdownStartMs
    val nextEpisodeCountdownProgress = recordedCountdownProgress(
        nextEpisodeCountdownStartMs,
        nextEpisodeCountdownEndMs,
        nextEpisodeCountdownRemainingMs
    )

    LaunchedEffect(showNextEpisodeCountdown, nextEpisodeCountdownRemainingMs, nextSeriesProgram?.id) {
        val nextEpisode = nextSeriesProgram
        if (
            showNextEpisodeCountdown &&
            nextEpisodeCountdownRemainingMs <= 500L &&
            !hasAutoStartedNextEpisode &&
            !isNextEpisodeCountdownCancelled
        ) {
            nextEpisode?.let {
                hasAutoStartedNextEpisode = true
                hasHandledPlaybackEnd = true
                onProgramSelect(it, RecordedProgramSelectionReason.NextEpisode)
            }
        }
    }
    val playNextEpisodeNow: () -> Unit = {
        val nextEpisode = nextSeriesProgram
        if (nextEpisode != null && !hasAutoStartedNextEpisode) {
            hasAutoStartedNextEpisode = true
            hasHandledPlaybackEnd = true
            onProgramSelect(nextEpisode, RecordedProgramSelectionReason.NextEpisode)
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

    val toggleAudio: () -> Unit = {
        toggleRecordedAudio(vs, onShowToast)
    }
    val toggleSpeed: () -> Unit = {
        cycleRecordedPlaybackSpeed(vs, exoPlayer, onShowToast)
    }
    val toggleSubtitle: () -> Unit = {
        toggleRecordedSubtitle(vs, onShowToast)
    }
    val toggleSubtitleLanguage: () -> Unit = {
        val (languageId, message) = nextRecordedSubtitleLanguage(
            currentSubtitleLanguageId,
            subtitleLanguages
        )
        currentSubtitleLanguageId = languageId
        onShowToast(message)
    }
    val selectQuality: (StreamQuality) -> Unit = selectQuality@ { quality ->
        if (smbItem != null) {
            onShowToast("SMB再生中は画質の変更はできません")
            isModernSettingsOpen = false
            onSubMenuToggle(false)
            return@selectQuality
        }
        if (vs.currentQuality != quality) {
            vs.playbackOffsetMs = getCurrentPositionMs()
            vs.currentQuality = quality
            if (
                !quality.isRawMmts &&
                quality.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
            ) {
                videoPlayerViewModel.saveVideoQuality(quality.value)
            }
            val player = exoPlayer
            val currentPosition = getCurrentPositionMs()
            if (!isEdcbDirect) {
                vs.playbackOffsetMs = currentPosition - effectiveInitialPositionMs
            }
            scope.launch {
                val actionFence = recordedPlaybackFence
                isBuffering = true
                val newUrl = videoPlayerViewModel.resolveStreamUrl(
                    currentProgram.id,
                    quality.value,
                    currentSessionId,
                    if (isEdcbDirect) 0.0 else currentPosition / 1000.0,
                    isRecordingChasePlayback
                )
                if (!actionFence.accepts()) return@launch
                currentStreamUrlRef.set(newUrl)
                player.setMediaItem(buildVideoMediaItem(newUrl))
                player.prepare()
                if (
                    isEdcbDirect ||
                    quality.isRawMmts ||
                    quality.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE ||
                    isRecordingChasePlayback
                ) {
                    player.seekTo(currentPosition)
                }
                player.play()
            }
            onShowToast("画質を ${quality.label} に変更しました")
        }
        isModernSettingsOpen = false
        onSubMenuToggle(false)
        vs.lastInteractionTime = System.currentTimeMillis()
    }
    val toggleComment: () -> Unit = {
        toggleRecordedComment(vs, onShowToast)
    }
    val toggleLCrop: () -> Unit = {
        toggleRecordedLCrop(vs) {
            onSubMenuToggle(false)
            onShowControlsChange(false)
        }
    }
    val toggleCmSkipMode: () -> Unit = {
        val nextMode = cmSkipMode.next()
        settingsViewModel.setCmSkipMode(nextMode)
        onShowToast("CMスキップ: ${nextMode.displayLabel}")
    }
    val toggleHdrRenderMode: () -> Unit = {
        val nextMode = nextRecordedHdrRenderMode(hdrRenderMode)
        hdrModeResumePositionMs = getCurrentPositionMs()
        playbackPositionMs = hdrModeResumePositionMs ?: playbackPositionMs
        videoPlayerViewModel.setHdrRenderMode(nextMode)
        onShowToast(recordedHdrRenderModeMessage(nextMode))
    }

    BackHandler(enabled = isPiPMode) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                handleRecordedPlayerKeyEvent(
                    keyEvent = keyEvent,
                    isPiPMode = isPiPMode,
                    isDataBroadcastingAvailable = isDataBroadcastingAvailable,
                    isDataBroadcastingActive = isDataBroadcastingActive,
                    isDataBroadcastingBlank = isDataBroadcastingBlank,
                    dataBroadcastingInput = dataBroadcastingInput,
                    scope = scope,
                    dispatchDataBroadcastingRemoteKey = dispatchDataBroadcastingRemoteKey,
                    dispatchDataBroadcastingColorKey = dispatchDataBroadcastingColorKey,
                    openDataBroadcasting = openDataBroadcasting,
                    onShowToast = onShowToast,
                    isProgramInfoOpen = isProgramInfoOpen,
                    openProgramInfo = openProgramInfo,
                    closeProgramInfo = closeProgramInfo,
                    isSubOverlayOpen = isSubOverlayOpen,
                    state = vs,
                    cmSkipMode = cmSkipMode,
                    currentPositionMs = getCurrentPositionMs,
                    chapters = chapters,
                    showControls = showControls,
                    showNextEpisodeCountdown = showNextEpisodeCountdown,
                    armedManualCmSkipTargetMs = armedManualCmSkipTargetMs,
                    onArmedManualCmSkipTargetChange = {
                        armedManualCmSkipTargetMs = it
                    },
                    performSeek = performSeek,
                    isModern = isModern,
                    canOpenSceneSearch = canOpenSceneSearch,
                    totalDurationMs = totalDurationForControls,
                    triggerSeekingPreview = triggerSeekingPreview,
                    onShowControlsChange = onShowControlsChange,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                    onSceneSearchToggle = onSceneSearchToggle,
                    onSettingsMenuToggle = {
                        isModernSettingsOpen = true
                        onShowControlsChange(true)
                    },
                    onChapterListToggle = { isChapterListOpen = it },
                    onSubMenuToggle = onSubMenuToggle,
                    onQuickMenuRequested = refreshQuickMenuVideos,
                    exoPlayerIsPlaying = exoPlayer.playWhenReady,
                    onPause = exoPlayer::pause,
                    onPlay = exoPlayer::play
                )
            }
    ) {
        RecordedMediaSurface(
            isDataBroadcastingActive = isDataBroadcastingActive,
            dataBroadcastingStore = dataBroadcastingStore,
            dataBroadcastingChannel = dataBroadcastingChannel,
            exoPlayer = exoPlayer,
            dataBroadcastingRemoteCommand = dataBroadcastingRemoteCommand,
            onRemoteCommandConsumed = { consumedId ->
                if (dataBroadcastingRemoteCommand?.id == consumedId) {
                    dataBroadcastingRemoteCommand = null
                }
            },
            onMediaPlane = { dataBroadcastingMediaPlane = it },
            onBlankModeChanged = { isDataBroadcastingBlank = it },
            closeDataBroadcasting = closeDataBroadcasting,
            dataBroadcastingMediaPlane = dataBroadcastingMediaPlane,
            isDataBroadcastingBlank = isDataBroadcastingBlank,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pixelWidthHeightRatio = pixelWidthHeightRatio,
            state = vs,
            renderedFrameGeneration = renderedFrameGeneration,
            mainFocusRequester = mainFocusRequester,
            isPiPMode = isPiPMode,
            isSubOverlayOpen = isSubOverlayOpen
        )

        if (!isPiPMode) {
            RecordedPlaybackOverlays(
                isHeavyUiReady = isHeavyUiReady,
                state = vs,
                comments = allComments,
                currentPositionMs = getEffectivePositionMs,
                commentPositionMs = getCurrentPositionMs,
                commentSpeed = commentSpeed,
                commentFontSizeScale = commentFontSizeScale,
                commentOpacity = commentOpacity,
                commentMaxLines = commentMaxLines,
                isEmulator = isEmulator,
                recordedPlaybackFence = recordedPlaybackFence,
                subtitleCue = subtitleCue.value,
                superimposeCue = superimposeCue.value,
                isSubtitleBlockingOverlayOpen = isSubtitleBlockingOverlayOpen,
                subtitleOffset = subtitleOffset,
                subtitleAvoidanceStartFraction =
                    PLAYER_CONTROLS_SUBTITLE_AVOIDANCE_START_FRACTION,
                subtitleCommentLayer = subtitleCommentLayer,
                isBuffering = isBuffering,
                onLCropClose = {
                    closeRecordedLCrop(vs)
                    scope.launch {
                        delay(200)
                        mainFocusRequester.safeRequestFocus(TAG)
                    }
                },
                cmSkipMode = cmSkipMode,
                isDataBroadcastingActive = isDataBroadcastingActive,
                nextCountdownProgram = nextSeriesProgram,
                showNextEpisodeCountdown = showNextEpisodeCountdown,
                nextEpisodeCountdownProgress = nextEpisodeCountdownProgress,
                onPlayNextEpisodeNow = playNextEpisodeNow,
                onCancelNextEpisodeCountdown = cancelNextEpisodeCountdown,
                program = currentProgram,
                timeFormat = timeFormat,
                tiledThumbnailUrl = tiledThumbnailUrl,
                showControls = showControls,
                isSubOverlayOpen = isSubOverlayOpen,
                isSeekingPreviewVisible = isSeekingPreviewVisible,
                isModern = isModern,
                isPlaying = exoPlayer.playWhenReady,
                chapters = chapters,
                totalDurationMs = totalDurationForControls,
                bufferedPositionMs = bufferedPositionMs,
                playerControlsFocusRequester = playerControlsFocusRequester,
                onSeekBarFocusChanged = { vs.isSeekBarFocused = it },
                onPlayPauseToggle = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    vs.togglePlayPause(exoPlayer.playWhenReady)
                    if (exoPlayer.playWhenReady) exoPlayer.pause() else exoPlayer.play()
                },
                onSeekBack = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    performSeek((getEffectivePositionMs() - 10_000).coerceAtLeast(0L))
                },
                onSeekForward = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    performSeek(
                        (getEffectivePositionMs() + 30_000)
                            .coerceAtMost(totalDurationForControls)
                    )
                },
                onSeekRequested = performSeek,
                onSkipPreviousChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToPreviousChapter()
                },
                onSkipNextChapter = {
                    vs.lastInteractionTime = System.currentTimeMillis()
                    skipToNextChapter()
                },
                canOpenKeyframeGrid = canOpenSceneSearch,
                onOpenKeyframeGrid = openKeyframeGrid,
                onOpenChapterList = {
                    isChapterListOpen = true
                    onShowControlsChange(true)
                },
                onOpenProgramInfo = openProgramInfo,
                onOpenSettings = {
                    if (isModern) {
                        isModernSettingsOpen = true
                    } else {
                        onSubMenuToggle(true)
                    }
                },
                isProgramInfoOpen = isProgramInfoOpen,
                onCloseProgramInfo = closeProgramInfo,
                isSceneSearchOpen = isSceneSearchOpen,
                onCloseSceneSearch = { onSceneSearchToggle(false) },
                isChapterListOpen = isChapterListOpen,
                onCloseChapterList = { isChapterListOpen = false },
                isKeyframeGridOpen = isKeyframeGridOpen,
                onCloseKeyframeGrid = { isKeyframeGridOpen = false }
            )

            RecordedPlayerMenus(
                isModernSettingsOpen = isModernSettingsOpen,
                program = currentProgram,
                seriesPrograms = quickMenuSeriesPrograms,
                quickPrograms = quickMenuRecentPrograms,
                animeChannels = animeChannels,
                backendType = backendType,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                state = vs,
                subtitleLanguages = subtitleLanguages,
                currentSubtitleLanguageId = currentSubtitleLanguageId,
                cmSkipMode = cmSkipMode,
                hdrRenderMode = hdrRenderMode,
                isHdrRenderModeSupported = isHdrRenderModeSupported,
                isDataBroadcastingAvailable = isDataBroadcastingAvailable,
                isDataBroadcastingActive = isDataBroadcastingActive,
                availableQualities = availableQualities,
                subMenuFocusRequester = subMenuFocusRequester,
                onAudioToggle = toggleAudio,
                onSpeedToggle = toggleSpeed,
                onSubtitleToggle = toggleSubtitle,
                onSubtitleLanguageToggle = toggleSubtitleLanguage,
                onQualitySelect = selectQuality,
                onCommentToggle = toggleComment,
                onLCropToggle = toggleLCrop,
                onCmSkipModeToggle = toggleCmSkipMode,
                onHdrRenderModeToggle = toggleHdrRenderMode,
                onDataBroadcastingToggle = openDataBroadcasting,
                onVideoSelect = { selectedProgram ->
                    if (selectedProgram.id != currentProgram.id) {
                        onProgramSelect(
                            selectedProgram,
                            RecordedProgramSelectionReason.QuickSelect
                        )
                    }
                },
                onChannelSelect = onChannelSelect,
                canOpenKeyframeGrid = canOpenSceneSearch,
                onOpenKeyframeGrid = openKeyframeGrid,
                openQuickVideosInitially = openQuickVideosOnSubMenuOpen,
                isSubMenuOpen = isSubMenuOpen,
                onCloseModernSettings = { isModernSettingsOpen = false },
                onCloseSubMenu = { onSubMenuToggle(false) },
                isModern = isModern
            )
        }

        RecordedDataBroadcastingColorSelector(
            visible = !isPiPMode &&
                isDataBroadcastingActive &&
                dataBroadcastingInput.isDataBroadcastingColorSelectorVisible,
            selectedKey = dataBroadcastingInput.selectedDataBroadcastingColorKey,
            onColorSelected = { colorKey ->
                dataBroadcastingInput.dispatchDataBroadcastingColorKey(
                    colorKey,
                    dispatchDataBroadcastingColorKey
                )
            },
            onDismiss = dataBroadcastingInput::closeDataBroadcastingColorSelector
        )
    }
}
