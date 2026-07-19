@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.util.mmts

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

class TlvExtractorsFactory(
    private val preferredVideoPacketId: Int?
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = arrayOf(TlvExtractor(preferredVideoPacketId))
}

/**
 * Raw ARIB MMT/TLV を libtlvdemux.so で Access Unit に分解し、Media3 の既存 ElementaryStreamReader
 * へ渡すライブ専用 Extractor。TTML/B62 は首版では意図的に公開しない。
 */
class TlvExtractor(
    private val preferredVideoPacketId: Int?
) : Extractor, NativeTlvDemuxer.Callback {

    private val inputBuffer = ByteArray(INPUT_BUFFER_SIZE)
    private val trackIdGenerator = TsPayloadReader.TrackIdGenerator(0, 1)
    private val audioReaders = linkedMapOf<Long, LatmReader>()

    private var extractorOutput: ExtractorOutput? = null
    private var nativeDemuxer: NativeTlvDemuxer? = null
    private var videoReader: H265Reader? = null
    private var videoTrackId: Long? = null
    private var tracksEnded = false
    private var fatalError: IOException? = null

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        nativeDemuxer = NativeTlvDemuxer(this)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        fatalError?.let { throw it }
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
        nativeDemuxer?.reset()
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
        timescale: Long
    ) {
        if (tracksEnded) return
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

            codec == CODEC_AAC_LATM && !audioReaders.containsKey(trackId) -> {
                audioReaders[trackId] = LatmReader(
                    language.ifBlank { null },
                    0,
                    MMTS_CONTAINER_MIME_TYPE
                ).also { it.createTracks(output, trackIdGenerator) }
                Log.i(
                    TAG,
                    "MMTS audio track: context=$contextId packetId=0x${packetId.toString(16)} language=$language"
                )
            }

            codec == CODEC_TTML -> Log.d(
                TAG,
                "Ignoring MMTS TTML track: packetId=0x${packetId.toString(16)} componentTag=$componentTag timescale=$timescale"
            )
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

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val CODEC_HEVC = 0
        private const val CODEC_AAC_LATM = 1
        private const val CODEC_TTML = 2
    }
}
