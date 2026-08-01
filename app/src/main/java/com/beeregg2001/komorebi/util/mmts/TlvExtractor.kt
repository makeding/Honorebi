@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.util.mmts

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.ts.H265Reader
import androidx.media3.extractor.ts.LatmReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.IOException

private const val TAG = "TlvExtractor"
private const val INPUT_BUFFER_SIZE = 512 * 1024
private const val MMTS_CONTAINER_MIME_TYPE = "application/x-arib-mmts"

private fun subtitleTrackPriority(componentTag: Int): Int = when (componentTag) {
    in 0x30..0x37 -> 2 // programme caption
    in 0x38..0x3f -> 1 // superimpose
    else -> 0
}

data class B62SubtitleSample(
    val timeUs: Long,
    val data: ByteArray,
    val operationMode: Int,
    val timingMode: Int,
    val referenceStartTimeUs: Long?,
    val mpuSequenceNumber: Long?,
    val discontinuity: Boolean
)

class TlvExtractorsFactory(
    private val preferredVideoPacketId: Int?,
    private val enableSeeking: Boolean = false,
    private val enableDurationProbe: Boolean = false,
    private val growing: Boolean = false,
    private val growingDurationLimitUs: Long = C.TIME_UNSET,
    private val growingDurationFallbackUsProvider: (() -> Long)? = null,
    private val sourceLengthProvider: (() -> Long)? = null,
    private val durationUs: Long = C.TIME_UNSET,
    private val onSubtitleDataReceived: (B62SubtitleSample) -> Unit = {},
    private val dataBroadcastingCallback: B60DataBroadcastingCallback? = null
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(
        TlvExtractor(
            preferredVideoPacketId,
            enableSeeking,
            enableDurationProbe,
            growing,
            growingDurationLimitUs,
            growingDurationFallbackUsProvider,
            sourceLengthProvider,
            durationUs,
            onSubtitleDataReceived,
            dataBroadcastingCallback
        )
    )
}

/**
 * Raw ARIB MMT/TLV を libtlvdemux.so で Access Unit に分解し、Media3 の既存 ElementaryStreamReader
 * へ渡す Extractor。ARIB STD-B62 TTML は PTS とともにプレイヤー層へ直接通知する。
 */
class TlvExtractor(
    private val preferredVideoPacketId: Int?,
    private val enableSeeking: Boolean,
    private val enableDurationProbe: Boolean,
    private val growing: Boolean,
    private val growingDurationLimitUs: Long,
    private val growingDurationFallbackUsProvider: (() -> Long)?,
    private val sourceLengthProvider: (() -> Long)?,
    private val durationUs: Long,
    private val onSubtitleDataReceived: (B62SubtitleSample) -> Unit,
    private val dataBroadcastingCallback: B60DataBroadcastingCallback?
) : Extractor, NativeTlvDemuxer.Callback {

    private val inputBuffer = ByteArray(INPUT_BUFFER_SIZE)
    private val trackIdGenerator = TsPayloadReader.TrackIdGenerator(0, 1)
    private val audioReaders = linkedMapOf<Long, LatmReader>()

    private var extractorOutput: ExtractorOutput? = null
    private var nativeDemuxer: NativeTlvDemuxer? = null
    private var videoReader: H265Reader? = null
    private var videoTrackId: Long? = null
    private var subtitleTrackId: Long? = null
    private var subtitleTrackPriority: Int = -1
    private var subtitleOperationMode: Int = 1
    private var subtitleTimingMode: Int = 3
    private var tracksEnded = false
    private var fatalError: IOException? = null
    private var seekMapSent = false
    private var resolvedDurationUs = durationUs
    private var durationProbe: NativeTlvDurationProbe? = null
    private var durationProbeRange: TlvDurationProbeRange? = null
    private var durationProbeRangeBytesRead = 0L
    private var durationProbeFinished = false
    private var growingDurationBaseUs = C.TIME_UNSET
    private var growingDurationBaseRealtimeMs = C.TIME_UNSET
    private var knownInputLength = C.LENGTH_UNSET.toLong()
    private var recordingSeekMap: RecordingSeekMap? = null
    private var lastPublishedGrowingDurationUs = C.TIME_UNSET

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        nativeDemuxer = NativeTlvDemuxer(
            callback = this,
            preferredVideoPacketId = preferredVideoPacketId,
            buildRecordingIndex = enableSeeking
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        fatalError?.let { throw it }
        updateKnownInputLength(input.length)
        if (!seekMapSent) {
            readDurationProbe(input, seekPosition)?.let { return it }
            val seekMap =
                if (enableSeeking && currentDurationUs() > 0L && currentInputLength() > 0L) {
                    RecordingSeekMap().also { recordingSeekMap = it }
                } else {
                    SeekMap.Unseekable(
                        currentDurationUs().takeIf { it > 0L } ?: C.TIME_UNSET
                    )
                }
            extractorOutput?.seekMap(seekMap)
            seekMapSent = true
            lastPublishedGrowingDurationUs = seekMap.durationUs
        } else {
            refreshGrowingSeekMap()
        }
        val read = input.read(inputBuffer, 0, inputBuffer.size)
        if (read == C.RESULT_END_OF_INPUT) {
            nativeDemuxer?.flush()
            fatalError?.let { throw it }
            if (!tracksEnded) {
                throw IOException("Raw MMTS ended before playable HEVC/AAC tracks were found")
            }
            return Extractor.RESULT_END_OF_INPUT
        }

        nativeDemuxer?.push(inputBuffer, read)
        fatalError?.let { throw it }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        fatalError = null
        nativeDemuxer?.reposition(position.coerceAtLeast(0L))
        videoReader?.seek()
        audioReaders.values.forEach { it.seek() }
    }

    override fun release() {
        durationProbe?.close()
        durationProbe = null
        nativeDemuxer?.close()
        nativeDemuxer = null
        extractorOutput = null
    }

    private fun readDurationProbe(
        input: ExtractorInput,
        seekPosition: PositionHolder
    ): Int? {
        if (
            durationProbeFinished ||
            !enableSeeking ||
            !enableDurationProbe ||
            resolvedDurationUs > 0L
        ) {
            return null
        }

        val inputLength = currentInputLength()
        if (inputLength <= 0L) {
            durationProbeFinished = true
            Log.w(TAG, "MMTS duration probe skipped: source length is unknown")
            return null
        }

        if (durationProbe == null) {
            durationProbe = try {
                NativeTlvDurationProbe(inputLength, preferredVideoPacketId)
            } catch (error: IllegalStateException) {
                durationProbeFinished = true
                Log.w(TAG, "MMTS duration probe could not start", error)
                return null
            }
        }

        val probe = durationProbe ?: return null
        val result = probe.result()
        if (result.state != NativeTlvDurationProbe.STATE_NEED_RANGE) {
            if (
                result.state == NativeTlvDurationProbe.STATE_COMPLETE &&
                result.durationUs > 0L
            ) {
                resolvedDurationUs = result.durationUs
                if (growing) {
                    growingDurationBaseUs = result.durationUs
                    growingDurationBaseRealtimeMs = SystemClock.elapsedRealtime()
                }
                Log.i(
                    TAG,
                    "MMTS duration probe complete: duration_us=${result.durationUs}, " +
                        "transferred_bytes=${result.transferredBytes}"
                )
            } else {
                Log.w(
                    TAG,
                    "MMTS duration probe unavailable: state=${result.state}, " +
                        "failure=${result.failure}, transferred_bytes=${result.transferredBytes}"
                )
            }
            probe.close()
            durationProbe = null
            durationProbeRange = null
            durationProbeRangeBytesRead = 0L
            durationProbeFinished = true
            if (input.position != 0L) {
                seekPosition.position = 0L
                return Extractor.RESULT_SEEK
            }
            return null
        }

        val range = durationProbeRange ?: probe.nextRange()?.also {
            durationProbeRange = it
            durationProbeRangeBytesRead = 0L
        }
        if (range == null) {
            Log.w(TAG, "MMTS duration probe requested data without a range")
            probe.close()
            durationProbe = null
            durationProbeFinished = true
            if (input.position != 0L) {
                seekPosition.position = 0L
                return Extractor.RESULT_SEEK
            }
            return null
        }

        val expectedPosition = range.offset + durationProbeRangeBytesRead
        if (input.position != expectedPosition) {
            seekPosition.position = expectedPosition
            return Extractor.RESULT_SEEK
        }

        val remaining = range.length - durationProbeRangeBytesRead
        if (remaining <= 0L) {
            durationProbeRange = null
            durationProbeRangeBytesRead = 0L
            return Extractor.RESULT_CONTINUE
        }
        val bytesToRead = minOf(inputBuffer.size.toLong(), remaining).toInt()
        val read = input.read(inputBuffer, 0, bytesToRead)
        if (read == C.RESULT_END_OF_INPUT) {
            probe.pushRange(
                range.requestId,
                expectedPosition,
                inputBuffer,
                0,
                endOfRange = true
            )
            return Extractor.RESULT_CONTINUE
        }

        durationProbeRangeBytesRead += read.toLong()
        val endOfRange = durationProbeRangeBytesRead == range.length
        val accepted = probe.pushRange(
            range.requestId,
            expectedPosition,
            inputBuffer,
            read,
            endOfRange
        )
        if (!accepted) {
            Log.w(TAG, "MMTS duration probe rejected range data at $expectedPosition")
        }
        if (endOfRange) {
            durationProbeRange = null
            durationProbeRangeBytesRead = 0L
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun updateKnownInputLength(inputLength: Long) {
        val providedLength = sourceLengthProvider?.invoke() ?: C.LENGTH_UNSET.toLong()
        knownInputLength = maxOf(knownInputLength, inputLength, providedLength)
    }

    private fun currentInputLength(): Long {
        updateKnownInputLength(C.LENGTH_UNSET.toLong())
        return knownInputLength
    }

    private fun currentDurationUs(): Long {
        if (!growing) return resolvedDurationUs

        val probedDurationUs = if (
            growingDurationBaseUs > 0L &&
            growingDurationBaseRealtimeMs > 0L
        ) {
            growingDurationBaseUs +
                (SystemClock.elapsedRealtime() - growingDurationBaseRealtimeMs)
                    .coerceAtLeast(0L) * 1_000L
        } else {
            C.TIME_UNSET
        }
        val fallbackDurationUs = growingDurationFallbackUsProvider?.invoke() ?: C.TIME_UNSET
        val durationUs = maxOf(probedDurationUs, fallbackDurationUs)
        if (durationUs <= 0L) return C.TIME_UNSET
        return if (growingDurationLimitUs > 0L) {
            durationUs.coerceAtMost(growingDurationLimitUs)
        } else {
            durationUs
        }
    }

    private fun refreshGrowingSeekMap() {
        if (!growing) return
        val seekMap = recordingSeekMap ?: return
        val durationUs = currentDurationUs()
        if (
            durationUs <= 0L ||
            lastPublishedGrowingDurationUs > 0L &&
            durationUs - lastPublishedGrowingDurationUs < GROWING_SEEK_MAP_REFRESH_US
        ) {
            return
        }
        extractorOutput?.seekMap(seekMap)
        lastPublishedGrowingDurationUs = durationUs
    }

    override fun onService(contextId: Long, packageId: ByteArray) {
        Log.i(TAG, "MMTS service: context=$contextId packageId=${packageId.toHexString()}")
    }

    override fun onTrack(
        trackId: Long,
        contextId: Long,
        packetId: Int,
        kind: Int,
        codec: Int,
        language: String,
        componentTag: Int,
        timescale: Long,
        audioChannelLayout: Int,
        audioSampleRate: Int,
        audioMainComponent: Boolean,
        subtitleOperationMode: Int,
        subtitleTimingMode: Int
    ) {
        if (tracksEnded && codec != CODEC_TTML) return
        val output = extractorOutput ?: return
        when {
            codec == CODEC_HEVC && videoReader == null &&
                (preferredVideoPacketId == null || preferredVideoPacketId == packetId) -> {
                videoTrackId = trackId
                videoReader = H265Reader(
                    SeiReader(emptyList(), MMTS_CONTAINER_MIME_TYPE),
                    MMTS_CONTAINER_MIME_TYPE
                ).also { it.createTracks(output, trackIdGenerator) }
                Log.i(TAG, "MMTS video track: context=$contextId packetId=0x${packetId.toString(16)}")
            }

            codec == CODEC_AAC_LATM &&
                audioChannelLayout != AUDIO_LAYOUT_22_2 &&
                !audioReaders.containsKey(trackId) -> {
                audioReaders[trackId] = LatmReader(
                    language.ifBlank { null },
                    0,
                    MMTS_CONTAINER_MIME_TYPE
                ).also { it.createTracks(output, trackIdGenerator) }
                Log.i(
                    TAG,
                    "MMTS audio track: context=$contextId packetId=0x${packetId.toString(16)} " +
                        "language=$language layout=${audioLayoutName(audioChannelLayout)} " +
                        "sampleRate=$audioSampleRate main=$audioMainComponent"
                )
            }

            codec == CODEC_AAC_LATM -> Log.i(
                TAG,
                "Ignoring unsupported MMTS audio track: packetId=0x${packetId.toString(16)} " +
                    "layout=${audioLayoutName(audioChannelLayout)}"
            )

            codec == CODEC_TTML -> {
                val priority = subtitleTrackPriority(componentTag)
                if (subtitleTrackId == null || priority > subtitleTrackPriority) {
                    subtitleTrackId = trackId
                    subtitleTrackPriority = priority
                    this.subtitleOperationMode = subtitleOperationMode.takeIf { it >= 0 } ?: 1
                    this.subtitleTimingMode = subtitleTimingMode.takeIf { it >= 0 } ?: 3
                    Log.i(
                        TAG,
                        "MMTS subtitle track: packetId=0x${packetId.toString(16)} " +
                            "componentTag=0x${componentTag.toString(16)} language=$language"
                    )
                }
            }
        }
    }

    override fun onAccessUnit(
        trackId: Long,
        codec: Int,
        data: ByteArray,
        dataLength: Int,
        ptsValue: Long,
        ptsTimescale: Long,
        dtsValue: Long,
        dtsTimescale: Long,
        inputOffset: Long,
        mpuSequenceNumber: Long,
        subtitleReferenceStartPtsValue: Long,
        subtitleReferenceStartPtsTimescale: Long,
        randomAccess: Boolean,
        discontinuity: Boolean
    ) {
        if (fatalError != null) return
        ensureTracksEnded(inputOffset)
        val timeUs = scaleToMicroseconds(ptsValue, ptsTimescale)
        val flags = if (randomAccess) TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR else 0
        // HEVC/AAC callbacks reuse a JNI-owned scratch array. Readers consume it
        // synchronously, and dataLength keeps the unused capacity out of the sample.
        val payload = ParsableByteArray(data, dataLength)

        when (codec) {
            CODEC_HEVC -> {
                if (trackId != videoTrackId) return
                videoReader?.let { reader ->
                    if (discontinuity) reader.seek()
                    reader.packetStarted(timeUs, flags)
                    reader.consume(payload)
                    reader.packetFinished(false)
                }
            }

            CODEC_AAC_LATM -> audioReaders[trackId]?.let { reader ->
                if (discontinuity) reader.seek()
                reader.packetStarted(timeUs, flags)
                reader.consume(payload)
                reader.packetFinished(false)
            }

            CODEC_TTML -> {
                if (trackId != subtitleTrackId) return
                val subtitleData = if (dataLength == data.size) data else data.copyOf(dataLength)
                onSubtitleDataReceived(
                    B62SubtitleSample(
                        timeUs = timeUs,
                        data = subtitleData,
                        operationMode = subtitleOperationMode,
                        timingMode = subtitleTimingMode,
                        referenceStartTimeUs = subtitleReferenceStartPtsValue
                            .takeIf { subtitleReferenceStartPtsTimescale > 0L }
                            ?.let { scaleToMicroseconds(it, subtitleReferenceStartPtsTimescale) },
                        mpuSequenceNumber = mpuSequenceNumber.takeIf { it >= 0L },
                        discontinuity = discontinuity
                    )
                )
            }
        }
    }

    override fun onBroadcastClock(
        mediaTimeValue: Long,
        mediaTimeTimescale: Long,
        broadcastTimeValue: Long,
        broadcastTimeTimescale: Long,
        inputOffset: Long,
        discontinuity: Boolean
    ) {
        dataBroadcastingCallback?.onBroadcastClock(
            B60BroadcastClock(
                mediaTimeValue = mediaTimeValue,
                mediaTimeTimescale = mediaTimeTimescale,
                broadcastTimeValue = broadcastTimeValue,
                broadcastTimeTimescale = broadcastTimeTimescale,
                inputOffset = inputOffset,
                discontinuity = discontinuity
            )
        )
    }

    override fun onEventInfo(
        contextId: Long,
        tableId: Int,
        currentNext: Boolean,
        sectionNumber: Int,
        serviceId: Int,
        tlvStreamId: Int,
        originalNetworkId: Int,
        eventId: Int,
        startTimeUnixMilliseconds: Long,
        durationSeconds: Long,
        runningStatus: Int,
        freeCaMode: Boolean,
        language: String,
        title: String,
        description: String
    ) {
        dataBroadcastingCallback?.onEventInfo(
            B60EventInfo(
                contextId = contextId,
                tableId = tableId,
                currentNext = currentNext,
                sectionNumber = sectionNumber,
                serviceId = serviceId,
                tlvStreamId = tlvStreamId,
                originalNetworkId = originalNetworkId,
                eventId = eventId,
                startTimeUnixMilliseconds = startTimeUnixMilliseconds.takeIf { it >= 0L },
                durationSeconds = durationSeconds.takeIf { it >= 0L },
                runningStatus = runningStatus,
                freeCaMode = freeCaMode,
                language = language,
                title = title,
                description = description
            )
        )
    }

    override fun onApplicationState(
        contextId: Long,
        entryPath: String,
        transportUrls: Array<String>,
        collectionState: Int,
        resourceCount: Long,
        entryReady: Boolean
    ) {
        dataBroadcastingCallback?.onApplicationState(
            contextId,
            entryPath,
            transportUrls.asList(),
            collectionState,
            resourceCount,
            entryReady
        )
    }

    override fun onApplicationResource(
        contextId: Long,
        path: String,
        contentType: String,
        data: ByteArray,
        version: Int
    ) {
        dataBroadcastingCallback?.onApplicationResource(
            B60ApplicationResource(contextId, path, contentType, data, version)
        )
    }

    override fun onApplicationResourcesReset() {
        dataBroadcastingCallback?.onApplicationResourcesReset()
    }

    override fun onError(code: Int, inputOffset: Long, recoverable: Boolean, message: String) {
        val detail = "code=$code offset=$inputOffset message=$message"
        if (recoverable) {
            Log.w(TAG, "Recoverable MMTS demux error: $detail")
        } else {
            fatalError = IOException("Fatal MMTS demux error: $detail")
        }
    }

    private fun ensureTracksEnded(inputOffset: Long) {
        if (tracksEnded) return
        if (videoReader == null) {
            fatalError = IOException(
                "Raw MMTS does not contain the requested HEVC track " +
                    "(packetId=${preferredVideoPacketId?.let { "0x${it.toString(16)}" } ?: "auto"}, offset=$inputOffset)"
            )
            return
        }
        if (audioReaders.isEmpty()) {
            fatalError = IOException("Raw MMTS does not contain a supported AAC-LATM track (offset=$inputOffset)")
            return
        }
        extractorOutput?.endTracks()
        tracksEnded = true
    }

    private fun scaleToMicroseconds(value: Long, timescale: Long): Long {
        if (timescale <= 0L) return 0L
        return Util.scaleLargeTimestamp(value, C.MICROS_PER_SECOND, timescale)
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

    private inner class RecordingSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = currentDurationUs()

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val recordingDurationUs = currentDurationUs().coerceAtLeast(1L)
            val inputLength = currentInputLength()
            val targetUs = timeUs.coerceIn(0L, recordingDurationUs)
            val indexed = nativeDemuxer?.getSeekPoints(targetUs) ?: longArrayOf()
            if (indexed.size >= 4 && indexed[0] >= 0L && indexed[1] >= 0L) {
                val distanceFromIndexedPoint = targetUs - indexed[0]
                if (distanceFromIndexedPoint <= MAX_INDEXED_SEEK_DISTANCE_US || indexed[2] >= 0L) {
                    val first = androidx.media3.extractor.SeekPoint(indexed[0], indexed[1])
                    if (indexed[2] >= 0L && indexed[3] >= 0L) {
                        return SeekMap.SeekPoints(
                            first,
                            androidx.media3.extractor.SeekPoint(indexed[2], indexed[3])
                        )
                    }
                    return SeekMap.SeekPoints(first)
                }
            }

            val estimatedPosition = if (recordingDurationUs <= 0L || inputLength <= 0L) {
                0L
            } else {
                ((targetUs.toDouble() / recordingDurationUs.toDouble()) * inputLength.toDouble())
                    .toLong()
                    .minus(SEEK_PROBE_BACKOFF_BYTES)
                    .coerceIn(0L, (inputLength - 1L).coerceAtLeast(0L))
            }
            return SeekMap.SeekPoints(
                androidx.media3.extractor.SeekPoint(targetUs, estimatedPosition)
            )
        }
    }

    private fun audioLayoutName(layout: Int): String = when (layout) {
        AUDIO_LAYOUT_STEREO -> "stereo"
        AUDIO_LAYOUT_5_1 -> "5.1ch"
        AUDIO_LAYOUT_22_2 -> "22.2ch"
        else -> "layout-$layout"
    }

    companion object {
        private const val CODEC_HEVC = 0
        private const val CODEC_AAC_LATM = 1
        private const val CODEC_TTML = 2
        private const val AUDIO_LAYOUT_STEREO = 3
        private const val AUDIO_LAYOUT_5_1 = 9
        private const val AUDIO_LAYOUT_22_2 = 14
        private const val MAX_INDEXED_SEEK_DISTANCE_US = 30L * C.MICROS_PER_SECOND
        private const val SEEK_PROBE_BACKOFF_BYTES = 16L * 1024L * 1024L
        private const val GROWING_SEEK_MAP_REFRESH_US = 30L * C.MICROS_PER_SECOND
    }
}
