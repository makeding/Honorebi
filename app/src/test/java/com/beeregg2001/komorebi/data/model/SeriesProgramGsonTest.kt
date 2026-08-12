package com.beeregg2001.komorebi.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesProgramGsonTest {
    @Test
    fun seriesSummary_isDeserializedFromCurrentHonomiContract() {
        val response = Gson().fromJson(
            """
                {
                  "total": 1,
                  "series_list": [{
                    "id": 123,
                    "title": "作品名",
                    "description": "作品紹介",
                    "genres": [{"major": "アニメ・特撮", "middle": "国内アニメ"}],
                    "thumbnail_recorded_program_ids": [901, 872, 841],
                    "official_website_url": "https://example.com/",
                    "bangumi_subject_id": 456,
                    "recorded_programs_count": 12,
                    "created_at": "2026-08-12T00:00:00+09:00",
                    "updated_at": "2026-08-12T01:00:00+09:00"
                  }]
                }
            """.trimIndent(),
            SeriesApiResponse::class.java,
        )

        val series = response.seriesList.single()
        assertEquals(12, series.recordedProgramsCount)
        assertEquals(listOf(901, 872, 841), series.thumbnailRecordedProgramIds)
        assertEquals("https://example.com/", series.officialWebsiteUrl)
        assertEquals(456, series.bangumiSubjectId)
    }
}
