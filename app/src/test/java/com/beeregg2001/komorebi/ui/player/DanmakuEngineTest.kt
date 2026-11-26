package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuEngineTest {

    @Test
    fun lateFramesAdvanceClockAndStartItemsAtTheirElapsedPosition() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 500)
        engine.enqueue(DanmakuItem("late", -1, 40f, showTimeMs = 500L))
        engine.step(1500L, { 100f to 40f }, { _, _, _ -> Unit }, {})
        assertEquals(1500L, engine.currentTimeMs)
        engine.forEachActive { assertEquals(800f, it.x, 0.01f) }
    }

    @Test
    fun retainersAreIndependentAndPackMixedHeights() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 500)
        engine.enqueue(DanmakuItem("large", -1, 80f))
        engine.enqueue(DanmakuItem("small", -1, 30f, DanmakuType.FIX_TOP))
        engine.enqueue(DanmakuItem("second", -1, 20f, DanmakuType.FIX_TOP))
        engine.step(0L, { 100f to it.textSizePx }, { _, _, _ -> Unit }, {})
        val positions = mutableListOf<Pair<Float, Float>>()
        engine.forEachActive { positions += it.y to it.heightPx }
        assertEquals(3, positions.size)
        assertEquals(0f, positions[0].first, 0f)
        assertEquals(0f, positions[1].first, 0f)
        assertEquals(31f, positions[2].first, 0f)
    }

    @Test
    fun fixedRetainersWrapOnOverflowAndBottomStartsAtScreenEdge() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 80)
        repeat(3) { engine.enqueue(DanmakuItem("top$it", -1, 40f, DanmakuType.FIX_TOP)) }
        engine.enqueue(DanmakuItem("bottom", -1, 20f, DanmakuType.FIX_BOTTOM))
        engine.step(0L, { 100f to it.textSizePx }, { _, _, _ -> Unit }, {})
        val ys = mutableListOf<Float>()
        engine.forEachActive { ys += it.y }
        assertEquals(listOf(0f, 0f, 0f, 59f), ys)
    }

    @Test
    fun changingSpeedAffectsExistingScrollingItems() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 500)
        engine.enqueue(DanmakuItem("item", -1, 40f))
        engine.step(0L, { 100f to 40f }, { _, _, _ -> Unit }, {})
        engine.updateConfig(2f, 1f, 0)
        engine.step(1000L, { 100f to 40f }, { _, _, _ -> Unit }, {})
        engine.forEachActive { assertEquals(600f, it.x, 0.01f) }
    }

    @Test
    fun engineModeParsesKnownValuesAndDefaultsToHardware() {
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId("GLES"))
        assertEquals(DanmakuEngineMode.GLES, DanmakuEngineMode.fromId("gles"))
        assertEquals(DanmakuEngineMode.DFM, DanmakuEngineMode.fromId("DFM"))
        assertEquals(DanmakuEngineMode.DFM, DanmakuEngineMode.fromId("dfm"))
        assertEquals(DanmakuEngineMode.DFM_HARDWARE, DanmakuEngineMode.fromId("dfm_hardware"))
        assertEquals(DanmakuEngineMode.DFM_HARDWARE, DanmakuEngineMode.fromId(null))
        assertEquals(DanmakuEngineMode.DFM_HARDWARE, DanmakuEngineMode.fromId("unknown"))
    }

    @Test
    fun retainerRespectsThirtyPixelGapAndReusesRemovedRows() {
        val retainer = DanmakuLaneLayout()
        val item = DanmakuItem("first", -1, 40f)
        val existing = DanmakuEngine.ActiveItem(item, 100f, 40f, 0, 870f, 0f, 200f, 0f, Unit as Any?)
        retainer.add(existing)
        fun place() = retainer.allocateLane(DanmakuType.SCROLL_RL, 100f, 40f, 0, 1000f, 500f, 1f, 0)!!
        assertEquals(41f, place().y, 0f)
        existing.x = 869f
        assertEquals(0f, place().y, 0f)
        retainer.remove(existing)
        assertEquals(0f, place().y, 0f)
    }

    @Test
    fun retainerRejectsFasterCatchupAndLimitsMixedSizeRows() {
        val retainer = DanmakuLaneLayout()
        val item = DanmakuItem("first", -1, 80f)
        retainer.add(DanmakuEngine.ActiveItem(item, 100f, 80f, 0, 700f, 0f, 100f, 0f, Unit as Any?))
        val placement = retainer.allocateLane(DanmakuType.SCROLL_RL, 500f, 20f, 0, 1000f, 500f, 1f, 0)!!
        assertEquals(81f, placement.y, 0f)
        assertEquals(null, retainer.allocateLane(DanmakuType.SCROLL_RL, 500f, 20f, 0, 1000f, 500f, 1f, 1))
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

    @Test
    fun earliestPendingShowTimeReturnsMinimumOrNullWhenEmpty() {
        val engine = DanmakuEngine()
        assertEquals(null, engine.earliestPendingShowTimeMs())

        engine.enqueue(DanmakuItem("later", -1, 40f, showTimeMs = 2_500L))
        engine.enqueue(DanmakuItem("sooner", -1, 40f, showTimeMs = 1_200L))
        assertEquals(1_200L, engine.earliestPendingShowTimeMs())

        engine.clearAll()
        assertEquals(null, engine.earliestPendingShowTimeMs())
    }
}
