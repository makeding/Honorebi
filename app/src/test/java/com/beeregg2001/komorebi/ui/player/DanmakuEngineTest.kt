package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuEngineTest {

    @Test
    fun engineModeParsesKnownValuesAndDefaultsToGles() {
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId("GLES"))
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId("gles"))
        assertEquals(DanmakuEngineMode.DFM, DanmakuEngineMode.fromId("DFM"))
        assertEquals(DanmakuEngineMode.DFM, DanmakuEngineMode.fromId("dfm"))
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId(null))
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId("unknown"))
    }

    @Test
    fun futureScheduledItemWaitsUntilClockReachesShowTime() {
        val engine = DanmakuEngine()
        engine.setViewport(1920, 1080)
        engine.enqueue(DanmakuItem("later", 0xFFFFFFFF.toInt(), 64f, DanmakuType.SCROLL_RL, showTimeMs = 200L))

        engine.step(100L, { 300f to 70f }, { _, _, _ -> "slot-1" }, {})
        assertEquals(0, engine.activeCount())
        assertEquals(1, engine.pendingCount())
        assertTrue(engine.hasWork())

        engine.step(100L, { 300f to 70f }, { _, _, _ -> "slot-1" }, {})
        assertEquals(1, engine.activeCount())
        assertEquals(0, engine.pendingCount())
    }

    @Test
    fun scrollItemsUseSeparateLanesToAvoidOverlapAndRespectMaxLines() {
        val engine = DanmakuEngine()
        engine.setViewport(1920, 1080)
        engine.updateConfig(speed = 1.0f, opacity = 1.0f, maxLines = 2)

        engine.enqueue(DanmakuItem("one", 0xFFFFFFFF.toInt(), 64f, DanmakuType.SCROLL_RL, showTimeMs = 0L))
        engine.enqueue(DanmakuItem("two", 0xFFFFFFFF.toInt(), 64f, DanmakuType.SCROLL_RL, showTimeMs = 0L))
        engine.enqueue(DanmakuItem("three", 0xFFFFFFFF.toInt(), 64f, DanmakuType.SCROLL_RL, showTimeMs = 0L))

        engine.step(16L, { 400f to 70f }, { item, _, _ -> item.text }, {})

        assertEquals(2, engine.activeCount())
        val lanes = mutableListOf<Int>()
        engine.forEachActive { lanes += it.lane }
        assertEquals(listOf(0, 1), lanes)
    }

    @Test
    fun clearAllExpiresAllActivePayloadsAndEmptiesQueue() {
        val engine = DanmakuEngine()
        engine.setViewport(1920, 1080)
        engine.enqueue(DanmakuItem("active", 0xFFFFFFFF.toInt(), 64f, DanmakuType.FIX_TOP, showTimeMs = 0L))
        engine.enqueue(DanmakuItem("pending", 0xFFFFFFFF.toInt(), 64f, DanmakuType.FIX_TOP, showTimeMs = 1000L))

        engine.step(16L, { 300f to 70f }, { item, _, _ -> item.text }, {})
        assertEquals(1, engine.activeCount())
        assertEquals(1, engine.pendingCount())

        val recycled = mutableListOf<Any?>()
        engine.clearAll { recycled += it }

        assertEquals(listOf<Any?>("active"), recycled)
        assertFalse(engine.hasWork())
    }

    @Test
    fun scrollingItemExitsAndRecyclesSlotSoEngineBecomesIdle() {
        val engine = DanmakuEngine()
        engine.setViewport(1920, 1080)
        engine.enqueue(DanmakuItem("hello", 0xFFFFFFFF.toInt(), 64f, DanmakuType.SCROLL_RL, showTimeMs = 0L))

        val recycled = mutableListOf<Any?>()
        repeat(80) {
            engine.step(100L, { 300f to 70f }, { _, _, _ -> "slot-hello" }, { recycled += it })
        }

        assertEquals(listOf<Any?>("slot-hello"), recycled)
        assertEquals(0, engine.activeCount())
        assertFalse(engine.hasWork())
    }
}
