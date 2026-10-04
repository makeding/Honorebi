package com.beeregg2001.komorebi.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class OnAirSeriesGsonTest {
    @Test
    fun onAirResponse_usesServerFieldsAndTurnsNullArraysIntoVisibleEmptyStates() {
        val response = Gson().fromJson(
            """
            {"current_season_id":"2026-fall","seasons":[{"season_id":"2026-fall","season_label":"2026年 秋","is_current":true,"start_date":"2026-10-01","end_date":"2026-12-31","series_list":[{
              "id":42,"title":"番組","thumbnail_recorded_program_ids":null,"channel_ids":null,
              "recorded_episodes_count":12,"missing_episodes_count":2,"partially_recorded_episodes_count":1,
              "weekday":0,"broadcast_time":"23:30","latest_broadcast_at":"2026-09-13T23:30:00+09:00"
            }]}]}
            """.trimIndent(),
            OnAirSeriesApiResponse::class.java,
        )

        val series = response.seasons.single().seriesList.single()
        assertEquals(42, series.id)
        assertEquals(emptyList<Int>(), series.thumbnailRecordedProgramIds)
        assertEquals(emptyList<String>(), series.channelIds)
        assertEquals(2, series.missingEpisodesCount)
        assertEquals("23:30", series.broadcastTime)
    }

    @Test
    fun nullSeriesList_becomesAnHonestEmptyState() {
        val response = Gson().fromJson("{\"seasons\":null}", OnAirSeriesApiResponse::class.java)
        assertEquals(emptyList<OnAirSeason>(), response.seasons)
    }
}
