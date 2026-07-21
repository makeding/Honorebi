@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsPayloadReader
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import java.io.ByteArrayOutputStream

/**
 * 字幕データを抽出し、native デコーダーへ渡す PayloadReader
 */
@UnstableApi
class DirectSubtitlePayloadReader(
    private val onSubtitleDataReceived: (Long, ByteArray) -> Unit
) : TsPayloadReader {
    private var timestampAdjuster: TimestampAdjuster? = null
    private val buffer = ByteArrayOutputStream()

    override fun init(
        adjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        this.timestampAdjuster = adjuster
    }

    override fun seek() {
        buffer.reset()
    }

    override fun consume(data: ParsableByteArray, flags: Int) {
        val isStart = (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0
        if (isStart && buffer.size() > 0) {
            parseAndSendBuffer()
            buffer.reset()
        }
        val bytesAvailable = data.bytesLeft()
        if (bytesAvailable > 0) {
            buffer.write(data.data, data.position, bytesAvailable)
            data.skipBytes(bytesAvailable)
        }
    }

    private fun parseAndSendBuffer() {
        val rawData = buffer.toByteArray()
        var id3StartIndex = -1
        for (i in 0 until rawData.size - 2) {
            if (rawData[i] == 0x49.toByte() && rawData[i + 1] == 0x44.toByte() && rawData[i + 2] == 0x33.toByte()) {
                id3StartIndex = i; break
            }
        }
        if (id3StartIndex == -1) return
        try {
            var offset = id3StartIndex + 10
            while (offset < rawData.size - 10) {
                val frameSize =
                    (rawData[offset + 4].toInt() and 0x7F shl 21) or (rawData[offset + 5].toInt() and 0x7F shl 14) or (rawData[offset + 6].toInt() and 0x7F shl 7) or (rawData[offset + 7].toInt() and 0x7F)
                offset += 10
                if (isPrivFrame(rawData, offset - 10)) {
                    var ownerEnd = offset
                    while (ownerEnd < offset + frameSize && ownerEnd < rawData.size && rawData[ownerEnd].toInt() != 0) ownerEnd++
                    if (containsAsciiIgnoreCase(rawData, offset, ownerEnd, "aribb24") ||
                        containsAsciiIgnoreCase(rawData, offset, ownerEnd, "B24")
                    ) {
                        val privateDataStart = ownerEnd + 1
                        val privateDataLength = frameSize - (privateDataStart - offset)
                        if (privateDataStart + privateDataLength <= rawData.size) {
                            val privateData = rawData.copyOfRange(
                                privateDataStart,
                                privateDataStart + privateDataLength
                            )
                            val currentPtsMs = ((timestampAdjuster?.lastAdjustedTimestampUs
                                ?: 0L) / 1000) + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS

                            onSubtitleDataReceived(currentPtsMs, privateData)
                        }
                    }
                }
                offset += frameSize
                if (frameSize <= 0) break
            }
        } catch (e: Exception) {
            android.util.Log.e("DirectSubtitle", "Parse error", e)
        }
    }

    private fun isPrivFrame(data: ByteArray, offset: Int): Boolean {
        return data[offset] == 'P'.code.toByte() &&
            data[offset + 1] == 'R'.code.toByte() &&
            data[offset + 2] == 'I'.code.toByte() &&
            data[offset + 3] == 'V'.code.toByte()
    }

    private fun containsAsciiIgnoreCase(
        data: ByteArray,
        start: Int,
        endExclusive: Int,
        needle: String
    ): Boolean {
        val needleLength = needle.length
        if (needleLength == 0 || endExclusive - start < needleLength) return false
        val lastStart = endExclusive - needleLength
        for (i in start..lastStart) {
            var matched = true
            for (j in 0 until needleLength) {
                if (toLowerAscii(data[i + j]) != needle[j].lowercaseChar().code) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }

    private fun toLowerAscii(value: Byte): Int {
        val charCode = value.toInt() and 0xff
        return if (charCode in 'A'.code..'Z'.code) charCode + 32 else charCode
    }
}

@UnstableApi
class DirectSubtitlePayloadReaderFactory(
    private val onSubtitleDataReceived: (Long, ByteArray) -> Unit
) : TsPayloadReader.Factory {
    private val defaultFactory = DefaultTsPayloadReaderFactory(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    )

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        defaultFactory.createInitialPayloadReaders()

    override fun createPayloadReader(
        streamType: Int,
        esInfo: TsPayloadReader.EsInfo
    ): TsPayloadReader? {
        if (streamType == 0x06 || streamType == 0x15) {
            return DirectSubtitlePayloadReader(onSubtitleDataReceived)
        }
        return defaultFactory.createPayloadReader(streamType, esInfo)
    }
}

/**
 * 原始 MPEG-TS 内の ARIB STD-B24 PES を Media3 の ID3 Metadata Track に変換する。
 * 動画・音声パケットには触れず、MetadataRenderer が字幕 PTS に合わせて通知できるようにする。
 */
@UnstableApi
class RawAribSubtitlePayloadReader : TsPayloadReader {
    private var timestampAdjuster: TimestampAdjuster? = null
    private var trackOutput: TrackOutput? = null
    private val buffer = ByteArrayOutputStream()

    override fun init(
        adjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator
    ) {
        timestampAdjuster = adjuster
        idGenerator.generateNewId()
        trackOutput = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_METADATA).apply {
            format(
                Format.Builder()
                    .setId(idGenerator.formatId)
                    .setSampleMimeType(MimeTypes.APPLICATION_ID3)
                    .build()
            )
        }
    }

    override fun seek() {
        buffer.reset()
    }

    override fun consume(data: ParsableByteArray, flags: Int) {
        val isStart = (flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0
        if (isStart && buffer.size() > 0) {
            emitBufferedPes()
            buffer.reset()
        }
        val bytesAvailable = data.bytesLeft()
        if (bytesAvailable > 0) {
            buffer.write(data.data, data.position, bytesAvailable)
            data.skipBytes(bytesAvailable)
        }
    }

    private fun emitBufferedPes() {
        val pes = buffer.toByteArray()
        val parsed = parseAribPes(pes) ?: return
        val adjustedTimeUs = timestampAdjuster?.adjustTsTimestamp(parsed.pts90Khz)
            ?.takeUnless { it == C.TIME_UNSET }
            ?: timestampAdjuster?.lastAdjustedTimestampUs
                ?.takeUnless { it == C.TIME_UNSET }
            ?: return
        val id3 = buildId3PrivTag(parsed.payload)
        val output = trackOutput ?: return
        output.sampleData(ParsableByteArray(id3), id3.size)
        output.sampleMetadata(
            adjustedTimeUs,
            C.BUFFER_FLAG_KEY_FRAME,
            id3.size,
            0,
            null
        )
    }

    private data class ParsedAribPes(
        val pts90Khz: Long,
        val payload: ByteArray
    )

    private fun parseAribPes(pes: ByteArray): ParsedAribPes? {
        if (pes.size < 9 ||
            pes[0] != 0x00.toByte() ||
            pes[1] != 0x00.toByte() ||
            pes[2] != 0x01.toByte()
        ) {
            return null
        }
        val streamId = pes[3].toInt() and 0xff
        if (streamId != 0xbd || pes.size < 14) return null
        val ptsDtsFlags = (pes[7].toInt() ushr 6) and 0x03
        if (ptsDtsFlags < 2) return null
        val payloadStart = 9 + (pes[8].toInt() and 0xff)
        if (payloadStart + 3 > pes.size) return null
        val dataIdentifier = pes[payloadStart].toInt() and 0xff
        val privateStreamId = pes[payloadStart + 1].toInt() and 0xff
        if (dataIdentifier != 0x80 || privateStreamId != 0xff) return null
        val pesPacketLength = ((pes[4].toInt() and 0xff) shl 8) or
            (pes[5].toInt() and 0xff)
        val payloadEnd = if (pesPacketLength > 0) {
            (6 + pesPacketLength).coerceAtMost(pes.size)
        } else {
            pes.size
        }
        if (payloadStart >= payloadEnd) return null

        val pts = ((pes[9].toLong() and 0x0eL) shl 29) or
            ((pes[10].toLong() and 0xffL) shl 22) or
            ((pes[11].toLong() and 0xfeL) shl 14) or
            ((pes[12].toLong() and 0xffL) shl 7) or
            ((pes[13].toLong() and 0xfeL) ushr 1)
        return ParsedAribPes(
            pts90Khz = pts,
            payload = pes.copyOfRange(payloadStart, payloadEnd)
        )
    }

    private fun buildId3PrivTag(payload: ByteArray): ByteArray {
        val owner = "aribb24.js".toByteArray(Charsets.US_ASCII)
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
}

@UnstableApi
class RawAribSubtitlePayloadReaderFactory : TsPayloadReader.Factory {
    private val defaultFactory = DefaultTsPayloadReaderFactory(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
    )

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> =
        defaultFactory.createInitialPayloadReaders()

    override fun createPayloadReader(
        streamType: Int,
        esInfo: TsPayloadReader.EsInfo
    ): TsPayloadReader? {
        if (streamType == 0x06) return RawAribSubtitlePayloadReader()
        return defaultFactory.createPayloadReader(streamType, esInfo)
    }
}
