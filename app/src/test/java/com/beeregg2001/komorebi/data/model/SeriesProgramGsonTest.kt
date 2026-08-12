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
                    "channel_ids": ["NID4-SID211"],
                    "official_website_url": "https://example.com/",
                    "bangumi_subject_id": 456,
                    "bangumi_subject_name": "作品名",
                    "bangumi_subject_name_cn": "作品名 中文",
                    "bangumi_subject_summary": "Bangumi 介绍",
                    "bangumi_subject_image_url": "https://example.com/image.jpg",
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
        assertEquals(listOf("NID4-SID211"), series.channelIds)
        assertEquals("https://example.com/", series.officialWebsiteUrl)
        assertEquals(456, series.bangumiSubjectId)
        assertEquals("作品名", series.bangumiSubjectName)
        assertEquals("作品名 中文", series.bangumiSubjectNameCn)
        assertEquals("Bangumi 介绍", series.bangumiSubjectSummary)
        assertEquals("https://example.com/image.jpg", series.bangumiSubjectImageUrl)
    }

    @Test
    fun recordedProgramSeriesFields_areDeserializedFromCurrentHonomiContract() {
        val program = Gson().fromJson(
            """
                {
                  "id": 901,
                  "title": "作品名 #7",
                  "series_id": 123,
                  "series_broadcast_period_id": 456,
                  "series_title": "作品名",
                  "episode_number": "7",
                  "subtitle": "旅立ち",
                  "description": "",
                  "start_time": "2026-08-12T22:00:00+09:00",
                  "end_time": "2026-08-12T22:30:00+09:00",
                  "duration": 1800.0,
                  "is_partially_recorded": false,
                  "recorded_video": {
                    "id": 901,
                    "status": "Recorded",
                    "file_path": "sample.ts",
                    "duration": 1800.0,
                    "container_format": "MPEG-TS",
                    "video_codec": "H.264",
                    "primary_audio_codec": "AAC"
                  }
                }
            """.trimIndent(),
            RecordedProgram::class.java,
        )

        assertEquals(123, program.seriesId)
        assertEquals(456, program.seriesBroadcastPeriodId)
        assertEquals("7", program.episodeNumber)
        assertEquals("旅立ち", program.subtitle)
    }
}
