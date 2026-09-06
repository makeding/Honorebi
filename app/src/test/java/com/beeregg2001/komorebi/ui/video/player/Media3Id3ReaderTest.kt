package com.beeregg2001.komorebi.ui.video.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs checked-in `tsreadex_pipeline_test --emit-hex` output through Media3's [TsExtractor]. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Media3Id3ReaderTest {
    @Test
    fun nativeTsReadExFixtureProducesCaptionAndSuperimposeMetadata() {
        val bytes = nativeFixtureBytes()
        assertEquals(0, bytes.size % TsExtractor.TS_PACKET_SIZE)

        val output = CapturingExtractorOutput()
        val extractor = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            TimestampAdjuster(C.TIME_UNSET),
            DefaultTsPayloadReaderFactory(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            ),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
        extractor.init(output)
        fun inputAt(position: Long): DefaultExtractorInput {
            require(position in 0..bytes.size.toLong())
            val stream = ByteArrayInputStream(bytes, position.toInt(), bytes.size - position.toInt())
            return DefaultExtractorInput(
                DataReader { target, offset, length -> stream.read(target, offset, length) },
                position,
                bytes.size.toLong(),
            )
        }
        var input = inputAt(0)
        val holder = PositionHolder()
        repeat(1_000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> return@repeat
                Extractor.RESULT_SEEK -> input = inputAt(holder.position)
            }
        }
        extractor.release()

        val metadataTrack = output.tracks.values.single { it.format?.sampleMimeType == "application/id3" }
        assertEquals(2, metadataTrack.samples.size)
        assertEquals(1_000_000L, metadataTrack.samples[1].timeUs - metadataTrack.samples[0].timeUs)
        val decoded = metadataTrack.samples.map { sample -> Id3Decoder().decode(sample.data, sample.data.size) }
        assertTrue(decoded.all { it != null && it.length() == 1 })

        val caption = decoded[0]!!.get(0) as PrivFrame
        val superimpose = decoded[1]!!.get(0) as PrivFrame
        assertEquals("aribb24.js", caption.owner)
        assertEquals("aribb24.js", superimpose.owner)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xff.toByte(), 0, 0x10, 0x20), caption.privateData)
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0xff.toByte(), 0, 0x30, 0x40), superimpose.privateData)
    }

    private fun nativeFixtureBytes(): ByteArray = javaClass
        .getResourceAsStream("/native_tsreadex_id3_fixture.hex")!!
        .bufferedReader()
        .useLines { lines ->
            lines.filterNot { it.startsWith("#") }
                .joinToString("")
                .trim()
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }

    private class CapturingExtractorOutput : ExtractorOutput {
        val tracks = linkedMapOf<Int, CapturingTrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { CapturingTrackOutput() }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class CapturingTrackOutput : TrackOutput {
        var format: Format? = null
        private val pendingData = ArrayList<Byte>()
        val samples = ArrayList<Sample>()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pendingData += bytes.toList()
        }

        @Throws(IOException::class)
        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = throw UnsupportedOperationException("TsExtractor supplies ParsableByteArray data")

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val start = pendingData.size - offset - size
            samples += Sample(pendingData.subList(start, start + size).toByteArray(), timeUs)
            pendingData.subList(0, start + size + offset).clear()
        }
    }

    private data class Sample(val data: ByteArray, val timeUs: Long)
}
