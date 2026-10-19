package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.Program
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Only the EPG adapter differs: live position advances with the clock and is not seekable. */
internal data class LiveProgramTimeline(val start: Instant, val end: Instant) {
    val durationMs: Long get() = Duration.between(start, end).toMillis()
    fun positionMs(now: Instant): Long = Duration.between(start, now).toMillis().coerceIn(0, durationMs)

    companion object {
        fun from(program: Program?, zone: ZoneId = ZoneId.systemDefault()): LiveProgramTimeline? {
            if (program == null) return null
            fun parse(value: String): Instant? =
                runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()
                    ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
            val start = parse(program.startTime) ?: return null
            val end = parse(program.endTime) ?: return null
            return if (end > start) LiveProgramTimeline(start, end) else null
        }
    }
}
