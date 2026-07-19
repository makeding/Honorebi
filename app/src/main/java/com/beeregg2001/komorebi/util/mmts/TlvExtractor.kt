@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.util.mmts

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.H265Reader
import androidx.media3.extractor.ts.LatmReader
import androidx.media3.extractor.ts.SeiReader
import androidx.media3.extractor.ts.TsPayloadReader
import java.io.IOException

private const val TAG = "TlvExtractor"
private const val INPUT_BUFFER_SIZE = 512 * 1024
private const val MMTS_CONTAINER_MIME_TYPE = "application/x-arib-mmts"

class TlvExtractorsFactory(
    private val preferredVideoPacketId: Int?,
    private val enableSeeking: Boolean = false,
    private val durationUs: Long = C.TIME_UNSET
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(
        TlvExtractor(preferredVideoPacketId, enableSeeking, durationUs)
    )
}

/**
 * Raw ARIB MMT/TLV を libtlvdemux.so で Access Unit に分解し、Media3 の既存 ElementaryStreamReader
 * へ渡す Extractor。ARIB STD-B62 TTML は timed ID3 として MetadataRenderer に公開する。
 */
class TlvExtractor(
    private val preferredVideoPacketId: Int?,
    private val enableSeeking: Boolean,
    private val durationUs: Long
) : Extractor, NativeTlvDemuxer.Callback {

    private val inputBuffer = ByteArray(INPUT_BUFFER_SIZE)
    private val trackIdGenerator = TsPayloadReader.TrackIdGenerator(0, 1)
    private val audioReaders = linkedMapOf<Long, LatmReader>()

    private var extractorOutput: ExtractorOutput? = null
    private var nativeDemuxer: NativeTlvDemuxer? = null
    private var videoReader: H265Reader? = null
    private var videoTrackId: Long? = null
    private var subtitleOutput: TrackOutput? = null
    private var subtitleTrackId: Long? = null
    private var tracksEnded = false
    private var fatalError: IOException? = null
    private var seekMapSent = false

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackIdGenerator.generateNewId()
        subtitleOutput = output.track(trackIdGenerator.trackId, C.TRACK_TYPE_METADATA).apply {
            format(
                Format.Builder()
                    .setId(trackIdGenerator.formatId)
                    .setSampleMimeType(MimeTypes.APPLICATION_ID3)
                    .build()
            )
        }
        nativeDemuxer = NativeTlvDemuxer(
            callback = this,
            preferredVideoPacketId = preferredVideoPacketId,
            buildRecordingIndex = enableSeeking
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        fatalError?.let { throw it }
        if (!seekMapSent) {
            val inputLength = input.length
            extractorOutput?.seekMap(
                if (enableSeeking && durationUs > 0L && inputLength > 0L) {
                    RecordingSeekMap(durationUs, inputLength)
                } else {
                    SeekMap.Unseekable(if (durationUs > 0L) durationUs else C.TIME_UNSET)
                }
            )
            seekMapSent = true
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
        nativeDemuxer?.close()
        nativeDemuxer = null
        extractorOutput = null
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
        audioMainComponent: Boolean
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

            codec == CODEC_TTML && subtitleTrackId == null -> {
                subtitleTrackId = trackId
            }
        }
    }

    override fun onAccessUnit(
        trackId: Long,
        codec: Int,
        data: ByteArray,
        ptsValue: Long,
        ptsTimescale: Long,
        dtsValue: Long,
        dtsTimescale: Long,
        inputOffset: Long,
        randomAccess: Boolean,
        discontinuity: Boolean
    ) {
        if (fatalError != null) return
        ensureTracksEnded(inputOffset)
        val timeUs = scaleToMicroseconds(ptsValue, ptsTimescale)
        val flags = if (randomAccess) TsPayloadReader.FLAG_RANDOM_ACCESS_INDICATOR else 0
        val payload = ParsableByteArray(data)

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
                val id3 = buildPrivateId3("aribb62", data)
                subtitleOutput?.let { output ->
                    output.sampleData(ParsableByteArray(id3), id3.size)
                    output.sampleMetadata(
                        timeUs,
                        C.BUFFER_FLAG_KEY_FRAME,
                        id3.size,
                        0,
                        null
                    )
                }
            }
        }
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

    private fun buildPrivateId3(ownerName: String, payload: ByteArray): ByteArray {
        val owner = ownerName.toByteArray(Charsets.US_ASCII)
        val framePayloadSize = owner.size + 1 + payload.size
        val tagPayloadSize = 10 + framePayloadSize
        return ByteArray(10 + tagPayloadSize).also { id3 ->
            id3[0] = 'I'.code.toByte()
            id3[1] = 'D'.code.toByte()
            id3[2] = '3'.code.toByte()
            id3[3] = 4
            writeSynchsafeInt(id3, 6, tagPayloadSize)
            id3[10] = 'P'.code.toByte()
            id3[11] = 'R'.code.toByte()
            id3[12] = 'I'.code.toByte()
            id3[13] = 'V'.code.toByte()
            writeSynchsafeInt(id3, 14, framePayloadSize)
            owner.copyInto(id3, destinationOffset = 20)
            payload.copyInto(id3, destinationOffset = 20 + owner.size + 1)
        }
    }

    private fun writeSynchsafeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 21) and 0x7f).toByte()
        target[offset + 1] = ((value ushr 14) and 0x7f).toByte()
        target[offset + 2] = ((value ushr 7) and 0x7f).toByte()
        target[offset + 3] = (value and 0x7f).toByte()
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

    private inner class RecordingSeekMap(
        private val recordingDurationUs: Long,
        private val inputLength: Long
    ) : SeekMap {
        override fun isSeekable(): Boolean = true

        override fun getDurationUs(): Long = recordingDurationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
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
    }
}
