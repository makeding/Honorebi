package com.beeregg2001.komorebi.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ProgramTimeFormatterTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test
    fun formatTimeSupports12And24HourFormatsAnd28HourExtension() {
        assertEquals("午前 1:05", ProgramTimeFormatter.formatTime(
            LocalDateTime.of(2026, 10, 4, 1, 5).toLocalTime(), "12H"
        ))
        assertEquals("01:05", ProgramTimeFormatter.formatTime(
            LocalDateTime.of(2026, 10, 4, 1, 5).toLocalTime(), "24H"
        ))
        assertEquals("25:05", ProgramTimeFormatter.formatTime(
            LocalDateTime.of(2026, 10, 4, 1, 5).toLocalTime(), "28H"
        ))
    }

    @Test
    fun twentyEightHourFormatUsesPreviousBroadcastDateUntilFourAm() {
        val midnight = LocalDateTime.of(2026, 10, 4, 0, 0).atZone(zone)
        val threeFiftyNine = LocalDateTime.of(2026, 10, 4, 3, 59).atZone(zone)
        val fourAm = LocalDateTime.of(2026, 10, 4, 4, 0).atZone(zone)

        assertEquals("2026/10/03 24:00", ProgramTimeFormatter.formatDateTime(midnight, "28H", "yyyy/MM/dd"))
        assertEquals("2026/10/03 27:59", ProgramTimeFormatter.formatDateTime(threeFiftyNine, "28H", "yyyy/MM/dd"))
        assertEquals("2026/10/04 04:00", ProgramTimeFormatter.formatDateTime(fourAm, "28H", "yyyy/MM/dd"))
    }

    @Test
    fun rangeKeepsOvernightProgramWithinOneBroadcastDate() {
        val start = LocalDateTime.of(2026, 10, 3, 23, 30).atZone(zone)
        val end = LocalDateTime.of(2026, 10, 4, 1, 5).atZone(zone)

        assertEquals(
            "2026/10/03 23:30 - 25:05",
            ProgramTimeFormatter.formatRange(start, end, "28H", "yyyy/MM/dd")
        )
    }

    @Test
    fun rangeShowsNewBroadcastDateAfterFourAm() {
        val start = LocalDateTime.of(2026, 10, 4, 3, 30).atZone(zone)
        val end = LocalDateTime.of(2026, 10, 4, 4, 30).atZone(zone)

        assertEquals(
            "2026/10/03 27:30 - 2026/10/04 04:30",
            ProgramTimeFormatter.formatRange(start, end, "28H", "yyyy/MM/dd")
        )
    }
}
