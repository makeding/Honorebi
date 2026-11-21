package com.beeregg2001.komorebi.util

import com.beeregg2001.komorebi.data.model.CmSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterParserTest {
    @Test
    fun luaFormatRetainsNamedMarkersAndDerivesCmSections() {
        val chapters = ChapterParser.parse(
            "c-0cOpening-60000cPartA-120000cixA-150000coxA-c",
            durationSec = 180.0,
        )

        assertTrue(chapters.any { it.isMarkerOnly && it.label == "Opening" })
        assertTrue(chapters.any { it.isMarkerOnly && it.label == "PartA" })
        assertEquals(listOf(CmSection(120.0, 150.0)), ChapterParser.toCmSections(chapters))
    }

    @Test
    fun iniFormatIsDetectedFromContentsAndAdjacentCmRangesAreMerged() {
        val chapters = ChapterParser.parse(
            """
                CHAPTER01=00:00:00.000
                CHAPTER01NAME=Part 1
                CHAPTER02=00:01:00.000
                CHAPTER02NAME=CM A
                CHAPTER03=00:01:30.000
                CHAPTER03NAME=Sponsor
                CHAPTER04=00:02:00.000
                CHAPTER04NAME=Part 2
            """.trimIndent(),
            durationSec = 180.0,
        )

        assertTrue(chapters.any { it.isMarkerOnly && it.label == "Part 2" })
        assertEquals(listOf(CmSection(60.0, 120.0)), ChapterParser.toCmSections(chapters))
    }

    @Test
    fun decoderHandlesUtf8AndUtf16ByteOrderMarks() {
        val text = "chapter"

        assertEquals(
            text,
            ChapterParser.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()),
        )
        assertEquals(
            text,
            ChapterParser.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            text,
            ChapterParser.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)),
        )
    }

    @Test
    fun decoderFallsBackToShiftJisForJapaneseChapterNames() {
        val text = "CHAPTER01NAME=本編"

        assertEquals(text, ChapterParser.decode(text.toByteArray(charset("Shift_JIS"))))
    }

    @Test
    fun invalidLuaAndEmptyContentProduceNoChapters() {
        assertTrue(ChapterParser.parse("c-invalid-c", 60.0).isEmpty())
        assertFalse(ChapterParser.parse("CHAPTER01=00:00:00.000", 60.0).isEmpty())
    }
}
