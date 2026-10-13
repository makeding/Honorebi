@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.*
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in real-file diagnostic; no network request or fixture repair happens in this test. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TsExtractionDiagnosticsTest {
    @Test fun reportTrackProgressAcrossDamagedTransportStream() {
        val path = System.getenv("KOMOREBI_TS_DIAGNOSTIC")
        assumeTrue("Set KOMOREBI_TS_DIAGNOSTIC to a tsreadex output file", path != null)
        val tracks = linkedMapOf<Int, Track>()
        val extractor = TsExtractor(TsExtractor.MODE_SINGLE_PMT, TimestampAdjuster(C.TIME_UNSET),
            DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(id, type) }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        extractor.seek(0, 0)
        RandomAccessFile(File(path!!), "r").use { file ->
            fun input(position: Long): DefaultExtractorInput {
                file.seek(position)
                return DefaultExtractorInput(DataReader { bytes, offset, length -> file.read(bytes, offset, length) },
                    position, C.LENGTH_UNSET.toLong())
            }
            var source = input(0)
            val holder = PositionHolder()
            var ended = false
            repeat((file.length() / 188 + 1000).toInt()) {
                if (!ended) when (extractor.read(source, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> ended = true
                    Extractor.RESULT_SEEK -> source = input(holder.position)
                }
            }
            assertTrue("Extractor failed to consume bounded input", ended)
        }
        extractor.release()
        tracks.values.forEach { println("TS_TRACK id=${it.id} type=${it.type} mime=${it.mime} samples=${it.count} first_us=${it.first} last_us=${it.last}") }
        assertTrue(tracks.values.any { it.type == C.TRACK_TYPE_AUDIO && it.count > 0 })
        assertTrue(tracks.values.any { it.type == C.TRACK_TYPE_VIDEO && it.count > 0 })
    }

    private class Track(val id: Int, val type: Int) : TrackOutput {
        var mime: String? = null
        var count = 0
        var first = C.TIME_UNSET
        var last = C.TIME_UNSET
        override fun format(format: Format) { mime = format.sampleMimeType }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            error("Unexpected streaming track output")
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (count == 0) first = timeUs
            if (count > 0 && (timeUs - last > 500_000L || timeUs - last < -500_000L)) println("TS_GAP id=$id from_us=$last to_us=$timeUs count=$count")
            last = timeUs
            count++
        }
    }
}
