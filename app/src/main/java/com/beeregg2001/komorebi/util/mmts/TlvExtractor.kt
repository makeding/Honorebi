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

private data class PendingAudioTrack(
    val trackId: Long,
    val contextId: Long,
    val packetId: Int,
    val language: String,
    val channelLayout: Int,
    val sampleRate: Int,
    val mainComponent: Boolean,
    val groupIdentifications: IntArray,
    val selectionLevels: IntArray
) {
    fun assetGroupsDescription(): String = groupIdentifications.indices.joinToString(
        prefix = "[",
        postfix = "]"
    ) { index ->
        "${groupIdentifications[index]}:${selectionLevels.getOrElse(index) { -1 }}"
    }
}

private data class PendingSubtitleTrack(
    val type: Int,
    val operationMode: Int,
    val timingMode: Int
)

data class B62SubtitleSample(
    val timeUs: Long,
    val data: ByteArray,
    val type: Int,
    val operationMode: Int,
    val timingMode: Int,
    val referenceStartTimeUs: Long?,
    val mpuSequenceNumber: Long?,
    val resources: List<B62SubtitleResource>,
    val discontinuity: Boolean
)

data class B62SubtitleResource(
    val index: Int,
    val dataType: Int,
    val data: ByteArray
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
    private val onTracksChanged: (List<TlvTrackInfo>) -> Unit = {},
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
            onTracksChanged,
            onSubtitleDataReceived,
            dataBroadcastingCallback
        )
    )
}

/**
 * Raw ARIB MMT/TLV を libaribtlv で Access Unit に分解し、tlvdemux の playback helper を通して
 * Media3 の既存 ElementaryStreamReader
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
    private val onTracksChanged: (List<TlvTrackInfo>) -> Unit,
    private val onSubtitleDataReceived: (B62SubtitleSample) -> Unit,
    private val dataBroadcastingCallback: B60DataBroadcastingCallback?
) : Extractor, NativeTlvDemuxer.Callback {

    private val inputBuffer = ByteArray(INPUT_BUFFER_SIZE)
    private val videoReaders = linkedMapOf<Long, H265Reader>()
    private val audioReaders = linkedMapOf<Long, LatmReader>()
    private val trackInventory = linkedMapOf<Int, TlvTrackInfo>()

    private var extractorOutput: ExtractorOutput? = null
    private var nativeDemuxer: NativeTlvDemuxer? = null
    private val subtitleTracks = linkedMapOf<Long, PendingSubtitleTrack>()
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
    private var pendingRecoveryOffset: Long? = null

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        nativeDemuxer = NativeTlvDemuxer(
            callback = this,
            preferredVideoPacketId = preferredVideoPacketId,
            buildRecordingIndex = enableSeeking,
            exposeAllVideoTracks = !enableSeeking
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        fatalError?.let { throw it }
        pendingRecoveryOffset?.let { recoveryOffset ->
            pendingRecoveryOffset = null
            seekPosition.position = recoveryOffset
            return Extractor.RESULT_SEEK
        }
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
        pendingRecoveryOffset = null
        nativeDemuxer?.reposition(position.coerceAtLeast(0L))
        videoReaders.values.forEach { it.seek() }
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
        assetGroupIdentifications: IntArray,
        assetGroupSelectionLevels: IntArray,
        audioChannelLayout: Int,
        audioSampleRate: Int,
        audioMainComponent: Boolean,
        subtitleType: Int,
        subtitleOperationMode: Int,
        subtitleTimingMode: Int
    ) {
        if (tracksEnded && codec != CODEC_TTML) return
        val output = extractorOutput ?: return
        when {
            codec == CODEC_HEVC && !videoReaders.containsKey(trackId) -> {
                videoReaders[trackId] = H265Reader(
                    SeiReader(emptyList(), MMTS_CONTAINER_MIME_TYPE),
                    MMTS_CONTAINER_MIME_TYPE
                ).also {
                    it.createTracks(output, TsPayloadReader.TrackIdGenerator(packetId, 1))
                }
                publishTrack(
                    TlvTrackInfo(
                        packetId = packetId,
                        kind = TlvTrackKind.VIDEO,
                        assetGroups = assetGroups(
                            assetGroupIdentifications,
                            assetGroupSelectionLevels
                        )
                    )
                )
                Log.i(
                    TAG,
                    "MMTS video track: context=$contextId packetId=0x${packetId.toString(16)} " +
                        "groups=${assetGroupsDescription(assetGroupIdentifications, assetGroupSelectionLevels)}"
                )
            }

            codec == CODEC_AAC_LATM -> addAudioTrack(
                PendingAudioTrack(
                    trackId = trackId,
                    contextId = contextId,
                    packetId = packetId,
                    language = language,
                    channelLayout = audioChannelLayout,
                    sampleRate = audioSampleRate,
                    mainComponent = audioMainComponent,
                    groupIdentifications = assetGroupIdentifications,
                    selectionLevels = assetGroupSelectionLevels
                )
            )

            codec == CODEC_TTML -> {
                if (subtitleType !in B62_SUBTITLE_TYPE_CAPTION..B62_SUBTITLE_TYPE_SUPERIMPOSE) return
                subtitleTracks[trackId] = PendingSubtitleTrack(
                    type = subtitleType,
                    operationMode = subtitleOperationMode.takeIf { it >= 0 } ?: 1,
                    timingMode = subtitleTimingMode.takeIf { it >= 0 } ?: 3
                )
                Log.i(
                    TAG,
                    "MMTS subtitle track: packetId=0x${packetId.toString(16)} " +
                        "type=$subtitleType language=$language"
                )
            }
        }
    }

    private fun addAudioTrack(track: PendingAudioTrack) {
        if (track.channelLayout == AUDIO_LAYOUT_22_2) {
            Log.i(
                TAG,
                "Ignoring unsupported MMTS audio track: " +
                    "packetId=0x${track.packetId.toString(16)} " +
                    "layout=${audioLayoutName(track.channelLayout)}"
            )
            return
        }
        if (audioReaders.containsKey(track.trackId)) return
        val output = extractorOutput ?: return
        audioReaders[track.trackId] = LatmReader(
            track.language.ifBlank { null },
            0,
            MMTS_CONTAINER_MIME_TYPE
        ).also {
            it.createTracks(output, TsPayloadReader.TrackIdGenerator(track.packetId, 1))
        }
        publishTrack(
            TlvTrackInfo(
                packetId = track.packetId,
                kind = TlvTrackKind.AUDIO,
                assetGroups = assetGroups(track.groupIdentifications, track.selectionLevels),
                audioMainComponent = track.mainComponent
            )
        )
        Log.i(
            TAG,
            "MMTS audio track: context=${track.contextId} " +
                "packetId=0x${track.packetId.toString(16)} " +
                "language=${track.language} layout=${audioLayoutName(track.channelLayout)} " +
                "sampleRate=${track.sampleRate} main=${track.mainComponent} " +
                "groups=${track.assetGroupsDescription()}"
        )
    }

    private fun publishTrack(track: TlvTrackInfo) {
        trackInventory[track.packetId] = track
        onTracksChanged(trackInventory.values.toList())
    }

    private fun assetGroups(
        identifications: IntArray,
        selectionLevels: IntArray
    ): List<TlvAssetGroup> = identifications.indices.mapNotNull { index ->
        selectionLevels.getOrNull(index)?.let { level ->
            TlvAssetGroup(identifications[index], level)
        }
    }

    private fun assetGroupsDescription(
        identifications: IntArray,
        selectionLevels: IntArray
    ): String = identifications.indices.joinToString(prefix = "[", postfix = "]") { index ->
        "${identifications[index]}:${selectionLevels.getOrElse(index) { -1 }}"
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
        subtitleResourceIndices: IntArray,
        subtitleResourceTypes: IntArray,
        subtitleResourceData: Array<ByteArray>,
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
            CODEC_HEVC -> videoReaders[trackId]?.let { reader ->
                    if (discontinuity) reader.seek()
                    reader.packetStarted(timeUs, flags)
                    reader.consume(payload)
                    reader.packetFinished()
            }

            CODEC_AAC_LATM -> audioReaders[trackId]?.let { reader ->
                if (discontinuity) reader.seek()
                reader.packetStarted(timeUs, flags)
                reader.consume(payload)
                reader.packetFinished()
            }

            CODEC_TTML -> {
                val subtitleTrack = subtitleTracks[trackId] ?: return
                val subtitleData = if (dataLength == data.size) data else data.copyOf(dataLength)
                onSubtitleDataReceived(
                    B62SubtitleSample(
                        timeUs = timeUs,
                        data = subtitleData,
                        type = subtitleTrack.type,
                        operationMode = subtitleTrack.operationMode,
                        timingMode = subtitleTrack.timingMode,
                        referenceStartTimeUs = subtitleReferenceStartPtsValue
                            .takeIf { subtitleReferenceStartPtsTimescale > 0L }
                            ?.let { scaleToMicroseconds(it, subtitleReferenceStartPtsTimescale) },
                        mpuSequenceNumber = mpuSequenceNumber.takeIf { it >= 0L },
                        resources = subtitleResourceData.indices.mapNotNull { index ->
                            val subsampleIndex = subtitleResourceIndices.getOrNull(index)
                                ?: return@mapNotNull null
                            val dataType = subtitleResourceTypes.getOrNull(index)
                                ?: return@mapNotNull null
                            B62SubtitleResource(
                                index = subsampleIndex,
                                dataType = dataType,
                                data = subtitleResourceData[index]
                            )
                        },
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

    override fun onPlaybackDamage(
        trackId: Long,
        startTimeUs: Long,
        endTimeUs: Long,
        recoveryTimeUs: Long,
        startInputOffset: Long,
        endInputOffset: Long,
        recoveryInputOffset: Long,
        recoveryRestartOffset: Long,
        severity: Int,
        action: Int
    ) {
        Log.w(
            TAG,
            "MMTS playback damage: track=$trackId start_us=$startTimeUs " +
                "end_us=$endTimeUs recovery_us=$recoveryTimeUs " +
                "start_offset=$startInputOffset end_offset=$endInputOffset " +
                "recovery_offset=$recoveryInputOffset restart_offset=$recoveryRestartOffset " +
                "severity=$severity action=$action"
        )
        if (
            action == NativeTlvDemuxer.PLAYBACK_RECOVERY_SEEK &&
            recoveryRestartOffset >= 0L
        ) {
            pendingRecoveryOffset = recoveryRestartOffset
        }
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
        applicationType: Int,
        organizationId: Int,
        applicationId: Long,
        controlCode: Int,
        applicationPriority: Int,
        entryPath: String,
        transportUrls: Array<String>,
        collectionState: Int,
        resourceCount: Long,
        entryReady: Boolean
    ) {
        dataBroadcastingCallback?.onApplicationState(
            contextId,
            applicationType,
            organizationId,
            applicationId,
            controlCode,
            applicationPriority,
            entryPath,
            transportUrls.asList(),
            collectionState,
            resourceCount,
            entryReady
        )
    }

    override fun onLayoutConfiguration(contextId: Long, backgroundColorRgb: Int) {
        dataBroadcastingCallback?.onLayoutConfiguration(
            contextId = contextId,
            backgroundColorRgb = backgroundColorRgb.takeIf { it >= 0 }
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
        val requestedVideoMissing = preferredVideoPacketId != null &&
            trackInventory[preferredVideoPacketId]?.kind != TlvTrackKind.VIDEO
        if (videoReaders.isEmpty() || requestedVideoMissing) {
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
                val hasFollowingIndexedPoint = indexed[2] >= 0L && indexed[3] >= 0L
                if (
                    shouldUseIndexedTlvSeekPoint(
                        distanceFromIndexedPointUs = distanceFromIndexedPoint,
                        recordingDurationUs = recordingDurationUs,
                        inputLengthBytes = inputLength,
                        hasFollowingIndexedPoint = hasFollowingIndexedPoint,
                    )
                ) {
                    val first = androidx.media3.extractor.SeekPoint(indexed[0], indexed[1])
                    Log.i(
                        TAG,
                        "MMTS indexed seek: target_us=$targetUs indexed_us=${indexed[0]} " +
                            "offset=${indexed[1]} input_length=$inputLength"
                    )
                    if (indexed[2] >= 0L && indexed[3] >= 0L) {
                        return SeekMap.SeekPoints(
                            first,
                            androidx.media3.extractor.SeekPoint(indexed[2], indexed[3])
                        )
                    }
                    return SeekMap.SeekPoints(first)
                }
                Log.i(
                    TAG,
                    "MMTS indexed seek skipped: target_us=$targetUs indexed_us=${indexed[0]} " +
                        "estimated_forward_scan_bytes=${estimatedIndexedForwardScanBytes(distanceFromIndexedPoint, recordingDurationUs, inputLength)} " +
                        "budget_bytes=$MAX_INDEXED_FORWARD_SCAN_BYTES"
                )
            }

            val estimatedPosition = if (recordingDurationUs <= 0L || inputLength <= 0L) {
                0L
            } else {
                ((targetUs.toDouble() / recordingDurationUs.toDouble()) * inputLength.toDouble())
                    .toLong()
                    .minus(SEEK_PROBE_BACKOFF_BYTES)
                    .coerceIn(0L, (inputLength - 1L).coerceAtLeast(0L))
            }
            Log.w(
                TAG,
                "MMTS estimated seek: target_us=$targetUs duration_us=$recordingDurationUs " +
                    "offset=$estimatedPosition input_length=$inputLength indexed=false"
            )
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
        const val B62_SUBTITLE_TYPE_CAPTION = 0
        const val B62_SUBTITLE_TYPE_SUPERIMPOSE = 1

        private const val CODEC_HEVC = 0
        private const val CODEC_AAC_LATM = 1
        private const val CODEC_TTML = 2
        private const val AUDIO_LAYOUT_STEREO = 3
        private const val AUDIO_LAYOUT_5_1 = 9
        private const val AUDIO_LAYOUT_22_2 = 14
        private const val SEEK_PROBE_BACKOFF_BYTES = 16L * 1024L * 1024L
        private const val GROWING_SEEK_MAP_REFRESH_US = 30L * C.MICROS_PER_SECOND
    }
}
