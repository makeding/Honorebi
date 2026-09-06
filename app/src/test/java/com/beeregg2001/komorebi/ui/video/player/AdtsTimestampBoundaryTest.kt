package com.beeregg2001.komorebi.ui.video.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsReader
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for an ADTS frame that crosses from an untimestamped PES into a timestamped one. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdtsTimestampBoundaryTest {
    @Test
    fun untimestampedPrefixCannotCommitOnlyAnAdtsSuffix() {
        val output = CapturingExtractorOutput()
        val reader = AdtsReader(false, "video/mp2t")
        reader.createTracks(output, TrackIdGenerator(100, 1))

        // A complete no-PTS frame must not produce metadata.
        reader.packetStarted(C.TIME_UNSET, 0)
        reader.consume(ParsableByteArray(ADTS_FRAME))
        assertTrue(output.audio.metadata.isEmpty())

        // The next frame starts without a PTS and ends in a PES that has one.
        // Its complete six-byte AAC access unit must be present before metadata is committed.
        reader.packetStarted(C.TIME_UNSET, 0)
        reader.consume(ParsableByteArray(ADTS_FRAME.copyOfRange(0, 9)))
        reader.packetStarted(1_000_000L, 0)
        reader.consume(ParsableByteArray(ADTS_FRAME.copyOfRange(9, ADTS_FRAME.size)))

        assertEquals(1, output.audio.metadata.size)
        val metadata = output.audio.metadata.single()
        assertEquals(6, metadata.size)
        assertEquals(1_000_000L, metadata.timeUs)

        // This is SampleQueue's absolute-offset calculation. The old patch writes only
        // the four-byte suffix and yields -2, which overlaps the prior queue region.
        assertEquals(6L, metadata.totalBytesWritten - metadata.size - metadata.offset)
    }

    private class CapturingExtractorOutput : ExtractorOutput {
        val audio = CapturingTrackOutput()

        override fun track(id: Int, type: Int): TrackOutput = audio
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class CapturingTrackOutput : TrackOutput {
        var totalBytesWritten = 0L
        val metadata = mutableListOf<Metadata>()

        override fun format(format: Format) = Unit

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalBytesWritten += length
        }

        @Throws(IOException::class)
        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = throw UnsupportedOperationException("AdtsReader supplies ParsableByteArray data")

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            metadata += Metadata(timeUs, size, offset, totalBytesWritten)
        }
    }

    private data class Metadata(val timeUs: Long, val size: Int, val offset: Int, val totalBytesWritten: Long)

    private companion object {
        // 48 kHz stereo AAC-LC ADTS frame emitted by CServiceFilter's silent-audio path.
        val ADTS_FRAME = byteArrayOf(
            0xff.toByte(), 0xf1.toByte(), 0x4c, 0x80.toByte(), 0x01, 0xbf.toByte(), 0xfc.toByte(),
            0x21, 0x10, 0x04, 0x60, 0x8c.toByte(), 0x1c,
        )
    }
}
