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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.media.SystemMediaSession
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.subtitle.rememberNativeCaptionCue
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TitleNormalizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel as CommentChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.roundToInt
import java.util.UUID

private const val TAG = "VideoPlayerScreen"
private const val PLAYBACK_END_FALLBACK_WINDOW_MS = 10_000L
private const val PLAYBACK_END_FALLBACK_GRACE_MS = 750L
private const val CHASE_PLAYBACK_TARGET_LIVE_OFFSET_MS = 30_000L
private const val CHASE_PLAYBACK_MIN_LIVE_OFFSET_MS = 20_000L
private const val CHASE_PLAYBACK_MAX_LIVE_OFFSET_MS = 60_000L
private const val CHASE_PLAYBACK_PLAYLIST_REFRESH_INTERVAL_MS = 45_000L
private const val CHASE_PLAYBACK_REFRESH_BUFFER_THRESHOLD_MS = 6_000L
private const val NEXT_EPISODE_COUNTDOWN_WINDOW_MS = 15_000L
private const val ATX_NEXT_EPISODE_TRIGGER_MS = 26 * 60 * 1000L
private const val QUICK_MENU_REFRESH_DEBOUNCE_MS = 2_500L
private const val CHASE_COMMENT_QUEUE_CAPACITY = 256
private const val ACTIVE_PLAYBACK_STATE_POLL_MS = 250L
private const val IDLE_PLAYBACK_STATE_POLL_MS = 1_000L
private const val THIRTY_MINUTE_RECORDING_MIN_MS = 27 * 60 * 1000L
private const val THIRTY_MINUTE_RECORDING_MAX_MS = 36 * 60 * 1000L
private const val COMMENT_CLIMAX_WINDOW_START_MS = 25 * 60 * 1000L
private const val COMMENT_CLIMAX_WINDOW_END_MS = 30 * 60 * 1000L
private const val COMMENT_DENSITY_BUCKET_MS = 10_000L
private const val COMMENT_CLIMAX_LEAD_MS = 10_000L
private const val MIN_PROGRAM_COMMENTS_FOR_CLIMAX = 80
private const val MIN_CLIMAX_COMMENTS = 20
private const val WATCH_HISTORY_CHECKPOINT_INTERVAL_MS = 15_000L
private const val MIN_DENSE_BUCKET_COMMENTS = 5
private const val MIN_CLIMAX_WINDOW_COMMENT_RATIO = 0.08f
private const val MIN_CLIMAX_PEAK_TO_BASELINE_RATIO = 2.0f
private const val MIN_C_PART_SIGNAL_COMMENTS = 3
private const val C_PART_SIGNAL_CLUSTER_WINDOW_MS = 30_000L
private const val LATE_C_PART_WINDOW_START_MS = 28 * 60 * 1000L + 30_000L
private const val MIN_LATE_C_PART_COMMENTS = 8
private const val MIN_LATE_C_PART_PEAK_RATIO = 1.6f
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
    val preferOriginalMpegTs by settingsViewModel.preferOriginalMpegTs.collectAsState()

    val isModern = false
    var isBuffering by remember { mutableStateOf(true) }

    LaunchedEffect(program.id) {
        currentProgram = program
        if (smbItem == null) {
            videoPlayerViewModel.fetchProgramDetail(program.id)
            videoPlayerViewModel.fetchAvailableQualities(program)
        }
    }

    LaunchedEffect(fetchedDetail) {
        if (fetchedDetail != null && fetchedDetail?.id == program.id) {
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
    val requiresRawMmtsPlayback = currentProgram.requiresRawMmtsPlayback

    val vs = rememberVideoPlayerState()

    val autoCmSkipStr by settingsViewModel.autoCmSkip.collectAsState()
    LaunchedEffect(autoCmSkipStr) {
        vs.isAutoCmSkipEnabled = (autoCmSkipStr == "ON")
    }

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
    val commentKeys = remember(currentProgram.id, isRecordingChasePlayback) {
        HashSet<String>()
    }
    val pendingWebSocketComments = remember(currentProgram.id, isRecordingChasePlayback) {
        CommentChannel<ArchivedComment>(
            capacity = CHASE_COMMENT_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    var currentSessionId by remember(currentProgram.id, vs.currentQuality.value, isRecordingChasePlayback) {
        mutableStateOf(UUID.randomUUID().toString())
    }
    val currentStreamUrlRef = remember(currentProgram.id) { AtomicReference<String?>(null) }
    val subtitleEvents = remember {
        MutableSharedFlow<NativeCaptionCue>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    var subtitleLanguages by remember(currentProgram.id) {
        mutableStateOf(emptyList<NativeCaptionLanguage>())
    }
    var currentSubtitleLanguageId by remember(currentProgram.id) { mutableIntStateOf(1) }
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

    LaunchedEffect(currentProgram.recordedVideo.id, smbItem, isRecordingChasePlayback) {
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

    LaunchedEffect(pendingWebSocketComments) {
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

    LaunchedEffect(currentProgram.id, smbItem, isRecordingChasePlayback) {
        if (smbItem != null || !isRecordingChasePlayback) return@LaunchedEffect
        val programStartUnix = currentProgram.programStartUnixOrNull() ?: return@LaunchedEffect
        val watchSessionUrl = videoPlayerViewModel.getChaseJikkyoWatchSessionUrl(currentProgram)
            ?: return@LaunchedEffect
        val processedCommentKeys = mutableSetOf<String>()
        var receivedCommentCount = 0
        val client = JikkyoClient(watchSessionUrl)

        try {
            client.start { jsonText ->
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
        onStreamSessionExpired = { player ->
            if (smbItem != null || currentProgram.id == 0 || vs.currentQuality.value.isBlank()) {
                return@rememberManagedExoPlayer false
            }

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
                currentProgram.id,
                vs.currentQuality.value,
                newSessionId,
                resumePositionMs / 1000.0,
                isRecordingChasePlayback
            )
            if (newUrl.isEmpty()) {
                return@rememberManagedExoPlayer false
            }

            Log.i(
                TAG,
                "Recovered expired stream session for video=${currentProgram.id}, quality=${vs.currentQuality.value}"
            )
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
        },
        onStopOrDispose = { player ->
            // 画質の初期化中に作られた空の Player は、Raw MMTS Player への
            // 再構築時に dispose される。ここで0秒を書くとレジューム位置が消える。
            if (smbItem == null && player.mediaItemCount > 0) {
                val rawPosition = player.currentPosition
                val playerPosition = if (rawPosition == C.TIME_UNSET || rawPosition < 0L) {
                    playbackPositionMs
                } else {
                    rawPosition
                }
                val posMs = if (isLiveStream && !isRecordingChasePlayback) {
                    vs.playbackOffsetMs + playerPosition
                } else {
                    playerPosition
                }.coerceAtLeast(0L)
                videoPlayerViewModel.updateWatchHistory(currentProgram, posMs / 1000.0)
            }
        }
    )

    val subtitleCue = rememberNativeCaptionCue(
        events = subtitleEvents,
        enabled = vs.isSubtitleEnabled,
        resetKey = currentProgram.id to currentSubtitleLanguageId,
        clockRunning = vs.isPlayerPlaying,
        positionMsProvider = { exoPlayer.currentPosition.coerceAtLeast(0L) }
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

    val playbackStatePollIntervalMs = if (showControls || isSeekingPreviewVisible) {
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
                    currentStreamUrlRef.set(newUrl)
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

    LaunchedEffect(currentProgram.id, smbItem?.path) {
        isFirstLoad = true
        preparedPlaybackKey = null
        currentStreamUrlRef.set(null)
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
            val startPositionMs = effectiveInitialPositionMs.takeIf {
                isFirstLoad && it > 0L
            }
            if (startPositionMs != null) {
                exoPlayer.setMediaItem(mediaItem, startPositionMs)
            } else {
                exoPlayer.setMediaItem(mediaItem)
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
            currentStreamUrlRef.set(url)
            val mediaItem = buildVideoMediaItem(url)
            val startPositionMs = effectiveInitialPositionMs.takeIf {
                isFirstLoad && it > 0L && (!isLiveStream || isRecordingChasePlayback)
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
        } else {
            if (fetchedDetail != null) onShowToast("ストリームURLの取得に失敗しました")
        }
    }

    LaunchedEffect(
        exoPlayer,
        currentProgram.id,
        smbItem,
        vs.currentQuality.value,
        currentSessionId,
        isRecordingChasePlayback
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
            if (!exoPlayer.isPlaying || exoPlayer.mediaItemCount == 0) continue
            val positionMs = getCurrentPositionMs()
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

    DisposableEffect(currentProgram.recordedVideo.id, vs.currentQuality.value, currentSessionId, smbItem) {
        if (
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
    val previousSeriesProgram = remember(currentProgram.id, quickMenuSeriesPrograms) {
        val newestFirst = quickMenuSeriesPrograms
            .distinctBy { it.id }
            .sortedByDescending { it.startTime }
        val currentIndex = newestFirst.indexOfFirst { it.id == currentProgram.id }
        if (currentIndex >= 0 && currentIndex + 1 < newestFirst.size) {
            newestFirst[currentIndex + 1]
        } else null
    }
    SystemMediaSession(
        player = exoPlayer,
        title = smbItem?.name ?: currentProgram.title,
        subtitle = currentProgram.channel?.name,
        artworkUrl = systemArtworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_TV_SHOW,
        onPrevious = previousSeriesProgram?.let { target -> { onProgramSelect(target) } },
        onNext = nextSeriesProgram?.let { target -> { onProgramSelect(target) } },
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
        totalDurationForControls,
        allComments.size
    ) {
        calculateNextEpisodeCountdownStartMs(
            program = currentProgram,
            comments = allComments,
            totalDurationMs = totalDurationForControls
        )
    }
    val nextEpisodeCountdownEndMs =
        (nextEpisodeCountdownStartMs + NEXT_EPISODE_COUNTDOWN_WINDOW_MS)
            .coerceAtMost(totalDurationForControls)
            .coerceAtLeast(nextEpisodeCountdownStartMs)
    val nextEpisodeCountdownRemainingMs =
        (nextEpisodeCountdownEndMs - getEffectivePositionMs()).coerceAtLeast(0L)
    val showNextEpisodeCountdown =
        nextSeriesProgram != null &&
                isNextEpisodeLandingEligible &&
                !isRecordingChasePlayback &&
                !hasAutoStartedNextEpisode &&
                !isNextEpisodeCountdownCancelled &&
                totalDurationForControls > NEXT_EPISODE_COUNTDOWN_WINDOW_MS &&
                getEffectivePositionMs() >= nextEpisodeCountdownStartMs
    val nextEpisodeCountdownProgress =
        if (nextEpisodeCountdownEndMs <= nextEpisodeCountdownStartMs) {
            1f
        } else {
            1f - (
                    nextEpisodeCountdownRemainingMs.toFloat() /
                            (nextEpisodeCountdownEndMs - nextEpisodeCountdownStartMs).toFloat()
                    )
        }
            .coerceIn(0f, 1f)

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

    BackHandler(enabled = isPiPMode) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { keyEvent ->
                if (isSubOverlayOpen) {
                    return@onPreviewKeyEvent false
                }

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
            onRelease = { view ->
                exoPlayer.clearVideoSurfaceView(view.getChildAt(0) as SurfaceView)
                view.keepScreenOn = false
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
                        visible = vs.isSubtitleEnabled && !isSubtitleBlockingOverlayOpen,
                        modifier = Modifier.fillMaxSize(),
                        bottomAvoidanceOffset = subtitleOffset,
                        bottomAvoidanceStartFraction = PLAYER_CONTROLS_SUBTITLE_AVOIDANCE_START_FRACTION
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
                            if (
                                !it.isRawMmts &&
                                it.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
                            ) {
                                videoPlayerViewModel.saveVideoQuality(it.value)
                            }
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
                                    ); currentStreamUrlRef.set(newUrl); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare(); player.seekTo(
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
                                    ); currentStreamUrlRef.set(newUrl); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare()
                                    if (
                                        it.isRawMmts ||
                                        it.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE ||
                                        isRecordingChasePlayback
                                    ) {
                                        player.seekTo(currentPos)
                                    }
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

            Box(modifier = Modifier.fillMaxSize()) {
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
                    subtitleLanguages = subtitleLanguages,
                    currentSubtitleLanguageId = currentSubtitleLanguageId,
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
                    onSubtitleLanguageToggle = {
                        currentSubtitleLanguageId = if (currentSubtitleLanguageId == 1) 2 else 1
                        val selectedLanguage = subtitleLanguages.firstOrNull { it.id == currentSubtitleLanguageId }
                        onShowToast(
                            "字幕言語: 第${currentSubtitleLanguageId}言語" +
                                (selectedLanguage?.let { "・${it.displayName}" } ?: "")
                        )
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
                            if (
                                !it.isRawMmts &&
                                it.value != StreamQuality.ORIGINAL_MPEG_TS_VALUE
                            ) {
                                videoPlayerViewModel.saveVideoQuality(it.value)
                            }
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
                                    ); currentStreamUrlRef.set(newUrl); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare(); player.seekTo(
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
                                    ); currentStreamUrlRef.set(newUrl); player.setMediaItem(buildVideoMediaItem(newUrl)); player.prepare()
                                    if (
                                        it.isRawMmts ||
                                        it.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE ||
                                        isRecordingChasePlayback
                                    ) {
                                        player.seekTo(currentPos)
                                    }
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
                    isVisible = isSubMenuOpen,
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

private fun isNextEpisodeLandingEligible(program: RecordedProgram): Boolean =
    program.genres.orEmpty().any { genre ->
        val label = "${genre.major} ${genre.middle}".lowercase()
        label.contains("アニメ") ||
                label.contains("anime") ||
                label.contains("特撮") ||
                label.contains("tokusatsu") ||
                label.contains("ドラマ") ||
                label.contains("drama")
    }

private fun calculateNextEpisodeCountdownStartMs(
    program: RecordedProgram,
    comments: List<ArchivedComment>,
    totalDurationMs: Long
): Long {
    val fallbackStartMs =
        (totalDurationMs - NEXT_EPISODE_COUNTDOWN_WINDOW_MS).coerceAtLeast(0L)
    if (totalDurationMs <= NEXT_EPISODE_COUNTDOWN_WINDOW_MS) return fallbackStartMs

    if (hasCPartSignal(comments, totalDurationMs)) {
        return fallbackStartMs
    }

    if (
        isAtxChannel(program.channel) &&
        isApproximatelyThirtyMinuteRecording(program, totalDurationMs) &&
        ATX_NEXT_EPISODE_TRIGGER_MS < totalDurationMs
    ) {
        return ATX_NEXT_EPISODE_TRIGGER_MS
    }

    return calculateCommentClimaxCountdownStartMs(comments, totalDurationMs)
        ?.takeIf { it < totalDurationMs }
        ?.coerceIn(0L, totalDurationMs)
        ?: fallbackStartMs
}

private fun isAtxChannel(channel: RecordedChannel?): Boolean {
    if (channel == null) return false
    val textSignals = listOf(
        channel.id,
        channel.displayChannelId,
        channel.name,
        channel.channelNumber
    )
    return channel.serviceId == 333 ||
            textSignals.any { value ->
                value.contains("AT-X", ignoreCase = true) ||
                        value.contains("CS333", ignoreCase = true) ||
                        value == "333" ||
                        value.endsWith("333")
            }
}

private fun isApproximatelyThirtyMinuteRecording(
    program: RecordedProgram,
    totalDurationMs: Long
): Boolean {
    val candidates = listOf(
        totalDurationMs,
        (program.duration * 1000.0).toLong(),
        (program.recordedVideo.duration * 1000.0).toLong()
    )
    return candidates.any { durationMs ->
        durationMs in THIRTY_MINUTE_RECORDING_MIN_MS..THIRTY_MINUTE_RECORDING_MAX_MS
    }
}

private fun hasCPartSignal(
    comments: List<ArchivedComment>,
    totalDurationMs: Long
): Boolean {
    val windowStartMs = COMMENT_CLIMAX_WINDOW_START_MS
    val windowEndMs = minOf(COMMENT_CLIMAX_WINDOW_END_MS, totalDurationMs)
    if (windowEndMs <= windowStartMs) return false

    val cPartTimes = comments
        .asSequence()
        .filter { isCPartComment(it.text) }
        .map { (it.time * 1000.0).toLong() }
        .filter { it in windowStartMs until windowEndMs }
        .sorted()
        .toList()
    if (hasCluster(cPartTimes, MIN_C_PART_SIGNAL_COMMENTS, C_PART_SIGNAL_CLUSTER_WINDOW_MS)) {
        return true
    }

    val lateWindowStartMs = maxOf(LATE_C_PART_WINDOW_START_MS, windowStartMs)
    if (windowEndMs <= lateWindowStartMs) return false
    val lateComments = comments
        .asSequence()
        .map { (it.time * 1000.0).toLong() }
        .filter { it in lateWindowStartMs until windowEndMs }
        .toList()
    if (lateComments.size < MIN_LATE_C_PART_COMMENTS) return false

    val lateBucketCount =
        ceil((windowEndMs - lateWindowStartMs).toDouble() / COMMENT_DENSITY_BUCKET_MS.toDouble())
            .toInt()
            .coerceAtLeast(1)
    val buckets = IntArray(lateBucketCount)
    lateComments.forEach { commentMs ->
        val index = ((commentMs - lateWindowStartMs) / COMMENT_DENSITY_BUCKET_MS)
            .toInt()
            .coerceIn(0, lateBucketCount - 1)
        buckets[index] += 1
    }
    val peak = buckets.maxOrNull() ?: return false
    if (peak < MIN_LATE_C_PART_COMMENTS) return false

    val baseline = buckets
        .filter { it > 0 }
        .average()
        .takeIf { !it.isNaN() } ?: return false
    return peak >= (baseline * MIN_LATE_C_PART_PEAK_RATIO).roundToInt()
}

private fun hasCluster(
    sortedTimesMs: List<Long>,
    minCount: Int,
    clusterWindowMs: Long
): Boolean {
    if (sortedTimesMs.size < minCount) return false
    var startIndex = 0
    for (endIndex in sortedTimesMs.indices) {
        while (sortedTimesMs[endIndex] - sortedTimesMs[startIndex] > clusterWindowMs) {
            startIndex += 1
        }
        if (endIndex - startIndex + 1 >= minCount) return true
    }
    return false
}

private fun isCPartComment(text: String): Boolean {
    val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[\\s　]+"), "")
    if (normalized.isBlank()) return false
    val stripped = normalized.trim { char ->
        char == '!' || char == '?' || char == '！' || char == '？' ||
                char == '。' || char == '.' || char == ',' || char == '、' ||
                char == '-' || char == '_' || char == '~' || char == '〜'
    }
    if (stripped == "c") return true
    return stripped.contains("cpart") ||
            stripped.contains("partc") ||
            stripped.contains("cパート") ||
            stripped.contains("パートc") ||
            stripped.contains("cぱーと") ||
            stripped.contains("ぱーとc")
}

private fun calculateCommentClimaxCountdownStartMs(
    comments: List<ArchivedComment>,
    totalDurationMs: Long
): Long? {
    val windowStartMs = COMMENT_CLIMAX_WINDOW_START_MS
    val windowEndMs = minOf(COMMENT_CLIMAX_WINDOW_END_MS, totalDurationMs)
    if (windowEndMs <= windowStartMs) return null

    val totalCommentCount = comments.count { comment ->
        val commentMs = (comment.time * 1000.0).toLong()
        commentMs in 0 until totalDurationMs
    }
    if (totalCommentCount < MIN_PROGRAM_COMMENTS_FOR_CLIMAX) return null

    val commentsInWindow = comments
        .asSequence()
        .map { (it.time * 1000.0).toLong() }
        .filter { it in windowStartMs until windowEndMs }
        .toList()
    val minWindowComments = maxOf(
        MIN_CLIMAX_COMMENTS,
        (totalCommentCount * MIN_CLIMAX_WINDOW_COMMENT_RATIO).roundToInt()
    )
    if (commentsInWindow.size < minWindowComments) return null

    val bucketCount =
        ceil((windowEndMs - windowStartMs).toDouble() / COMMENT_DENSITY_BUCKET_MS.toDouble())
            .toInt()
            .coerceAtLeast(1)
    val buckets = IntArray(bucketCount)
    commentsInWindow.forEach { commentMs ->
        val index = ((commentMs - windowStartMs) / COMMENT_DENSITY_BUCKET_MS)
            .toInt()
            .coerceIn(0, bucketCount - 1)
        buckets[index] += 1
    }

    val maxBucket = buckets.maxOrNull() ?: return null
    if (maxBucket < MIN_DENSE_BUCKET_COMMENTS) return null

    val nonEmptyBuckets = buckets.filter { it > 0 }.sorted()
    val averageNonEmptyBucket = nonEmptyBuckets.average().takeIf { !it.isNaN() } ?: 0.0
    val medianNonEmptyBucket = nonEmptyBuckets
        .takeIf { it.isNotEmpty() }
        ?.let { it[it.size / 2].toDouble() }
        ?: 0.0
    val relativeBaseline = maxOf(averageNonEmptyBucket, medianNonEmptyBucket)
    if (
        relativeBaseline > 0.0 &&
        maxBucket < (relativeBaseline * MIN_CLIMAX_PEAK_TO_BASELINE_RATIO).roundToInt()
    ) {
        return null
    }

    val denseThreshold = maxOf(
        MIN_DENSE_BUCKET_COMMENTS,
        (maxBucket * 0.45).roundToInt(),
        (relativeBaseline * 1.5).roundToInt()
    )
    val sparseThreshold = maxOf(
        1,
        (maxBucket * 0.25).roundToInt(),
        (relativeBaseline * 0.7).roundToInt()
    )

    val peakIndex = buckets.indices
        .filter { buckets[it] >= denseThreshold }
        .maxWithOrNull(
            compareBy<Int> { buckets[it] }
                .thenBy { it }
        ) ?: return null

    var regionEnd = peakIndex
    while (regionEnd + 1 < buckets.size) {
        val nextCount = buckets[regionEnd + 1]
        if (nextCount < sparseThreshold) {
            break
        }
        regionEnd += 1
    }

    val climaxRegionEndMs = minOf(
        windowStartMs + ((regionEnd + 1) * COMMENT_DENSITY_BUCKET_MS),
        windowEndMs
    )
    return (climaxRegionEndMs - COMMENT_CLIMAX_LEAD_MS).coerceAtLeast(0L)
}

private fun ArchivedComment.stableCommentKey(): String =
    "${time}:${author}:${type}:${size}:${color}:${text}"

private fun appendUniqueArchivedComments(
    target: MutableList<ArchivedComment>,
    knownKeys: MutableSet<String>,
    candidates: List<ArchivedComment>
) {
    if (candidates.isEmpty()) return

    val newComments = candidates
        .asSequence()
        .filter { knownKeys.add(it.stableCommentKey()) }
        .sortedBy { it.time }
        .toList()
    if (newComments.isEmpty()) return

    if (target.isEmpty() || target.last().time <= newComments.first().time) {
        target.addAll(newComments)
    } else {
        val mergedComments = (target + newComments).sortedBy { it.time }
        target.clear()
        target.addAll(mergedComments)
    }
}

private fun RecordedProgram.chaseElapsedDurationMs(nowMillis: Long = System.currentTimeMillis()): Long {
    return runCatching {
        val startMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val endMs = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        (nowMillis.coerceAtMost(endMs) - startMs).coerceAtLeast(0L)
    }.getOrDefault((duration * 1000.0).toLong().coerceAtLeast(0L))
}

private fun RecordedProgram.programStartUnixOrNull(): Long? =
    runCatching { OffsetDateTime.parse(startTime).toEpochSecond() }.getOrNull()

private fun parseChaseWsArchivedComment(jsonText: String, programStartUnix: Long): ArchivedComment? {
    return runCatching {
        val chat = JSONObject(jsonText).optJSONObject("chat") ?: return null
        val content = chat.optString("content", "")
        if (content.isBlank()) return null
        if (chat.optString("deleted") == "1") return null
        if (content.startsWith("/") &&
            content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*")) &&
            chat.optString("premium") == "3"
        ) {
            return null
        }

        var color = "#FFEAEA"
        var position = "right"
        var size = "medium"
        chat.optString("mail", "")
            .replace("184", "")
            .split(" ")
            .forEach { command ->
                getCommentColor(command)?.let { color = it }
                getCommentPosition(command)?.let { position = it }
                getCommentSize(command)?.let { size = it }
            }

        val chatDate = chat.optString("date").toDoubleOrNull() ?: return null
        val chatDateUsec = chat.optString("date_usec", "0").toDoubleOrNull() ?: return null
        val commentTime = (chatDate - programStartUnix) + (chatDateUsec / 1000000.0)
        if (commentTime < -5.0) return null

        ArchivedComment(
            time = commentTime.coerceAtLeast(0.0),
            text = content,
            color = color,
            author = chat.optString("user_id", ""),
            type = position,
            size = size
        )
    }.getOrNull()
}

private fun getCommentColor(command: String): String? = when (command) {
    "red" -> "#F02840"
    "pink" -> "#FF8080"
    "orange" -> "#FFC000"
    "yellow" -> "#FFFF00"
    "green" -> "#00FF00"
    "cyan" -> "#00FFFF"
    "blue" -> "#0000FF"
    "purple" -> "#C000FF"
    "black" -> "#000000"
    "niconicowhite", "white2" -> "#CCCC99"
    "truered", "red2" -> "#CC0033"
    "passionorange", "orange2" -> "#FF6600"
    "madyellow", "yellow2" -> "#999900"
    "elementalgreen", "green2" -> "#00CC66"
    "marineblue", "blue2" -> "#33FFFC"
    "nobleviolet", "purple2" -> "#6633CC"
    else -> null
}

private fun getCommentPosition(command: String): String? = when (command) {
    "ue" -> "top"
    "naka" -> "right"
    "shita" -> "bottom"
    else -> null
}

private fun getCommentSize(command: String): String? = when (command) {
    "big" -> "big"
    "medium" -> "medium"
    "small" -> "small"
    else -> null
}

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
