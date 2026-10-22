package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuViewLifecycleTest {

    @Test
    fun preparedImmediatelyPausesWhenOverlayMustNotRun() {
        val lifecycle = DanmakuViewLifecycle()
        val events = mutableListOf<String>()

        assertTrue(
            lifecycle.prepared(
                running = false,
                start = { events += "start" },
                pause = { events += "pause" },
            )
        )

        assertEquals(listOf("start", "pause"), events)
    }

    @Test
    fun preparedLeavesRunningOverlayStarted() {
        val lifecycle = DanmakuViewLifecycle()
        val events = mutableListOf<String>()

        assertTrue(
            lifecycle.prepared(
                running = true,
                start = { events += "start" },
                pause = { events += "pause" },
            )
        )

        assertEquals(listOf("start"), events)
    }

    @Test
    fun releasePreventsLatePrepareCallback() {
        val lifecycle = DanmakuViewLifecycle()
        val events = mutableListOf<String>()

        assertTrue(lifecycle.release { events += "release" })
        assertFalse(lifecycle.ifActive { events += "start" })

        assertEquals(listOf("release"), events)
    }

    @Test
    fun releaseAndCallbacksAreIdempotent() {
        val lifecycle = DanmakuViewLifecycle()
        val events = mutableListOf<String>()

        assertTrue(lifecycle.ifActive { events += "prepare" })
        assertTrue(lifecycle.release { events += "release" })
        assertFalse(lifecycle.release { events += "release-again" })
        assertFalse(lifecycle.ifActive { events += "prepared-late" })

        assertEquals(listOf("prepare", "release"), events)
    }
}
