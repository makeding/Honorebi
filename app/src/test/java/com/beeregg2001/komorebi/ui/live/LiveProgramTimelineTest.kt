package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.Program
import com.beeregg2001.komorebi.ui.player.formatPlayerEndTime
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class LiveProgramTimelineTest {
    private val program = Program("p", "歴史探偵", "", null,
        "2026-10-19T23:50:00+09:00", "2026-10-20T00:35:00+09:00", 2700, null)

    @Test fun midnightProgramHasElapsedAndTotalDurationInsteadOfClockEndpoints() {
        val timeline = LiveProgramTimeline.from(program)!!
        val now = ZonedDateTime.parse("2026-10-20T00:26:00+09:00[Asia/Tokyo]")
        assertEquals(36 * 60_000L, timeline.positionMs(now.toInstant()))
        assertEquals(45 * 60_000L, timeline.durationMs)
        assertEquals(0L, timeline.positionMs(timeline.start.minusSeconds(1)))
        assertEquals(timeline.durationMs, timeline.positionMs(timeline.end.plusSeconds(1)))
    }

    @Test fun missingAndInvalidEpgNeverReuseOrInventDuration() {
        assertNull(LiveProgramTimeline.from(null))
        assertNull(LiveProgramTimeline.from(program.copy(endTime = "")))
        assertNull(LiveProgramTimeline.from(program.copy(startTime = "invalid")))
        assertNull(LiveProgramTimeline.from(program.copy(endTime = program.startTime)))
        assertNull(LiveProgramTimeline.from(program.copy(endTime = "2026-10-19T23:00:00+09:00")))
    }

    @Test fun endLabelUsesTheSameClockDayAndZoneRulesAsRecordedPlayback() {
        val now = ZonedDateTime.parse("2026-10-19T23:59:00+09:00[Asia/Tokyo]")
        val end = LiveProgramTimeline.from(program)!!.end
        assertEquals("翌日 00:35 終了予定", formatPlayerEndTime(now, end.atZone(now.zone), "24H"))
        assertEquals("翌日 00:35 終了予定", formatPlayerEndTime(now, end.atZone(now.zone), "28H"))
        assertEquals("翌日 午前 12:35 終了予定", formatPlayerEndTime(now, end.atZone(now.zone), "12H"))
        val utc = now.withZoneSameInstant(java.time.ZoneId.of("UTC"))
        assertEquals("15:35 終了予定", formatPlayerEndTime(utc, end.atZone(utc.zone), "24H"))
    }
}
