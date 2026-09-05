@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedByteSeekPolicy
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedMpegTsPassthroughPolicy
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedPlayerConstructionKey
import com.beeregg2001.komorebi.ui.video.smb.player.SmbContextBuilder
import com.beeregg2001.komorebi.ui.video.smb.player.SmbDataSourceFactory
import com.beeregg2001.komorebi.util.TsReadExDataSource
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.util.mmts.TlvExtractorsFactory
import com.beeregg2001.komorebi.viewmodel.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "RecordedPlaybackSources"
private const val RECORDED_SEGMENT_PREFETCH_COUNT = 0
private const val GROWING_FILE_RETRY_INTERVAL_MS = 3_000L
private const val GROWING_FILE_MAX_IDLE_RETRIES = 20
internal const val GROWING_FILE_LIVE_EDGE_SAFETY_MS = 2_000L
private const val GROWING_SEEK_MAP_REFRESH_MS = 30_000L

internal fun RecordedProgram.currentChaseDurationMs(nowMs: Long = System.currentTimeMillis()): Long {
    return runCatching {
        val startMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val endMs = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        (nowMs.coerceAtMost(endMs) - startMs).coerceAtLeast(0L)
    }.getOrDefault((recordedVideo.duration * 1000.0).toLong().coerceAtLeast(0L))
}

private class GrowingHttpDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val onKnownFileSize: (Long) -> Unit,
    private val reportSnapshotLength: Boolean = false,
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
        val openedLength = openAtCurrentPosition()
        return if (reportSnapshotLength) openedLength else C.LENGTH_UNSET.toLong()
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
            if (idleRetries >= GROWING_FILE_MAX_IDLE_RETRIES) return C.RESULT_END_OF_INPUT
            idleRetries += 1
            try {
                Thread.sleep(GROWING_FILE_RETRY_INTERVAL_MS)
                if (!isClosed) openAtCurrentPosition()
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (error.responseCode != 416) throw error
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
            uri.getQueryParameters(name).forEach { value -> builder.appendQueryParameter(name, value) }
        }
    }
    return builder.appendQueryParameter("cache_key", System.currentTimeMillis().toString()).build()
}

private fun isRecordedSegmentUri(uri: Uri): Boolean =
    uri.pathSegments.contains("streams") &&
        uri.pathSegments.contains("video") &&
        uri.lastPathSegment == "segment" &&
        uri.getQueryParameter("sequence")?.toIntOrNull() != null

private fun withSegmentSequence(uri: Uri, sequence: Int): Uri {
    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames.forEach { name ->
        val values = if (name == "sequence") listOf(sequence.toString()) else uri.getQueryParameters(name)
        values.forEach { value -> builder.appendQueryParameter(name, value) }
    }
    return builder.build()
}

private class PrefetchedSegmentDataSource(
    private val bytes: ByteArray,
    private val sourceUri: Uri,
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
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
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
        if (!future.isDone) return null
        segments.remove(key, future)
        return runCatching { future.get() }.getOrNull()
    }

    fun prefetch(scope: CoroutineScope, uri: Uri) {
        val currentSequence = uri.getQueryParameter("sequence")?.toIntOrNull() ?: return
        repeat(RECORDED_SEGMENT_PREFETCH_COUNT) { index ->
            val prefetchUri = withSegmentSequence(uri, currentSequence + index + 1)
            val prefetchUrl = prefetchUri.toString()
            val future = CompletableFuture<ByteArray>()
            if (segments.putIfAbsent(prefetchUrl, future) != null) return@repeat
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
                    val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    if (responseCode in 200..299) {
                        future.complete(bytes)
                    } else {
                        future.completeExceptionally(IOException("HTTP $responseCode"))
                        segments.remove(prefetchUrl, future)
                        Log.d(TAG, "Recorded segment prefetch skipped. [code=$responseCode, url=$prefetchUrl]")
                    }
                } catch (error: Exception) {
                    future.completeExceptionally(error)
                    segments.remove(prefetchUrl, future)
                    Log.d(TAG, "Recorded segment prefetch failed. [url=$prefetchUrl]", error)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }
}

internal fun buildRecordedDataSourceFactory(
    nativeLib: NativeLib,
    httpDataSourceFactory: DefaultHttpDataSource.Factory,
    constructionKey: RecordedPlayerConstructionKey,
    smbServerList: List<SmbServer>,
    scope: CoroutineScope,
    fileSizeBytesRef: AtomicLong,
    fileSizeReferenceDurationUsRef: AtomicLong,
    programRef: AtomicReference<RecordedProgram?>,
    programDurationUsRef: AtomicLong,
): DataSource.Factory {
    val segmentPrefetchCache = SegmentPrefetchCache()
    return DataSource.Factory {
        object : DataSource {
            private var activeDataSource: DataSource? = null
            private val transferListeners = mutableListOf<TransferListener>()

            override fun addTransferListener(transferListener: TransferListener) {
                transferListeners.add(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val isSmb = dataSpec.uri.scheme == "smb"
                val isEdcbScheme = dataSpec.uri.scheme == "edcb"
                val isDirectTs = dataSpec.uri.path?.endsWith(".ts", ignoreCase = true) == true ||
                    dataSpec.uri.path?.endsWith("m2ts", ignoreCase = true) == true
                var isHttpSource = false
                val source = when {
                    isSmb -> {
                        val host = dataSpec.uri.host.orEmpty()
                        val server = smbServerList.find { it.ip.substringBefore("/") == host }
                        val smbContext = SmbContextBuilder.build(server?.user.orEmpty(), server?.password.orEmpty())
                        SmbDataSourceFactory(smbContext).createDataSource()
                    }
                    isEdcbScheme || isDirectTs || constructionKey.isEdcbDirect ||
                        constructionKey.isOriginalMpegTsPlayback -> TsReadExDataSource(
                        nativeLib = nativeLib,
                        tsArgs = RecordedMpegTsPassthroughPolicy.tsReadExArguments(
                            constructionKey.tsreadexServiceId
                        ),
                        fileSizeBytesRef = fileSizeBytesRef,
                        requestHeaders = mapOf("User-Agent" to "DTVClient/1.0"),
                        growingHttpStream = constructionKey.isOriginalMpegTsPlayback &&
                            constructionKey.isRecordingChasePlayback,
                    )
                    else -> {
                        isHttpSource = true
                        if (constructionKey.isRawMmtsPlayback && constructionKey.isRecordingChasePlayback) {
                            GrowingHttpDataSource(
                                upstreamFactory = httpDataSourceFactory,
                                onKnownFileSize = { totalFileSize ->
                                    fileSizeBytesRef.updateAndGet { maxOf(it, totalFileSize) }
                                    val recordedDurationUs = programRef.get()?.currentChaseDurationMs()
                                        ?.times(1_000L) ?: programDurationUsRef.get()
                                    fileSizeReferenceDurationUsRef.updateAndGet {
                                        maxOf(it, recordedDurationUs)
                                    }
                                },
                                reportSnapshotLength = true,
                            )
                        } else {
                            httpDataSourceFactory.createDataSource()
                        }
                    }
                }

                transferListeners.forEach(source::addTransferListener)
                activeDataSource = source
                val requestSpec = if (isHttpSource && shouldBypassPlaylistCache(dataSpec)) {
                    val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                        put("Cache-Control", "no-cache")
                        put("Pragma", "no-cache")
                    }
                    dataSpec.buildUpon()
                        .setUri(withFreshPlaylistCacheKey(dataSpec.uri))
                        .setHttpRequestHeaders(headers)
                        .build()
                } else {
                    dataSpec
                }
                if (isHttpSource && isRecordedSegmentUri(requestSpec.uri)) {
                    segmentPrefetchCache.take(requestSpec.uri)?.let { bytes ->
                        val prefetchedSource = PrefetchedSegmentDataSource(bytes, requestSpec.uri)
                        activeDataSource = prefetchedSource
                        return prefetchedSource.open(requestSpec).also {
                            segmentPrefetchCache.prefetch(scope, requestSpec.uri)
                        }
                    }
                }
                val openedLength = try {
                    source.open(requestSpec)
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    Log.e(TAG, "HTTP source open failed. [code=${error.responseCode}, uri=${requestSpec.uri}]", error)
                    throw error
                }
                if ((constructionKey.isOriginalMpegTsPlayback || constructionKey.isRawMmtsPlayback) &&
                    openedLength != C.LENGTH_UNSET.toLong()
                ) {
                    fileSizeBytesRef.updateAndGet { maxOf(it, requestSpec.position + openedLength) }
                    if (constructionKey.isRecordingChasePlayback) {
                        val recordedDurationUs = programRef.get()?.currentChaseDurationMs()
                            ?.times(1_000L) ?: programDurationUsRef.get()
                        fileSizeReferenceDurationUsRef.updateAndGet { maxOf(it, recordedDurationUs) }
                    }
                }
                if (isHttpSource && isRecordedSegmentUri(requestSpec.uri)) {
                    segmentPrefetchCache.prefetch(scope, requestSpec.uri)
                }
                return openedLength
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeDataSource?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

            override fun getUri(): Uri? = activeDataSource?.uri

            override fun close() {
                activeDataSource?.close()
                activeDataSource = null
            }
        }
    }
}

internal fun buildRecordedExtractorsFactory(
    constructionKey: RecordedPlayerConstructionKey,
    epgDurationUs: Long,
    programRef: AtomicReference<RecordedProgram?>,
    programDurationUsRef: AtomicLong,
    fileSizeBytesRef: AtomicLong,
    fileSizeReferenceDurationUsRef: AtomicLong,
    onRawMmtsSubtitleData: (com.beeregg2001.komorebi.util.mmts.B62SubtitleSample) -> Unit,
    dataBroadcastingCallback: B60DataBroadcastingCallback?,
): ExtractorsFactory = ExtractorsFactory {
    if (constructionKey.isRawMmtsPlayback) {
        return@ExtractorsFactory TlvExtractorsFactory(
            preferredVideoPacketId = null,
            enableSeeking = true,
            enableDurationProbe = true,
            growing = constructionKey.isRecordingChasePlayback,
            growingDurationLimitUs = epgDurationUs,
            growingDurationFallbackUsProvider = {
                programRef.get()?.currentChaseDurationMs()
                    ?.minus(GROWING_FILE_LIVE_EDGE_SAFETY_MS)
                    ?.coerceAtLeast(0L)
                    ?.times(1_000L) ?: C.TIME_UNSET
            },
            sourceLengthProvider = fileSizeBytesRef::get,
            onSubtitleDataReceived = onRawMmtsSubtitleData,
            dataBroadcastingCallback = dataBroadcastingCallback,
        ).createExtractors()
    }

    val extractors: Array<Extractor> = if (constructionKey.isOriginalMpegTsPlayback) {
        arrayOf(
            TsExtractor(
                TsExtractor.MODE_SINGLE_PMT,
                TimestampAdjuster(C.TIME_UNSET),
                DefaultTsPayloadReaderFactory(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                ),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
            )
        )
    } else {
        DefaultExtractorsFactory().apply {
            setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            setTsExtractorMode(TsExtractor.MODE_SINGLE_PMT)
            setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
        }.createExtractors()
    }

    val tsSeekDurationUs = if (constructionKey.isOriginalMpegTsPlayback &&
        constructionKey.isRecordingChasePlayback
    ) epgDurationUs else programDurationUsRef.get()
    if (RecordedByteSeekPolicy.shouldWrapTsSeekMap(
            isEdcbDirect = constructionKey.isEdcbDirect,
            isOriginalMpegTsPlayback = constructionKey.isOriginalMpegTsPlayback,
            durationUs = tsSeekDurationUs,
        )
    ) {
        for (index in extractors.indices) {
            val extractor = extractors[index]
            if (extractor is TsExtractor) {
                extractors[index] = seekMapWrappedTsExtractor(
                    extractor = extractor,
                    constructionKey = constructionKey,
                    programRef = programRef,
                    programDurationUsRef = programDurationUsRef,
                    fileSizeBytesRef = fileSizeBytesRef,
                    fileSizeReferenceDurationUsRef = fileSizeReferenceDurationUsRef,
                )
            }
        }
    }
    extractors
}

private fun seekMapWrappedTsExtractor(
    extractor: TsExtractor,
    constructionKey: RecordedPlayerConstructionKey,
    programRef: AtomicReference<RecordedProgram?>,
    programDurationUsRef: AtomicLong,
    fileSizeBytesRef: AtomicLong,
    fileSizeReferenceDurationUsRef: AtomicLong,
): Extractor = object : Extractor {
    private var downstreamOutput: ExtractorOutput? = null
    private var growingSeekMap: SeekMap? = null
    private var lastSeekMapPublishRealtimeMs = C.TIME_UNSET

    override fun sniff(input: ExtractorInput): Boolean = extractor.sniff(input)

    override fun init(output: ExtractorOutput) {
        downstreamOutput = output
        extractor.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                val customSeekMap = object : SeekMap {
                    override fun isSeekable(): Boolean = RecordedByteSeekPolicy.isEstimatedByteSeekable(
                        sourceLengthBytes = fileSizeBytesRef.get(),
                        durationUs = durationUs,
                    )

                    override fun getDurationUs(): Long {
                        if (!constructionKey.isOriginalMpegTsPlayback ||
                            !constructionKey.isRecordingChasePlayback
                        ) return programDurationUsRef.get()
                        val elapsedMs = programRef.get()?.currentChaseDurationMs()
                            ?: (programDurationUsRef.get() / 1_000L)
                        return (elapsedMs - GROWING_FILE_LIVE_EDGE_SAFETY_MS)
                            .coerceAtLeast(0L) * 1_000L
                    }

                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                        val size = fileSizeBytesRef.get()
                        if (size <= 0L) return SeekMap.SeekPoints(SeekPoint.START)
                        val currentDurationUs = durationUs.coerceAtLeast(1L)
                        val safeTime = timeUs.coerceIn(0L, currentDurationUs)
                        val referenceDurationUs = if (constructionKey.isOriginalMpegTsPlayback &&
                            constructionKey.isRecordingChasePlayback
                        ) fileSizeReferenceDurationUsRef.get().coerceAtLeast(1L) else currentDurationUs
                        val calculatedPosition =
                            (safeTime.toDouble() / referenceDurationUs * size).toLong()
                        val maxPosition = (size - 188L * 512L).coerceAtLeast(0L)
                        return SeekMap.SeekPoints(
                            SeekPoint(safeTime, calculatedPosition.coerceAtMost(maxPosition))
                        )
                    }
                }
                growingSeekMap = customSeekMap
                lastSeekMapPublishRealtimeMs = SystemClock.elapsedRealtime()
                output.seekMap(customSeekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = extractor.read(input, seekPosition)
        if (constructionKey.isOriginalMpegTsPlayback && constructionKey.isRecordingChasePlayback) {
            val nowRealtimeMs = SystemClock.elapsedRealtime()
            if (lastSeekMapPublishRealtimeMs == C.TIME_UNSET ||
                nowRealtimeMs - lastSeekMapPublishRealtimeMs >= GROWING_SEEK_MAP_REFRESH_MS
            ) {
                growingSeekMap?.let { downstreamOutput?.seekMap(it) }
                lastSeekMapPublishRealtimeMs = nowRealtimeMs
            }
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) = extractor.seek(position, timeUs)

    override fun release() {
        downstreamOutput = null
        growingSeekMap = null
        extractor.release()
    }
}
