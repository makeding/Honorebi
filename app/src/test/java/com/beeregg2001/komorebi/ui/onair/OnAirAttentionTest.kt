package com.beeregg2001.komorebi.ui.onair

import com.beeregg2001.komorebi.data.model.OnAirSeries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** HonomiTV の OnAirUtils と同じ強調表示ウィンドウの判定。 */
class OnAirAttentionTest {
    private val jst = ZoneId.of("Asia/Tokyo")
    private fun jst(iso: String): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse(iso), jst)
    private fun series(weekday: Int, time: String) =
        OnAirSeries(1, "作品", emptyList(), emptyList(), 0, 0, 0, weekday, time, "2026-09-07T23:30:00+09:00")

    @Test fun nightWindowHighlightsTonightBroadcasts() {
        val mondayNight = jst("2026-09-07T23:00:00")
        assertTrue(isOnAirSeriesInAttentionWindow(series(0, "23:30"), mondayNight))
        assertFalse(isOnAirSeriesInAttentionWindow(series(1, "23:30"), mondayNight))
    }

    @Test fun daytimeWindowHighlightsUpcomingTonightAndRecentBroadcasts() {
        val mondayAfternoon = jst("2026-09-07T15:00:00")
        assertTrue(isOnAirSeriesInAttentionWindow(series(0, "23:30"), mondayAfternoon))
        // 翌朝（火曜）早朝の枠も「次の深夜帯」として同じ連続範囲に入る。
        assertTrue(isOnAirSeriesInAttentionWindow(series(1, "01:00"), mondayAfternoon))
        // それより先・前の曜日は対象外。
        assertFalse(isOnAirSeriesInAttentionWindow(series(4, "23:30"), mondayAfternoon))
    }

    @Test fun crossMidnightSlotMatchesOnAdjacentCalendarDay() {
        // 月曜 00:30 枠は日曜の夜として強調される（暦日の境界を跨ぐ照合）。
        val sundayNight = jst("2026-09-06T23:30:00")
        assertTrue(isOnAirSeriesInAttentionWindow(series(0, "00:30"), sundayNight))
        val mondayDeepNight = jst("2026-09-07T03:30:00")
        assertTrue(isOnAirSeriesInAttentionWindow(series(0, "00:30"), mondayDeepNight))
        assertFalse(isOnAirSeriesInAttentionWindow(series(1, "23:30"), mondayDeepNight))
    }
}
