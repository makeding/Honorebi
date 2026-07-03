@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TsReadExDataSource
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "VideoPlayerManager"
private const val RECORDED_PLAYER_TARGET_BUFFER_BYTES = 96 * 1024 * 1024
private const val RECORDED_PLAYER_MIN_BUFFER_MS = 15_000
private const val RECORDED_PLAYER_MAX_BUFFER_MS = 45_000
private const val RECORDED_PLAYER_BUFFER_FOR_PLAYBACK_MS = 2_500
private const val RECORDED_PLAYER_BUFFER_FOR_REBUFFER_MS = 8_000
private const val CHASE_PLAYER_TARGET_BUFFER_BYTES = 64 * 1024 * 1024
private const val CHASE_PLAYER_MIN_BUFFER_MS = 8_000
private const val CHASE_PLAYER_MAX_BUFFER_MS = 45_000
private const val CHASE_PLAYER_BUFFER_FOR_PLAYBACK_MS = 2_500
private const val CHASE_PLAYER_BUFFER_FOR_REBUFFER_MS = 8_000
private const val HLS_LOAD_RETRY_DELAY_MS = 1_000L
private const val HLS_LOAD_MAX_RETRY_DELAY_MS = 8_000L

private fun shouldBypassPlaylistCache(dataSpec: DataSpec): Boolean {
    val path = dataSpec.uri.path.orEmpty()
    val lastSegment = dataSpec.uri.lastPathSegment.orEmpty()
    return path.endsWith(".m3u8", ignoreCase = true) ||
            lastSegment.equals("playlist", ignoreCase = true) ||
            lastSegment.endsWith(".m3u8", ignoreCase = true)
}

private fun withFreshPlaylistCacheKey(uri: Uri): Uri {
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames.forEach { name ->
        if (name != "cache_key") {
            uri.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
    }
    return builder
        .appendQueryParameter("cache_key", System.currentTimeMillis().toString())
        .build()
}

private fun Throwable.hasHttpResponseCode(responseCode: Int): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == responseCode) {
            return true
        }
        cause = cause.cause
    }
    return false
}

private class HonomiLikeHlsLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return when (dataType) {
            C.DATA_TYPE_MANIFEST -> 4
            C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> 7
            else -> super.getMinimumLoadableRetryCount(dataType)
        }
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        if (exception.isHttpResponseCode(422)) {
            return C.TIME_UNSET
        }
        val defaultDelayMs = super.getRetryDelayMsFor(loadErrorInfo)
        if (defaultDelayMs == C.TIME_UNSET) {
            return C.TIME_UNSET
        }
        return if (loadErrorInfo.errorCount <= 2) {
            0L
        } else {
            ((loadErrorInfo.errorCount - 2) * HLS_LOAD_RETRY_DELAY_MS)
                .coerceAtMost(HLS_LOAD_MAX_RETRY_DELAY_MS)
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
    isLiveStream: Boolean,
    vs: VideoPlayerState,
    scope: CoroutineScope,
    onSubtitleCue: (NativeCaptionCue) -> Unit,
    onVideoSizeChanged: (Int, Int, Float) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onStreamSessionExpired: suspend (ExoPlayer) -> Boolean = { false },
    onStopOrDispose: (ExoPlayer) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
): ExoPlayer {
    val captionDecoder = remember { NativeCaptionDecoder() }
    LaunchedEffect(vs.isSubtitleEnabled) {
        if (!vs.isSubtitleEnabled) captionDecoder.flush()
    }
    DisposableEffect(Unit) {
        onDispose { captionDecoder.close() }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val backendType by settingsViewModel.backendType.collectAsState()
    val edcbPlayMethod by settingsViewModel.edcbRecordPlayMethod.collectAsState()
    val isEdcbDirect = (backendType == "EDCB" && edcbPlayMethod == "DIRECT")
    val isRecordingChasePlayback =
        program?.isRecording == true || program?.recordedVideo?.status.equals("Recording", ignoreCase = true)
    val smbServerList by settingsViewModel.smbServerList.collectAsState()

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

    val exoPlayer = remember(smbServerList, program?.id, program?.recordedVideo?.duration) {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(1_000_000)
            setReadTimeoutMs(1_000_000)
        }

        val nativeLib = NativeLib()

        // ★ 追加: HTTPリクエスト時に取得したファイルサイズを保持する共有変数
        val fileSizeBytesRef = AtomicLong(0L)

        val dataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var activeDataSource: DataSource? = null
                private val transferListeners = mutableListOf<TransferListener>()

                override fun addTransferListener(transferListener: TransferListener) {
                    transferListeners.add(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val isSmb = dataSpec.uri.scheme == "smb"
                    val isEdcbScheme = dataSpec.uri.scheme == "edcb"
                    val isDirectTs = dataSpec.uri.path?.endsWith(".ts", ignoreCase = true) == true || dataSpec.uri.path?.endsWith("m2ts", ignoreCase = true) == true
//                    val isMirakurun = dataSpec.uri.path?.contains("/api/streams/") == true || dataSpec.uri.path?.contains("/api/channels/") == true

                    val sid = program?.channel?.serviceId ?: -1
                    val nValue = sid.toString()

                    val dynamicTsArgs = arrayOf(
                        "tsreadex", "-x", "18/38/39", "-n", nValue,
                        "-a", "13", "-b", "5", "-c", "5", "-u", "1", "-d", "13"
                    )

                    var isHttpSource = false
                    val source = if (isSmb) {
                        val host = dataSpec.uri.host ?: ""
                        val server = smbServerList.find { s -> s.ip.substringBefore("/") == host }
                        val smbContext = SmbContextBuilder.build(server?.user ?: "", server?.password ?: "")
                        SmbDataSourceFactory(smbContext).createDataSource()
                    } else if (isEdcbScheme || isDirectTs  || isEdcbDirect) {
                        // ★ 修正: ファイルサイズ格納用の参照を渡す
                        TsReadExDataSource(nativeLib, dynamicTsArgs, fileSizeBytesRef)
                    } else {
                        isHttpSource = true
                        httpDataSourceFactory.createDataSource()
                    }

                    transferListeners.forEach { source.addTransferListener(it) }
                    activeDataSource = source
                    val requestSpec = if (isHttpSource && shouldBypassPlaylistCache(dataSpec)) {
                        val freshUri = withFreshPlaylistCacheKey(dataSpec.uri)
                        val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                            put("Cache-Control", "no-cache")
                            put("Pragma", "no-cache")
                        }
                        dataSpec.buildUpon()
                            .setUri(freshUri)
                            .setHttpRequestHeaders(headers)
                            .build()
                    } else {
                        dataSpec
                    }
                    return source.open(requestSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
                }

                override fun getUri(): Uri? = activeDataSource?.uri

                override fun close() {
                    activeDataSource?.close()
                    activeDataSource = null
                }
            }
        }

        // ★ 核心: ExoPlayer の Extractor をラップし、自前の SeekMap を強制注入する
        val programDurationUs = ((program?.recordedVideo?.duration ?: 0.0) * 1_000_000.0).toLong()
//        val isDirectPlayback = isEdcbDirect != null

        val customExtractorsFactory = ExtractorsFactory {
            val defaultExtractors = DefaultExtractorsFactory().apply {
                setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            }.createExtractors()

            // ダイレクトTS再生時のみ、TsExtractor をラップして SeekMap を上書き
            if (isEdcbDirect && programDurationUs > 0L) {
                for (i in defaultExtractors.indices) {
                    val extractor = defaultExtractors[i]
                    if (extractor is TsExtractor) {
                        defaultExtractors[i] = object : Extractor {
                            override fun sniff(input: ExtractorInput) = extractor.sniff(input)
                            override fun init(output: ExtractorOutput) {
                                extractor.init(object : ExtractorOutput by output {
                                    override fun seekMap(seekMap: SeekMap) {
                                        // TsExtractor が算出したエラーの SeekMap を無視し、独自の高精度マップを注入
                                        val customSeekMap = object : SeekMap {
                                            override fun isSeekable() = true
                                            override fun getDurationUs() = programDurationUs
                                            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                                                val size = fileSizeBytesRef.get()
                                                if (size <= 0L) return SeekMap.SeekPoints(SeekPoint(timeUs, 0L))
                                                val safeTime = timeUs.coerceIn(0L, programDurationUs)
                                                // 時間とファイルサイズから、HTTP Range の要求バイトオフセットを正確に計算する
                                                val position = (safeTime.toDouble() / programDurationUs * size).toLong()
                                                return SeekMap.SeekPoints(SeekPoint(safeTime, position))
                                            }
                                        }
                                        output.seekMap(customSeekMap)
                                    }
                                })
                            }
                            override fun read(input: ExtractorInput, seekPosition: PositionHolder) = extractor.read(input, seekPosition)
                            override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)
                            override fun release() = extractor.release()
                        }
                    }
                }
            }
            defaultExtractors
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, customExtractorsFactory)
            .setLoadErrorHandlingPolicy(HonomiLikeHlsLoadErrorHandlingPolicy())

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val targetBufferBytes =
            if (isRecordingChasePlayback) CHASE_PLAYER_TARGET_BUFFER_BYTES else RECORDED_PLAYER_TARGET_BUFFER_BYTES
        val minBufferMs =
            if (isRecordingChasePlayback) CHASE_PLAYER_MIN_BUFFER_MS else RECORDED_PLAYER_MIN_BUFFER_MS
        val maxBufferMs =
            if (isRecordingChasePlayback) CHASE_PLAYER_MAX_BUFFER_MS else RECORDED_PLAYER_MAX_BUFFER_MS
        val bufferForPlaybackMs =
            if (isRecordingChasePlayback) CHASE_PLAYER_BUFFER_FOR_PLAYBACK_MS else RECORDED_PLAYER_BUFFER_FOR_PLAYBACK_MS
        val bufferForPlaybackAfterRebufferMs =
            if (isRecordingChasePlayback) CHASE_PLAYER_BUFFER_FOR_REBUFFER_MS else RECORDED_PLAYER_BUFFER_FOR_REBUFFER_MS
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(targetBufferBytes)
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
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
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(),
                    true
                )
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        onVideoSizeChanged(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        vs.isPlayerPlaying = playing
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        applyAudioSelectionAndMatrix(vs.currentAudioMode, this@apply)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                        if (playbackState == Player.STATE_READY) onDurationChanged(duration)
                        if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
                        scope.launch {
                            if (error.hasHttpResponseCode(422) && onStreamSessionExpired(this@apply)) {
                                return@launch
                            }
                            onBufferingChanged(true)
                            delay(3000L)
                            prepare()
                            playWhenReady = true
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!vs.isSubtitleEnabled) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is PrivFrame && (entry.owner.contains("aribb24", true) || entry.owner.contains("B24", true))) {
                                captionDecoder.decode(entry.privateData, currentPosition)
                                    ?.let(onSubtitleCue)
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
                onStopOrDispose(exoPlayer)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            onStopOrDispose(exoPlayer)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    return exoPlayer
}
