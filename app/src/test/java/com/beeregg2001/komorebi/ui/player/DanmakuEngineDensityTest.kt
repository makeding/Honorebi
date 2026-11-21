package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DanmakuEngineDensityTest {
    @Test
    fun denseBatchActivatesAcrossFramesAndRecyclesEveryPayload() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 1000)
        repeat(12) { engine.enqueue(DanmakuItem("item$it", -1, 30f, DanmakuType.FIX_TOP)) }
        val expired = mutableListOf<Any?>()
        val measure: (DanmakuItem) -> Pair<Float, Float> = { 100f to 30f }
        val activate: (DanmakuItem, Float, Float) -> Any? = { item, _, _ -> item.text }
        engine.step(0, measure, activate) { expired.add(it) }
        assertEquals(6, engine.activeCount())
        assertEquals(6, engine.pendingCount())
        engine.step(0, measure, activate) { expired.add(it) }
        assertEquals(12, engine.activeCount())
        assertEquals(0, engine.pendingCount())
        engine.step(3800, measure, activate) { expired.add(it) }
        assertEquals((0 until 12).map { "item$it" }, expired)
        assertFalse(engine.hasWork())
    }

    @Test
    fun clearReleasesActiveAndPendingAndAllowsFreshPlacement() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 500)
        repeat(8) { engine.enqueue(DanmakuItem("old$it", -1, 40f, DanmakuType.FIX_TOP)) }
        engine.step(0, { 100f to 40f }, { item, _, _ -> item.text }, {})
        val expired = mutableListOf<Any?>()
        engine.clearAll { expired.add(it) }
        assertEquals((0 until 6).map { "old$it" }, expired)
        assertFalse(engine.hasWork())
        engine.enqueue(DanmakuItem("fresh", -1, 40f, DanmakuType.FIX_TOP))
        engine.step(0, { 100f to 40f }, { item, _, _ -> item.text }, {})
        engine.forEachActive { assertEquals(0f, it.y, 0f) }
    }

    @Test
    fun lateFrameExpiresOldItemsAndPositionsStillLiveItemsByAge() {
        val engine = DanmakuEngine()
        engine.setViewport(1000, 500)
        engine.enqueue(DanmakuItem("expired", -1, 40f, DanmakuType.FIX_TOP))
        engine.enqueue(DanmakuItem("live", -1, 40f, DanmakuType.SCROLL_RL, 3000))
        engine.step(4000, { 100f to 40f }, { item, _, _ -> item.text }, {})
        assertEquals(4000L, engine.currentTimeMs)
        assertEquals(1, engine.activeCount())
        engine.forEachActive {
            assertEquals("live", it.item.text)
            assertEquals(800f, it.x, 0.01f)
        }
    }
}
