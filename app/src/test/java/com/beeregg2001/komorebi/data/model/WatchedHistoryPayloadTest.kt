package com.beeregg2001.komorebi.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WatchedHistoryPayloadTest {
    @Test
    fun existingHistoryRequestCanCarryCurrentPlaybackWithoutAnotherRequest() {
        val payload = WatchedHistoryPayload(
            listOf(WatchedHistoryItem(7, 1200.0, 1.0, 2.0)),
            HistoryPlaybackProgress(7, 1200.0, 1807.0),
        )
        val json = JsonParser.parseString(Gson().toJson(payload)).asJsonObject
        assertEquals(1, json["items"].asJsonArray.size())
        val progress = json["playback_progress"].asJsonObject
        assertEquals(7, progress["video_id"].asInt)
        assertEquals(1200.0, progress["playback_position"].asDouble, 0.0)
        assertEquals(1807.0, progress["duration"].asDouble, 0.0)
    }

    @Test
    fun bulkCloudSyncDoesNotReportCurrentPlayback() {
        val payload = WatchedHistoryPayload(listOf(WatchedHistoryItem(7, 1200.0, 1.0, 2.0)))
        val json = JsonParser.parseString(Gson().toJson(payload)).asJsonObject
        assertFalse(json.has("playback_progress"))
        val response = Gson().fromJson(json, WatchedHistoryPayload::class.java)
        assertEquals(payload.items, response.items)
    }
}
