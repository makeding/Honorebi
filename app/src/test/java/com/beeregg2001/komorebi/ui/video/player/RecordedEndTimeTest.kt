package com.beeregg2001.komorebi.ui.video.player

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedEndTimeTest {
    private val now = ZonedDateTime.parse("2026-09-13T23:30:00+09:00[Asia/Tokyo]")

    @Test fun endTimeUsesRemainingDurationAndSpeedAndMarksMidnight() {
        assertEquals("翌日 00:30 終了予定", recordedEndTimeLabel(now, "24H", 0, 3_600_000, 1f))
        assertEquals("翌日 午前 12:00 終了予定", recordedEndTimeLabel(now, "12H", 0, 3_600_000, 2f))
        assertEquals("23:50 終了予定", recordedEndTimeLabel(now, "24H", 2_400_000, 3_600_000, 1f))
        assertEquals("9/15 23:30 終了予定", recordedEndTimeLabel(now, "24H", 0, 172_800_000, 1f))
    }

    @Test fun stationaryPositionStillEstimatesFromCurrentTime() {
        assertEquals("翌日 00:31 終了予定", recordedEndTimeLabel(now.plusMinutes(1), "24H", 0, 3_600_000, 1f))
        assertEquals("翌日 00:30 終了予定", recordedEndTimeLabel(now.plusMinutes(1), "24H", 60_000, 3_600_000, 1f))
    }

    @Test fun invalidAndFinishedMediaHaveHonestLabels() {
        assertEquals("終了時刻未定", recordedEndTimeLabel(now, "24H", 0, 0, 1f))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("終了時刻未定", recordedEndTimeLabel(now, "24H", 0, 1000, speed))
        }
        assertEquals("再生終了", recordedEndTimeLabel(now, "24H", 1000, 1000, 1f))
    }
}
