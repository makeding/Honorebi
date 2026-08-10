package com.beeregg2001.komorebi.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedVideoGsonTest {
    private val gson = Gson()

    @Test
    fun primaryAudioCodec_isDeserializedAndHashable() {
        assertAudioCodecAndHashCode(
            """
                {
                  "id": 1,
                  "status": "Recorded",
                  "file_path": "/recorded/current.ts",
                  "duration": 60.0,
                  "container_format": "MPEG-TS",
                  "video_codec": "H.264",
                  "primary_audio_codec": "AAC-LC"
                }
            """.trimIndent()
        )
    }

    @Test
    fun legacyAudioCodec_isStillDeserializedAndHashable() {
        assertAudioCodecAndHashCode(
            """
                {
                  "id": 2,
                  "status": "Recorded",
                  "file_path": "/recorded/legacy.ts",
                  "duration": 60.0,
                  "container_format": "MPEG-TS",
                  "video_codec": "H.264",
                  "audio_codec": "AAC-LC"
                }
            """.trimIndent()
        )
    }

    private fun assertAudioCodecAndHashCode(json: String) {
        val video = gson.fromJson(json, RecordedVideo::class.java)

        assertEquals("AAC-LC", video.audioCodec)
        assertEquals(video.hashCode(), video.hashCode())
    }
}
