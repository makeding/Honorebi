package com.beeregg2001.komorebi.util

import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.ui.video.player.ChapterInfo

/** Shared decoding and parsing for external EDCB and SMB chapter files. */
object ChapterParser {
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("Shift_JIS")) }.getOrDefault(utf8)
    }

    fun parse(text: String, durationSec: Double): List<ChapterInfo> {
        val normalized = text.removePrefix("\uFEFF").trim()
        if (normalized.isEmpty()) return emptyList()
        return when {
            normalized.contains("CHAPTER", ignoreCase = true) && normalized.contains('=') ->
                parseIniFormat(normalized, durationSec)
            normalized.startsWith("c-") -> parseLuaFormat(normalized, durationSec)
            else -> emptyList()
        }
    }

    fun toCmSections(chapters: List<ChapterInfo>): List<CmSection> {
        val cmRanges = chapters
            .filter { it.isCm && !it.isMarkerOnly }
            .map { it.startTimeMs / 1000.0 to it.endTimeMs / 1000.0 }
            .filter { (start, end) -> end > start }
            .sortedBy { it.first }

        val merged = mutableListOf<CmSection>()
        for ((start, end) in cmRanges) {
            val last = merged.lastOrNull()
            if (last != null && start <= last.endTime + 0.001) {
                merged[merged.lastIndex] = CmSection(last.startTime, maxOf(last.endTime, end))
            } else {
                merged.add(CmSection(start, end))
            }
        }
        return merged
    }

    private fun parseLuaFormat(text: String, durationSec: Double): List<ChapterInfo> {
        if (!text.startsWith("c-") || !text.endsWith("c")) return emptyList()
        val coreContent = text.substring(2, text.length - 1)
        if (coreContent.isEmpty() || !coreContent.matches(Regex("^(?:\\d+[cde][^-]*-)+$"))) {
            return emptyList()
        }

        val regex = Regex("""^(\d+)([cde])(.*)$""")
        val rawMarkers = mutableListOf<Pair<Long, String>>()
        var lastTimeMs = 0L
        for (segment in coreContent.split("-").filter { it.isNotEmpty() }) {
            val match = regex.find(segment) ?: return emptyList()
            val position = match.groupValues[1]
            val type = match.groupValues[2]
            val name = match.groupValues[3]
            val timeMs = when (type) {
                "c" -> position.toLongOrNull() ?: 0L
                "d" -> (position.toLongOrNull() ?: 0L) * 100L
                "e" -> if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
                else -> return emptyList()
            }
            rawMarkers.add(timeMs to name)
            lastTimeMs = timeMs
        }
        if (rawMarkers.isEmpty()) return emptyList()

        val safeDurationMs =
            if (durationSec > 0.0) (durationSec * 1000).toLong() else lastTimeMs + 30000L
        val chapters = mutableListOf<ChapterInfo>()
        var currentCmStartMs: Long? = null
        var lastChapterEndMs = 0L

        for (i in rawMarkers.indices) {
            val (timeMs, name) = rawMarkers[i]
            val nextTimeMs = rawMarkers.getOrNull(i + 1)?.first ?: safeDurationMs
            val isCmStart = name.startsWith("ix", ignoreCase = true)
            val isCmEnd = name.startsWith("ox", ignoreCase = true)
            if (isCmStart && currentCmStartMs == null) {
                if (lastChapterEndMs < timeMs) {
                    chapters.add(ChapterInfo(lastChapterEndMs, timeMs, isCm = false))
                }
                currentCmStartMs = timeMs
            } else if (isCmEnd && currentCmStartMs != null) {
                chapters.add(ChapterInfo(currentCmStartMs, timeMs, isCm = true))
                currentCmStartMs = null
                lastChapterEndMs = timeMs
            }
            if (!isCmStart && !isCmEnd) {
                chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = false, isMarkerOnly = true, label = name))
            }
        }

        if (currentCmStartMs != null) {
            chapters.add(ChapterInfo(currentCmStartMs, safeDurationMs, isCm = true))
            lastChapterEndMs = safeDurationMs
        }
        if (lastChapterEndMs < safeDurationMs) {
            chapters.add(ChapterInfo(lastChapterEndMs, safeDurationMs, isCm = false))
        }
        return chapters.sortedBy { it.startTimeMs }
    }

    private fun parseIniFormat(text: String, durationSec: Double): List<ChapterInfo> {
        val rawMarkers = mutableListOf<Pair<Long, String>>()
        var currentStartMs = -1L
        val timeRegex = Regex("""CHAPTER\d+=(\d{1,2}):(\d{2}):(\d{2})\.(\d{3})""")
        val nameRegex = Regex("""CHAPTER\d+NAME=(.*)""")

        for (line in text.split('\n')) {
            timeRegex.find(line)?.let { match ->
                val hours = match.groupValues[1].toLong()
                val minutes = match.groupValues[2].toLong()
                val seconds = match.groupValues[3].toLong()
                val millis = match.groupValues[4].toLong()
                currentStartMs = hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
            }
            nameRegex.find(line)?.let { match ->
                if (currentStartMs >= 0L) {
                    rawMarkers.add(currentStartMs to match.groupValues[1].trim())
                    currentStartMs = -1L
                }
            }
        }
        if (rawMarkers.isEmpty() && currentStartMs >= 0L) rawMarkers.add(currentStartMs to "")
        if (rawMarkers.isEmpty()) return emptyList()

        val safeDurationMs = if (durationSec > 0.0) (durationSec * 1000).toLong()
        else rawMarkers.last().first + 30000L
        val chapters = mutableListOf<ChapterInfo>()
        for (i in rawMarkers.indices) {
            val (timeMs, name) = rawMarkers[i]
            val nextTimeMs = rawMarkers.getOrNull(i + 1)?.first ?: safeDurationMs
            val isCm = name.contains("CM", ignoreCase = true) || name.contains("Sponsor", ignoreCase = true)
            if (isCm) chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = true))
            chapters.add(ChapterInfo(timeMs, nextTimeMs, isCm = false, isMarkerOnly = true, label = name))
        }
        return chapters.sortedBy { it.startTimeMs }
    }
}
