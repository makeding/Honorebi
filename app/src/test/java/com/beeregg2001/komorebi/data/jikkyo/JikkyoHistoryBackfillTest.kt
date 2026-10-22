package com.beeregg2001.komorebi.data.jikkyo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class JikkyoHistoryBackfillTest {
    @Test
    fun requestUsesBoundedCountAndInitialCursor() {
        val backfill = JikkyoHistoryBackfill(lowerBoundUnixSeconds = 100.0, pageSize = 500, initialWhenUnixSeconds = 200.25)

        val request = JSONArray(backfill.request("thread", "key"))
        val thread = request.getJSONObject(2).getJSONObject("thread")

        assertEquals(-500, thread.getInt("res_from"))
        assertEquals(200.25, thread.getDouble("when"), 0.0)
        assertEquals("thread", thread.getString("thread"))
    }

    @Test
    fun pageCursorUsesOldestTimestampForStrictWhenAndKeepsImmediatelyPreviousMicrosecond() {
        val backfill = JikkyoHistoryBackfill(lowerBoundUnixSeconds = 100.0, pageSize = 2, initialWhenUnixSeconds = 200.0)
        val delivered = mutableListOf<String>()

        backfill.acceptChat(chat(150, 900_000)) { delivered += it }
        backfill.acceptChat(chat(150, 100)) { delivered += it }

        assertTrue(backfill.advanceAfterPage())
        val nextThread = JSONArray(backfill.request("thread", "key")).getJSONObject(2).getJSONObject("thread")
        assertEquals(150.000100, nextThread.getDouble("when"), 0.000000001)
        assertEquals(2, delivered.size)
    }

    @Test
    fun stopsAtLowerBoundAndDoesNotForwardOlderChats() {
        val backfill = JikkyoHistoryBackfill(lowerBoundUnixSeconds = 100.0, pageSize = 2, initialWhenUnixSeconds = 200.0)
        val delivered = mutableListOf<String>()

        backfill.acceptChat(chat(100, 0)) { delivered += it }
        backfill.acceptChat(chat(99, 999_999)) { delivered += it }

        assertEquals(1, delivered.size)
        assertFalse(backfill.advanceAfterPage())
    }

    private fun chat(seconds: Long, micros: Long): String = JSONObject()
        .put("chat", JSONObject().put("date", seconds).put("date_usec", micros).put("content", "x"))
        .toString()
}
