package com.beeregg2001.komorebi.ui.screensaver

import com.beeregg2001.komorebi.data.model.ReserveChannel
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveProgramDetail
import com.beeregg2001.komorebi.data.model.ReserveRecordSettings
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingRecordingsTest {
    private val now = Instant.parse("2026-10-13T00:00:00Z")

    @Test
    fun `selects enabled future reservations in instant order regardless of input order`() {
        val selected = selectUpcomingRecordings(
            listOf(
                reserve(id = 3, start = "2026-10-13T11:00:00+09:00"),
                reserve(id = 1, start = "2026-10-13T00:30:00Z"),
                reserve(id = 2, start = "2026-10-13T01:00:00Z"),
            ),
            now,
        )

        assertEquals(listOf("program-1", "program-2", "program-3"), selected.map(UpcomingRecording::title))
    }

    @Test
    fun `omits disabled past and malformed reservations`() {
        val selected = selectUpcomingRecordings(
            listOf(
                reserve(id = 1, start = "2026-10-13T01:00:00Z", enabled = false),
                reserve(id = 2, start = "2026-10-12T23:59:59Z"),
                reserve(id = 3, start = "not-a-time"),
                reserve(id = 4, start = "2026-10-13T01:00:00Z"),
            ),
            now,
        )

        assertEquals(listOf("program-4"), selected.map(UpcomingRecording::title))
    }

    @Test
    fun `selection is independent of channel exclusion policy`() {
        val selected = selectUpcomingRecordings(
            listOf(reserve(id = 1, start = "2026-10-13T01:00:00Z", channel = "NHK 総合")),
            now,
        )

        assertEquals(listOf("NHK 総合"), selected.map(UpcomingRecording::channelName))
    }

    private fun reserve(id: Int, start: String, enabled: Boolean = true, channel: String = "channel-$id") = ReserveItem(
        id = id,
        channel = ReserveChannel(name = channel),
        program = ReserveProgramDetail(title = "program-$id", startTime = start),
        recordSettings = ReserveRecordSettings(isEnabled = enabled),
    )
}
