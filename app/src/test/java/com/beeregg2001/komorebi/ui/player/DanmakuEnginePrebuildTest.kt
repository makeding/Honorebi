package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuEnginePrebuildTest {
    @Test
    fun prebuildIsBoundedAndTransfersPayloadWithoutRemeasurement() {
        val engine = DanmakuEngine()
        repeat(3) { engine.enqueue(DanmakuItem("$it", -1, 20f, DanmakuType.FIX_TOP, 400)) }
        var measured = 0
        var activated = 0
        val measure: (DanmakuItem) -> Pair<Float, Float> = { measured++; 100f to 20f }
        val activate: (DanmakuItem, Float, Float) -> Any? = { item, _, _ -> activated++; item.text }
        repeat(3) { engine.prebuildNext(measure, activate) }
        assertEquals(2, activated)
        engine.step(400, measure, activate, {})
        assertEquals(3, activated)
        assertEquals(3, measured)
        assertEquals(3, engine.activeCount())
    }

    @Test
    fun discardKeepsTextAndClearReleasesPreparedPayloads() {
        val engine = DanmakuEngine()
        repeat(2) { engine.enqueue(DanmakuItem("$it", -1, 20f, DanmakuType.FIX_TOP, 400)) }
        val released = mutableListOf<Any?>()
        val measure: (DanmakuItem) -> Pair<Float, Float> = { 100f to 20f }
        val activate: (DanmakuItem, Float, Float) -> Any? = { item, _, _ -> item.text }
        repeat(2) { engine.prebuildNext(measure, activate) }
        assertTrue(engine.discardPrebuilt { released.add(it) })
        assertEquals(2, engine.pendingCount())
        engine.clearAll { released.add(it) }
        assertEquals(listOf("0", "1"), released)
        assertFalse(engine.hasWork())
    }

    @Test
    fun failedPrebuildIsNotRepeatedBeforeDue() {
        val engine = DanmakuEngine()
        engine.enqueue(DanmakuItem("item", -1, 20f, showTimeMs = 400))
        var attempts = 0
        val measure: (DanmakuItem) -> Pair<Float, Float> = { 100f to 20f }
        val activate: (DanmakuItem, Float, Float) -> Any? = { _, _, _ -> attempts++; null }
        repeat(3) { engine.prebuildNext(measure, activate) }
        assertEquals(1, attempts)
        engine.step(400, measure, activate, {})
        assertEquals(2, attempts)
    }

    @Test
    fun rejectedExpiredAndClearedTexturesAreEachReturnedOnce() {
        val engine = DanmakuEngine()
        engine.updateConfig(1f, 1f, 1)
        val released = mutableListOf<Any?>()
        val measure: (DanmakuItem) -> Pair<Float, Float> = { 100f to 32f }
        val upload: (DanmakuItem, Float, Float) -> Any? = { item, _, _ -> item.text }
        fun queue(name: String, at: Long) {
            engine.enqueue(DanmakuItem(name, -1, 32f, showTimeMs = at))
            engine.prebuildNext(measure, upload)
        }
        queue("visible", 100)
        queue("no lane", 100)
        engine.step(100, measure, upload) { released.add(it) }
        assertEquals(listOf("no lane"), released)
        queue("too late", 200)
        engine.step(6000, measure, upload) { released.add(it) }
        queue("seek", 6200)
        engine.clearAll { released.add(it) }
        assertEquals(listOf("no lane", "visible", "too late", "seek"), released)
    }
}
