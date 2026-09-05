@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.subtitle.AribId3PrivPayloadRouter
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.player.LibaribtlvToneMappingRenderersFactory
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedLoadDataType
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedLoadRetryDecision
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedLoadRetryPolicy
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedPlayerBufferProfile
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedPlayerConstructionKey
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedMpegTsPassthroughPolicy
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VideoPlayerManager"

private fun RecordedProgram.epgDurationUs(): Long {
    return runCatching {
        val startMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val endMs = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        (endMs - startMs).coerceAtLeast(0L) * 1_000L
    }.getOrDefault(C.TIME_UNSET)
}

internal fun Throwable.hasHttpResponseCode(responseCode: Int): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == responseCode) {
            return true
        }
        cause = cause.cause
    }
    return false
}

/** How a caller-owned, bounded recovery policy wants this player error handled. */
sealed interface PlayerErrorRecovery {
    /** The caller renewed/reported the error and no further player action is safe. */
    data object Handled : PlayerErrorRecovery
    /** Keep the existing delayed Media3 reprepare path. */
    data object Reprepare : PlayerErrorRecovery
    /** Use the legacy recovery behavior for ordinary (non-switching) playback. */
    data object UseDefault : PlayerErrorRecovery
}

private class HonomiLikeHlsLoadErrorHandlingPolicy(
    private val isNetworkAvailable: () -> Boolean,
) : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return RecordedLoadRetryPolicy.minimumLoadableRetryCount(
            when (dataType) {
                C.DATA_TYPE_MANIFEST -> RecordedLoadDataType.Manifest
                C.DATA_TYPE_MEDIA -> RecordedLoadDataType.Media
                C.DATA_TYPE_MEDIA_INITIALIZATION -> RecordedLoadDataType.MediaInitialization
                else -> RecordedLoadDataType.Other
            },
            super.getMinimumLoadableRetryCount(dataType),
        )
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val networkAvailable = isNetworkAvailable()
        val isHttp422 = loadErrorInfo.exception.isHttpResponseCode(422)
        val defaultDelayMs = if (networkAvailable && !isHttp422) {
            super.getRetryDelayMsFor(loadErrorInfo)
        } else {
            C.TIME_UNSET
        }
        return when (val decision = RecordedLoadRetryPolicy.retryDecision(
            networkAvailable = networkAvailable,
            isHttp422 = isHttp422,
            errorCount = loadErrorInfo.errorCount,
            media3CanRetry = defaultDelayMs != C.TIME_UNSET,
        )) {
            RecordedLoadRetryDecision.DoNotRetry -> C.TIME_UNSET
            is RecordedLoadRetryDecision.RetryAfter -> decision.delayMs
        }
    }
}

private fun IOException.isHttpResponseCode(responseCode: Int): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == responseCode) {
            return true
        }
        cause = cause.cause
    }
    return false
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
fun rememberManagedExoPlayer(
    program: RecordedProgram?,
    recordedPlaybackFence: RecordedPlaybackFence,
    isLiveStream: Boolean,
    vs: VideoPlayerState,
    scope: CoroutineScope,
    onSubtitleCue: (NativeCaptionCue) -> Unit,
    subtitleLanguageId: Int,
    onSubtitleLanguagesChanged: (List<NativeCaptionLanguage>) -> Unit,
    onVideoSizeChanged: (Int, Int, Float) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    dataBroadcastingCallback: B60DataBroadcastingCallback? = null,
    enableHdrToSdrToneMapping: Boolean = false,
    isNetworkAvailable: () -> Boolean = { true },
    onStreamSessionExpired: suspend (ExoPlayer) -> Boolean = { false },
    onPlayerErrorRecovery: suspend (ExoPlayer, PlaybackException) -> PlayerErrorRecovery = { _, _ ->
        PlayerErrorRecovery.UseDefault
    },
    onStopOrDispose: (ExoPlayer) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
): ExoPlayer {
    val context = LocalContext.current
    val captionDecoder = remember { NativeCaptionDecoder(context) }
    val superimposeDecoder = remember {
        NativeCaptionDecoder(
            captionType = NativeCaptionDecoder.TYPE_SUPERIMPOSE,
            context = context
        )
    }
    val b62SubtitleSamples = remember(recordedPlaybackFence.identity) {
        Channel<FencedB62SubtitleSample>(
            capacity = 4,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    val currentOnSubtitleCue = rememberUpdatedState(onSubtitleCue)
    val currentOnSubtitleLanguagesChanged = rememberUpdatedState(onSubtitleLanguagesChanged)
    val currentOnVideoSizeChanged = rememberUpdatedState(onVideoSizeChanged)
    val currentOnBufferingChanged = rememberUpdatedState(onBufferingChanged)
    val currentOnDurationChanged = rememberUpdatedState(onDurationChanged)
    val currentOnPlaybackEnded = rememberUpdatedState(onPlaybackEnded)
    val currentOnStreamSessionExpired = rememberUpdatedState(onStreamSessionExpired)
    val currentOnPlayerErrorRecovery = rememberUpdatedState(onPlayerErrorRecovery)
    val currentOnStopOrDispose = rememberUpdatedState(onStopOrDispose)
    LaunchedEffect(captionDecoder, b62SubtitleSamples, recordedPlaybackFence.identity) {
        captionDecoder.reset(subtitleLanguageId)
        superimposeDecoder.reset()
        currentOnSubtitleLanguagesChanged.value(emptyList())
        for (fencedSample in b62SubtitleSamples) {
            if (!recordedPlaybackFence.accepts(fencedSample.token)) continue
            val sample = fencedSample.sample
            val isCaption = sample.type == NativeCaptionDecoder.TYPE_CAPTION
            val decoder = when (sample.type) {
                NativeCaptionDecoder.TYPE_CAPTION -> captionDecoder
                NativeCaptionDecoder.TYPE_SUPERIMPOSE -> superimposeDecoder
                else -> continue
            }
            val (decoded, languages) = withContext(Dispatchers.Default) {
                decoder.decodeB62(
                    data = sample.data,
                    ptsMs = sample.timeUs / 1_000L,
                    operationMode = sample.operationMode,
                    timingMode = sample.timingMode,
                    referenceStartPtsMs = sample.referenceStartTimeUs?.div(1_000L),
                    mpuSequenceNumber = sample.mpuSequenceNumber,
                    resources = sample.resources,
                    discontinuity = sample.discontinuity
                ) to decoder.availableLanguages()
            }
            if (!recordedPlaybackFence.accepts(fencedSample.token)) continue
            if (isCaption) currentOnSubtitleLanguagesChanged.value(languages)
            if (!isCaption || vs.isSubtitleEnabled) {
                decoded.forEach { cue ->
                    if (recordedPlaybackFence.accepts(fencedSample.token)) {
                        currentOnSubtitleCue.value(cue)
                    }
                }
            }
        }
    }
    LaunchedEffect(subtitleLanguageId, recordedPlaybackFence.identity) {
        if (recordedPlaybackFence.accepts()) {
            captionDecoder.switchLanguage(subtitleLanguageId)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            b62SubtitleSamples.close()
            captionDecoder.close()
            superimposeDecoder.close()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    val backendType by settingsViewModel.backendType.collectAsState()
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")
    val isRecordingChasePlayback =
        program?.isRecording == true || program?.recordedVideo?.status.equals("Recording", ignoreCase = true)
    val isRawMmtsPlayback = program?.requiresRawMmtsPlayback == true &&
        vs.currentQuality.isRawMmts
    val isOriginalMpegTsPlayback = RecordedMpegTsPassthroughPolicy.shouldUseTsReadEx(
        containerFormat = program?.recordedVideo?.containerFormat,
        videoCodec = program?.recordedVideo?.videoCodec,
        qualityValue = vs.currentQuality.value,
    )
    val programDurationUs = ((program?.recordedVideo?.duration ?: 0.0) * 1_000_000.0).toLong()
    val epgDurationUs = program?.epgDurationUs() ?: C.TIME_UNSET
    val tsreadexServiceId = program?.channel?.serviceId ?: -1
    val smbServerList by settingsViewModel.smbServerList.collectAsState()
    val programRef = remember(program?.id) { AtomicReference(program) }
    val programDurationUsRef = remember(program?.id) { AtomicLong(programDurationUs) }
    SideEffect {
        programRef.set(program)
        if (programDurationUs > 0L) programDurationUsRef.set(programDurationUs)
    }
    val fileSizeBytesRef = remember(program?.id, isOriginalMpegTsPlayback, isRawMmtsPlayback) {
        AtomicLong(0L)
    }
    val fileSizeReferenceDurationUsRef = remember(
        program?.id,
        isOriginalMpegTsPlayback,
        isRecordingChasePlayback
    ) {
        AtomicLong(if (isRecordingChasePlayback) 0L else programDurationUs)
    }
    val constructionKey = RecordedPlayerConstructionKey(
        programId = program?.id,
        isRecordingChasePlayback = isRecordingChasePlayback,
        isRawMmtsPlayback = isRawMmtsPlayback,
        isOriginalMpegTsPlayback = isOriginalMpegTsPlayback,
        isEdcbDirect = isEdcbDirect,
        tsreadexServiceId = tsreadexServiceId,
        chaseProgramWindowDurationUs = if (
            isRecordingChasePlayback && (isRawMmtsPlayback || isOriginalMpegTsPlayback)
        ) epgDurationUs else C.TIME_UNSET,
        enableHdrToSdrToneMapping = enableHdrToSdrToneMapping,
    )

    val applyAudioSelectionAndMatrix = { mode: AudioMode, player: ExoPlayer ->
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isNotEmpty()) {
            val sortedAudioGroups = audioGroups.sortedBy { group ->
                group.mediaTrackGroup.getFormat(0).id?.toIntOrNull() ?: Int.MAX_VALUE
            }

            val isSub = mode == AudioMode.SUB
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

            if (sortedAudioGroups.size > 1) {
                val targetGroupIndex = if (isSub) 1 else 0
                val targetGroup = sortedAudioGroups[targetGroupIndex.coerceAtMost(sortedAudioGroups.size - 1)]
                builder.addOverride(TrackSelectionOverride(targetGroup.mediaTrackGroup, 0))
            } else {
                val targetGroup = sortedAudioGroups.firstOrNull()
                if ((targetGroup?.mediaTrackGroup?.length ?: 0) > 1) {
                    val targetTrackIndex = if (isSub) 1 else 0
                    builder.addOverride(
                        TrackSelectionOverride(targetGroup!!.mediaTrackGroup, targetTrackIndex)
                    )
                }
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    val exoPlayer = remember(
        recordedPlaybackFence.identity,
        constructionKey,
        smbServerList,
        dataBroadcastingCallback,
    ) {
        Log.i(TAG, "Building recorded ExoPlayer: $constructionKey")
        val renderersFactory = LibaribtlvToneMappingRenderersFactory(
            context,
            if (enableHdrToSdrToneMapping) HdrToneMapping.colorLut else null
        ).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(60_000)
        }

        val nativeLib = NativeLib()

        val isPlaybackRecoveryRunning = AtomicBoolean(false)

        val dataSourceFactory = buildRecordedDataSourceFactory(
            nativeLib = nativeLib,
            httpDataSourceFactory = httpDataSourceFactory,
            constructionKey = constructionKey,
            smbServerList = smbServerList,
            scope = scope,
            fileSizeBytesRef = fileSizeBytesRef,
            fileSizeReferenceDurationUsRef = fileSizeReferenceDurationUsRef,
            programRef = programRef,
            programDurationUsRef = programDurationUsRef,
        )
        val customExtractorsFactory = buildRecordedExtractorsFactory(
            constructionKey = constructionKey,
            epgDurationUs = epgDurationUs,
            programRef = programRef,
            programDurationUsRef = programDurationUsRef,
            fileSizeBytesRef = fileSizeBytesRef,
            fileSizeReferenceDurationUsRef = fileSizeReferenceDurationUsRef,
            onRawMmtsSubtitleData = { sample ->
                if (recordedPlaybackFence.accepts()) {
                    b62SubtitleSamples.trySend(
                        FencedB62SubtitleSample(recordedPlaybackFence.tokenOrNull(), sample)
                    )
                }
            },
            dataBroadcastingCallback = dataBroadcastingCallback,
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, customExtractorsFactory)
            .setLoadErrorHandlingPolicy(HonomiLikeHlsLoadErrorHandlingPolicy(isNetworkAvailable))

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val bufferProfile = RecordedPlayerBufferProfile.select(
            isRawMmtsPlayback = isRawMmtsPlayback,
            isRecordingChasePlayback = isRecordingChasePlayback,
        )
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(bufferProfile.targetBufferBytes)
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.bufferForPlaybackMs,
                bufferProfile.bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .build().apply {
                if (enableHdrToSdrToneMapping) {
                    setVideoEffects(emptyList())
                }
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(),
                    true
                )
                addListener(object : Player.Listener {
                    private var wasBuffering = false

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (!recordedPlaybackFence.accepts()) return
                        currentOnVideoSizeChanged.value(
                            videoSize.width,
                            videoSize.height,
                            videoSize.pixelWidthHeightRatio,
                        )
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        if (!recordedPlaybackFence.accepts()) return
                        vs.isPlayerPlaying = playing
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (!recordedPlaybackFence.accepts()) return
                        applyAudioSelectionAndMatrix(vs.currentAudioMode, this@apply)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (!recordedPlaybackFence.accepts() ||
                            !AribId3PrivPayloadRouter.shouldResetForPositionDiscontinuity(reason)
                        ) return
                        captionDecoder.reset(subtitleLanguageId)
                        superimposeDecoder.reset()
                        currentOnSubtitleLanguagesChanged.value(emptyList())
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!recordedPlaybackFence.accepts()) return
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        currentOnBufferingChanged.value(isBuffering)
                        if (isBuffering && !wasBuffering) {
                            Log.i(
                                TAG,
                                "Video buffering started. [recording_chase=$isRecordingChasePlayback, position_ms=$currentPosition, buffered_ms=$bufferedPosition, duration_ms=$duration]"
                            )
                        }
                        if (playbackState == Player.STATE_READY) {
                            currentOnDurationChanged.value(duration)
                            if (wasBuffering) {
                                Log.i(
                                    TAG,
                                    "Video buffering ended. [recording_chase=$isRecordingChasePlayback, position_ms=$currentPosition, buffered_ms=$bufferedPosition, duration_ms=$duration]"
                                )
                            }
                        }
                        wasBuffering = isBuffering
                        if (playbackState == Player.STATE_ENDED) currentOnPlaybackEnded.value()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!recordedPlaybackFence.accepts()) return
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
                        if (!isPlaybackRecoveryRunning.compareAndSet(false, true)) {
                            return
                        }
                        scope.launch {
                            try {
                                if (!recordedPlaybackFence.accepts()) return@launch
                                when (currentOnPlayerErrorRecovery.value(this@apply, error)) {
                                    PlayerErrorRecovery.Handled -> return@launch
                                    PlayerErrorRecovery.Reprepare -> Unit
                                    PlayerErrorRecovery.UseDefault -> {
                                        if (
                                            error.hasHttpResponseCode(422) &&
                                            currentOnStreamSessionExpired.value(this@apply)
                                        ) {
                                            return@launch
                                        }
                                    }
                                }
                                currentOnBufferingChanged.value(true)
                                delay(3000L)
                                if (!recordedPlaybackFence.accepts()) return@launch
                                prepare()
                                playWhenReady = true
                            } finally {
                                isPlaybackRecoveryRunning.set(false)
                            }
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!recordedPlaybackFence.accepts()) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry !is PrivFrame) continue
                            if (!entry.owner.equals("aribb24.js", ignoreCase = true)) continue
                            when (val route = AribId3PrivPayloadRouter.route(
                                entry.privateData,
                                captionsEnabled = vs.isSubtitleEnabled,
                            )) {
                                is AribId3PrivPayloadRouter.Route.Caption -> {
                                    val cue = captionDecoder.decode(
                                        entry.privateData,
                                        currentPosition,
                                        renderCaptions = route.render,
                                    )
                                    if (!recordedPlaybackFence.accepts()) continue
                                    currentOnSubtitleLanguagesChanged.value(
                                        captionDecoder.availableLanguages()
                                    )
                                    if (route.render && cue != null) {
                                        currentOnSubtitleCue.value(cue)
                                    }
                                }
                                AribId3PrivPayloadRouter.Route.Superimpose -> {
                                    val cue = superimposeDecoder.decode(
                                        entry.privateData,
                                        currentPosition,
                                        renderCaptions = true,
                                    )
                                    if (cue != null && recordedPlaybackFence.accepts()) {
                                        currentOnSubtitleCue.value(cue)
                                    }
                                }
                                AribId3PrivPayloadRouter.Route.Ignore -> Unit
                            }
                        }
                    }
                })
            }
    }

    LaunchedEffect(vs.currentAudioMode) {
        applyAudioSelectionAndMatrix(vs.currentAudioMode, exoPlayer)
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                currentOnStopOrDispose.value(exoPlayer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentOnStopOrDispose.value(exoPlayer)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    return exoPlayer
}
