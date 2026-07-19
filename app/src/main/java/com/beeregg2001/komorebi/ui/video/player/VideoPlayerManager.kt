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
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
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
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.live.RawAribSubtitlePayloadReaderFactory
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.util.TsReadExDataSource
import com.beeregg2001.komorebi.util.mmts.TlvExtractorsFactory
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VideoPlayerManager"
private const val RECORDED_PLAYER_TARGET_BUFFER_BYTES = 64 * 1024 * 1024
private const val RECORDED_PLAYER_MIN_BUFFER_MS = 30_000
private const val RECORDED_PLAYER_MAX_BUFFER_MS = 90_000
private const val RECORDED_PLAYER_BUFFER_FOR_PLAYBACK_MS = 4_000
private const val RECORDED_PLAYER_BUFFER_FOR_REBUFFER_MS = 8_000
private const val RAW_MMTS_PLAYER_TARGET_BUFFER_BYTES = 32 * 1024 * 1024
private const val RAW_MMTS_PLAYER_MIN_BUFFER_MS = 5_000
private const val RAW_MMTS_PLAYER_MAX_BUFFER_MS = 10_000
private const val RAW_MMTS_PLAYER_BUFFER_FOR_PLAYBACK_MS = 1_000
private const val RAW_MMTS_PLAYER_BUFFER_FOR_REBUFFER_MS = 2_000
private const val CHASE_PLAYER_TARGET_BUFFER_BYTES = 48 * 1024 * 1024
private const val CHASE_PLAYER_MIN_BUFFER_MS = 15_000
private const val CHASE_PLAYER_MAX_BUFFER_MS = 45_000
private const val CHASE_PLAYER_BUFFER_FOR_PLAYBACK_MS = 8_000
private const val CHASE_PLAYER_BUFFER_FOR_REBUFFER_MS = 15_000
private const val HLS_LOAD_RETRY_DELAY_MS = 1_000L
private const val HLS_LOAD_MAX_RETRY_DELAY_MS = 8_000L
private const val RECORDED_SEGMENT_PREFETCH_COUNT = 0
private const val RECORDED_SEGMENT_RECOVERY_DEBOUNCE_MS = 2_000L
private const val GROWING_FILE_RETRY_INTERVAL_MS = 3_000L
private const val GROWING_FILE_MAX_IDLE_RETRIES = 20
private const val GROWING_FILE_LIVE_EDGE_SAFETY_MS = 2_000L

private fun RecordedProgram.currentChaseDurationMs(nowMs: Long = System.currentTimeMillis()): Long {
    return runCatching {
        val startMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val endMs = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        (nowMs.coerceAtMost(endMs) - startMs).coerceAtLeast(0L)
    }.getOrDefault((recordedVideo.duration * 1000.0).toLong().coerceAtLeast(0L))
}

private class GrowingHttpDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val onKnownFileSize: (Long) -> Unit
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDataSource: DataSource? = null
    private var baseDataSpec: DataSpec? = null
    private var readPosition = 0L
    private var idleRetries = 0

    @Volatile
    private var isClosed = false

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        isClosed = false
        baseDataSpec = dataSpec
        readPosition = dataSpec.position
        idleRetries = 0
        openAtCurrentPosition()
        // 録画中のファイル長は増え続けるため、Extractor には固定長を知らせない。
        return C.LENGTH_UNSET.toLong()
    }

    private fun openAtCurrentPosition(): Long {
        val originalSpec = checkNotNull(baseDataSpec)
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach(source::addTransferListener)
        activeDataSource = source
        val headers = originalSpec.httpRequestHeaders.toMutableMap().apply {
            put("Cache-Control", "no-cache")
            put("Pragma", "no-cache")
        }
        val rangedSpec = originalSpec.buildUpon()
            .setPosition(readPosition)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(headers)
            .build()
        return try {
            source.open(rangedSpec).also { openedLength ->
                if (openedLength != C.LENGTH_UNSET.toLong()) {
                    onKnownFileSize(readPosition + openedLength)
                }
            }
        } catch (error: Throwable) {
            runCatching { source.close() }
            activeDataSource = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (!isClosed) {
            val result = activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            if (result != C.RESULT_END_OF_INPUT) {
                if (result > 0) readPosition += result
                idleRetries = 0
                return result
            }

            closeActiveSource()
            if (idleRetries >= GROWING_FILE_MAX_IDLE_RETRIES) {
                return C.RESULT_END_OF_INPUT
            }
            idleRetries += 1
            try {
                Thread.sleep(GROWING_FILE_RETRY_INTERVAL_MS)
                if (!isClosed) openAtCurrentPosition()
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode != 416) {
                    throw error
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return C.RESULT_END_OF_INPUT
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = activeDataSource?.uri ?: baseDataSpec?.uri

    override fun close() {
        isClosed = true
        closeActiveSource()
    }

    private fun closeActiveSource() {
        runCatching { activeDataSource?.close() }
        activeDataSource = null
    }
}

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

private fun isRecordedSegmentUri(uri: Uri): Boolean {
    return uri.pathSegments.contains("streams") &&
            uri.pathSegments.contains("video") &&
            uri.lastPathSegment == "segment" &&
            uri.getQueryParameter("sequence")?.toIntOrNull() != null
}

private fun withSegmentSequence(uri: Uri, sequence: Int): Uri {
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames.forEach { name ->
        val values = if (name == "sequence") {
            listOf(sequence.toString())
        } else {
            uri.getQueryParameters(name)
        }
        values.forEach { value ->
            builder.appendQueryParameter(name, value)
        }
    }
    return builder.build()
}

private class PrefetchedSegmentDataSource(
    private val bytes: ByteArray,
    private val sourceUri: Uri
) : DataSource {
    private var readPosition = 0
    private var bytesRemaining = 0

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        readPosition = dataSpec.position.coerceAtMost(bytes.size.toLong()).toInt()
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            bytes.size - readPosition
        } else {
            dataSpec.length.coerceAtMost((bytes.size - readPosition).toLong()).toInt()
        }
        return bytesRemaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT
        }
        val bytesToRead = length.coerceAtMost(bytesRemaining)
        System.arraycopy(bytes, readPosition, buffer, offset, bytesToRead)
        readPosition += bytesToRead
        bytesRemaining -= bytesToRead
        return bytesToRead
    }

    override fun getUri(): Uri = sourceUri

    override fun close() = Unit
}

private class SegmentPrefetchCache {
    private val segments = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()

    fun take(uri: Uri): ByteArray? {
        val key = uri.toString()
        val future = segments[key] ?: return null
        if (!future.isDone) {
            return null
        }
        segments.remove(key, future)
        return try {
            future.get()
        } catch (e: Exception) {
            null
        }
    }

    fun prefetch(scope: CoroutineScope, uri: Uri, prefetchCount: Int) {
        prefetchRecordedSegments(scope, uri, segments, prefetchCount)
    }
}

private fun prefetchRecordedSegments(
    scope: CoroutineScope,
    uri: Uri,
    prefetchedSegments: ConcurrentHashMap<String, CompletableFuture<ByteArray>>,
    prefetchCount: Int
) {
    val currentSequence = uri.getQueryParameter("sequence")?.toIntOrNull() ?: return
    repeat(prefetchCount) { index ->
        val prefetchUri = withSegmentSequence(uri, currentSequence + index + 1)
        val prefetchUrl = prefetchUri.toString()
        val future = CompletableFuture<ByteArray>()
        if (prefetchedSegments.putIfAbsent(prefetchUrl, future) != null) {
            return@repeat
        }
        scope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(prefetchUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1_000_000
                    readTimeout = 1_000_000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Pragma", "no-cache")
                }
                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val bytes = stream?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                if (responseCode in 200..299) {
                    future.complete(bytes)
                } else {
                    future.completeExceptionally(IOException("HTTP $responseCode"))
                    prefetchedSegments.remove(prefetchUrl, future)
                }
                if (responseCode !in 200..299) {
                    Log.d(TAG, "Recorded segment prefetch skipped. [code=$responseCode, url=$prefetchUrl]")
                }
            } catch (e: Exception) {
                future.completeExceptionally(e)
                prefetchedSegments.remove(prefetchUrl, future)
                Log.d(TAG, "Recorded segment prefetch failed. [url=$prefetchUrl]", e)
            } finally {
                connection?.disconnect()
            }
        }
    }
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
    subtitleLanguageId: Int,
    onSubtitleLanguagesChanged: (List<NativeCaptionLanguage>) -> Unit,
    onVideoSizeChanged: (Int, Int, Float) -> Unit,
    onBufferingChanged: (Boolean) -> Unit,
    onDurationChanged: (Long) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onStreamSessionExpired: suspend (ExoPlayer) -> Boolean = { false },
    onStopOrDispose: (ExoPlayer) -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel()
): ExoPlayer {
    val captionDecoder = remember { NativeCaptionDecoder() }
    LaunchedEffect(program?.id) {
        captionDecoder.reset(subtitleLanguageId)
        onSubtitleLanguagesChanged(emptyList())
    }
    LaunchedEffect(subtitleLanguageId) {
        captionDecoder.switchLanguage(subtitleLanguageId)
    }
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
    val isRawMmtsPlayback = program?.recordedVideo?.containerFormat.equals(
        "MMT/TLV",
        ignoreCase = true
    ) && vs.currentQuality.isRawMmts && !isRecordingChasePlayback
    val isOriginalMpegTsPlayback = program?.recordedVideo?.containerFormat.equals(
        "MPEG-TS",
        ignoreCase = true
    ) && program?.recordedVideo?.videoCodec.equals(
        "MPEG-2",
        ignoreCase = true
    ) && vs.currentQuality.value == StreamQuality.ORIGINAL_MPEG_TS_VALUE
    val programDurationUs = ((program?.recordedVideo?.duration ?: 0.0) * 1_000_000.0).toLong()
    val smbServerList by settingsViewModel.smbServerList.collectAsState()
    val fileSizeBytesRef = remember(program?.id, isOriginalMpegTsPlayback) { AtomicLong(0L) }
    val fileSizeReferenceDurationUsRef = remember(
        program?.id,
        isOriginalMpegTsPlayback,
        isRecordingChasePlayback
    ) {
        AtomicLong(if (isRecordingChasePlayback) 0L else programDurationUs)
    }

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
        smbServerList,
        program?.id,
        isRecordingChasePlayback,
        isRawMmtsPlayback,
        isOriginalMpegTsPlayback,
        programDurationUs
    ) {
        val renderersFactory = DefaultRenderersFactory(context).apply {
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

        val segmentPrefetchCache = SegmentPrefetchCache()
        val playerRef = AtomicReference<ExoPlayer?>()
        val isImmediateStreamRecoveryRunning = AtomicBoolean(false)
        val isPlaybackRecoveryRunning = AtomicBoolean(false)

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
                        if (isOriginalMpegTsPlayback && isRecordingChasePlayback) {
                            GrowingHttpDataSource(httpDataSourceFactory) { totalFileSize ->
                                fileSizeBytesRef.updateAndGet { knownSize ->
                                    maxOf(knownSize, totalFileSize)
                                }
                                val recordedDurationUs = program
                                    ?.currentChaseDurationMs()
                                    ?.times(1_000L)
                                    ?: programDurationUs
                                fileSizeReferenceDurationUsRef.updateAndGet { knownDurationUs ->
                                    maxOf(knownDurationUs, recordedDurationUs)
                                }
                            }
                        } else {
                            httpDataSourceFactory.createDataSource()
                        }
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
                    if (isHttpSource && isRecordedSegmentUri(requestSpec.uri)) {
                        val prefetchedSegment = segmentPrefetchCache.take(requestSpec.uri)
                        if (prefetchedSegment != null) {
                            val prefetchedSource =
                                PrefetchedSegmentDataSource(prefetchedSegment, requestSpec.uri)
                            activeDataSource = prefetchedSource
                            val openedLength = prefetchedSource.open(requestSpec)
                            segmentPrefetchCache.prefetch(
                                scope,
                                requestSpec.uri,
                                RECORDED_SEGMENT_PREFETCH_COUNT
                            )
                            return openedLength
                        }
                    }
                    val openedLength = try {
                        source.open(requestSpec)
                    } catch (e: HttpDataSource.InvalidResponseCodeException) {
                        Log.e(
                            TAG,
                            "HTTP source open failed. [code=${e.responseCode}, uri=${requestSpec.uri}]",
                            e
                        )
                        if (
                            e.responseCode == 422 &&
                            isHttpSource &&
                            isRecordedSegmentUri(requestSpec.uri) &&
                            isImmediateStreamRecoveryRunning.compareAndSet(false, true)
                        ) {
                            scope.launch(Dispatchers.Main.immediate) {
                                try {
                                    val player = playerRef.get()
                                    if (player != null && onStreamSessionExpired(player)) {
                                        Log.i(
                                            TAG,
                                            "Immediate stream session recovery requested after segment 422. [uri=${requestSpec.uri}]"
                                        )
                                    }
                                    delay(RECORDED_SEGMENT_RECOVERY_DEBOUNCE_MS)
                                } finally {
                                    isImmediateStreamRecoveryRunning.set(false)
                                }
                            }
                        }
                        throw e
                    }
                    if (
                        isOriginalMpegTsPlayback &&
                        isHttpSource &&
                        openedLength != C.LENGTH_UNSET.toLong()
                    ) {
                        val totalFileSize = requestSpec.position + openedLength
                        fileSizeBytesRef.updateAndGet { knownSize ->
                            maxOf(knownSize, totalFileSize)
                        }
                        if (isRecordingChasePlayback) {
                            val recordedDurationUs = program
                                ?.currentChaseDurationMs()
                                ?.times(1_000L)
                                ?: programDurationUs
                            fileSizeReferenceDurationUsRef.updateAndGet { knownDurationUs ->
                                maxOf(knownDurationUs, recordedDurationUs)
                            }
                        }
                    }
                    if (isHttpSource && isRecordedSegmentUri(requestSpec.uri)) {
                        segmentPrefetchCache.prefetch(
                            scope,
                            requestSpec.uri,
                            RECORDED_SEGMENT_PREFETCH_COUNT
                        )
                    }
                    return openedLength
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
//        val isDirectPlayback = isEdcbDirect != null

        val customExtractorsFactory = ExtractorsFactory {
            if (isRawMmtsPlayback) {
                return@ExtractorsFactory TlvExtractorsFactory(
                    preferredVideoPacketId = null,
                    enableSeeking = true,
                    durationUs = programDurationUs
                ).createExtractors()
            }
            val defaultExtractors: Array<Extractor> = if (isOriginalMpegTsPlayback) {
                arrayOf<Extractor>(
                    TsExtractor(
                        TsExtractor.MODE_SINGLE_PMT,
                        TimestampAdjuster(C.TIME_UNSET),
                        RawAribSubtitlePayloadReaderFactory(),
                        TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                    )
                )
            } else {
                DefaultExtractorsFactory().apply {
                    setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                    setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
                    setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
                }.createExtractors()
            }

            // ダイレクトTSまたはHonomiTVの原始TS再生時は、HTTP Rangeに対応するSeekMapを注入する
            if ((isEdcbDirect || isOriginalMpegTsPlayback) && programDurationUs > 0L) {
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
                                            override fun getDurationUs(): Long {
                                                if (!isOriginalMpegTsPlayback || !isRecordingChasePlayback) {
                                                    return programDurationUs
                                                }
                                                val elapsedMs = program
                                                    ?.currentChaseDurationMs()
                                                    ?: (programDurationUs / 1_000L)
                                                return (
                                                    elapsedMs - GROWING_FILE_LIVE_EDGE_SAFETY_MS
                                                ).coerceAtLeast(0L) * 1_000L
                                            }
                                            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                                                val size = fileSizeBytesRef.get()
                                                if (size <= 0L) return SeekMap.SeekPoints(SeekPoint(timeUs, 0L))
                                                val currentDurationUs = getDurationUs().coerceAtLeast(1L)
                                                val safeTime = timeUs.coerceIn(0L, currentDurationUs)
                                                val referenceDurationUs = if (
                                                    isOriginalMpegTsPlayback && isRecordingChasePlayback
                                                ) {
                                                    fileSizeReferenceDurationUsRef.get().coerceAtLeast(1L)
                                                } else {
                                                    currentDurationUs
                                                }
                                                // 既知のファイルサイズと、そのサイズを観測した時点の録画時間から
                                                // 平均ビットレートを求め、HTTP Range の位置を推定する。
                                                val calculatedPosition =
                                                    (safeTime.toDouble() / referenceDurationUs * size).toLong()
                                                val maxPosition = (size - 188L * 512L).coerceAtLeast(0L)
                                                val position = calculatedPosition.coerceAtMost(maxPosition)
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
        val targetBufferBytes = when {
            isRawMmtsPlayback -> RAW_MMTS_PLAYER_TARGET_BUFFER_BYTES
            isRecordingChasePlayback -> CHASE_PLAYER_TARGET_BUFFER_BYTES
            else -> RECORDED_PLAYER_TARGET_BUFFER_BYTES
        }
        val minBufferMs = when {
            isRawMmtsPlayback -> RAW_MMTS_PLAYER_MIN_BUFFER_MS
            isRecordingChasePlayback -> CHASE_PLAYER_MIN_BUFFER_MS
            else -> RECORDED_PLAYER_MIN_BUFFER_MS
        }
        val maxBufferMs = when {
            isRawMmtsPlayback -> RAW_MMTS_PLAYER_MAX_BUFFER_MS
            isRecordingChasePlayback -> CHASE_PLAYER_MAX_BUFFER_MS
            else -> RECORDED_PLAYER_MAX_BUFFER_MS
        }
        val bufferForPlaybackMs = when {
            isRawMmtsPlayback -> RAW_MMTS_PLAYER_BUFFER_FOR_PLAYBACK_MS
            isRecordingChasePlayback -> CHASE_PLAYER_BUFFER_FOR_PLAYBACK_MS
            else -> RECORDED_PLAYER_BUFFER_FOR_PLAYBACK_MS
        }
        val bufferForPlaybackAfterRebufferMs = when {
            isRawMmtsPlayback -> RAW_MMTS_PLAYER_BUFFER_FOR_REBUFFER_MS
            isRecordingChasePlayback -> CHASE_PLAYER_BUFFER_FOR_REBUFFER_MS
            else -> RECORDED_PLAYER_BUFFER_FOR_REBUFFER_MS
        }
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
                playerRef.set(this)
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(),
                    true
                )
                addListener(object : Player.Listener {
                    private var wasBuffering = false

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
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        onBufferingChanged(isBuffering)
                        if (isBuffering && !wasBuffering) {
                            Log.i(
                                TAG,
                                "Video buffering started. [recording_chase=$isRecordingChasePlayback, position_ms=$currentPosition, buffered_ms=$bufferedPosition, duration_ms=$duration]"
                            )
                        }
                        if (playbackState == Player.STATE_READY) {
                            onDurationChanged(duration)
                            if (wasBuffering) {
                                Log.i(
                                    TAG,
                                    "Video buffering ended. [recording_chase=$isRecordingChasePlayback, position_ms=$currentPosition, buffered_ms=$bufferedPosition, duration_ms=$duration]"
                                )
                            }
                        }
                        wasBuffering = isBuffering
                        if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
                        if (!isPlaybackRecoveryRunning.compareAndSet(false, true)) {
                            return
                        }
                        scope.launch {
                            try {
                                if (error.hasHttpResponseCode(422) && onStreamSessionExpired(this@apply)) {
                                    return@launch
                                }
                                onBufferingChanged(true)
                                delay(3000L)
                                prepare()
                                playWhenReady = true
                            } finally {
                                isPlaybackRecoveryRunning.set(false)
                            }
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!vs.isSubtitleEnabled) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry !is PrivFrame) continue
                            when {
                                entry.owner.contains("aribb62", ignoreCase = true) -> {
                                    val cues = captionDecoder.decodeB62(entry.privateData, currentPosition)
                                    onSubtitleLanguagesChanged(captionDecoder.availableLanguages())
                                    cues.forEach { cue ->
                                        if (cue.ptsMs <= currentPosition + 50L) {
                                            onSubtitleCue(cue)
                                        } else {
                                            createMessage(PlayerMessage.Target { _, payload ->
                                                if (vs.isSubtitleEnabled) {
                                                    (payload as? NativeCaptionCue)?.let(onSubtitleCue)
                                                }
                                            })
                                                .setPosition(cue.ptsMs)
                                                .setPayload(cue)
                                                .setDeleteAfterDelivery(true)
                                                .send()
                                        }
                                    }
                                }

                                entry.owner.contains("aribb24", ignoreCase = true) ||
                                    entry.owner.contains("B24", ignoreCase = true) -> {
                                    val cue = captionDecoder.decode(entry.privateData, currentPosition)
                                    onSubtitleLanguagesChanged(captionDecoder.availableLanguages())
                                    if (cue != null) onSubtitleCue(cue)
                                }
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
