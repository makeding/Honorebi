package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RecordedCaptionTimelineTest {
    private var now = 0L
    private val timeline = RecordedCaptionTimeline { now }
    private fun cue(type: Int = 0, pts: Long = 0, duration: Long = -1) = NativeCaptionCue(
        pts, duration, false, 1920, 1080,
        listOf(NativeCaptionImage(0, 0, 1, 1, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))),
        type = type,
    )
    private fun show(cue: NativeCaptionCue) {
        timeline.offer(timeline.generation, cue)
        assertSame(cue, timeline.current(cue.type, cue.ptsMs))
    }

    @Test fun `paused buffered and silent new stream expire at five seconds`() {
        val old = cue()
        show(old)
        timeline.switchQuality()
        now = 4_999
        assertSame(old, timeline.current(0, 0))
        now = 5_000
        assertNull(timeline.current(0, 0))
    }

    @Test fun `switch snapshots the frame actually displayed before the next render tick`() {
        val displayed = cue()
        show(displayed)
        timeline.offer(0, cue(pts = 10_000))
        timeline.switchQuality(caption = displayed)
        assertSame(displayed, timeline.current(0, 0))
        now = 5_000
        assertNull(timeline.current(0, 0))
    }

    @Test fun `repeated switches never renew deadline or resurrect pending old cues`() {
        val old = cue()
        show(old)
        timeline.offer(0, cue(pts = 20_000))
        timeline.current(0, 0)
        timeline.switchQuality()
        now = 4_000
        timeline.switchQuality()
        now = 5_000
        assertNull(timeline.current(0, 20_000))
        timeline.offer(0, old)
        timeline.offer(1, old)
        assertNull(timeline.current(0, 20_000))
    }

    @Test fun `new captions and clear commands replace only their layer`() {
        val caption = cue()
        val superimpose = cue(type = 1)
        show(caption)
        show(superimpose)
        timeline.switchQuality()
        now = 1_000
        val replacement = cue(pts = 10)
        show(replacement)
        assertSame(superimpose, timeline.current(1, 10))
        timeline.offer(timeline.generation, cue(type = 1).copy(clearScreen = true))
        assertNull(timeline.current(1, 10))
        assertSame(replacement, timeline.current(0, 10))
    }

    @Test fun `disable caption leaves superimpose and seek clears both`() {
        show(cue())
        val superimpose = cue(type = 1)
        show(superimpose)
        timeline.switchQuality()
        timeline.clear(0)
        assertNull(timeline.current(0, 0))
        assertSame(superimpose, timeline.current(1, 0))
        timeline.clear()
        assertNull(timeline.current(1, 0))
    }

    @Test fun `normal duration and reset commands retain broadcast semantics`() {
        val timed = cue(duration = 100)
        show(timed)
        now = 100_000
        assertSame(timed, timeline.current(0, 99))
        assertNull(timeline.current(0, 100))
        show(cue())
        timeline.switchQuality()
        timeline.offer(timeline.generation, cue().copy(timelineCommand = NativeCaptionCue.TIMELINE_COMMAND_RESET))
        assertNull(timeline.current(0, 0))
    }

    @Test fun `empty and paused timelines sleep until invalidated`() {
        assertNull(timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = true, playbackSpeed = 1f))
        timeline.offer(timeline.generation, cue(pts = 10_000))
        timeline.current(0, 0)
        assertNull(timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = false, playbackSpeed = 1f))
    }

    @Test fun `running timeline schedules cue boundaries with speed and bounded recalibration`() {
        timeline.offer(timeline.generation, cue(pts = 500))
        timeline.current(0, 0)
        assertEquals(250L, timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = true, playbackSpeed = 2f))

        timeline.clear()
        timeline.offer(timeline.generation, cue(pts = 10_000))
        timeline.current(0, 0)
        assertEquals(1_000L, timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = true, playbackSpeed = 1f))
    }

    @Test fun `timed cue expiry and independent layers choose earliest boundary`() {
        val caption = cue(pts = 0, duration = 2_000)
        val superimpose = cue(type = 1, pts = 0, duration = 500)
        show(caption)
        show(superimpose)
        assertEquals(500L, timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = true, playbackSpeed = 1f))
        assertNull(timeline.current(1, 500))
        assertEquals(1_000L, timeline.nextUpdateDelayMs(positionMs = 500, clockRunning = true, playbackSpeed = 1f))
    }

    @Test fun `quality transition deadline advances while paused`() {
        show(cue())
        timeline.switchQuality()
        now = 1_250
        timeline.current(0, 0)
        assertEquals(3_750L, timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = false, playbackSpeed = 1f))
        now = 5_000
        assertNull(timeline.current(0, 0))
        assertNull(timeline.nextUpdateDelayMs(positionMs = 0, clockRunning = false, playbackSpeed = 1f))
    }

    @Test fun `late cue wakes a scheduler suspended without a deadline`() = runBlocking {
        timeline.current(0, 0)
        val lateCue = cue(pts = 0)
        val wait = async {
            timeline.awaitInvalidation(timeoutMs = null)
            true
        }
        yield()

        timeline.offer(timeline.generation, lateCue)

        assertTrue(withTimeout(1_000L) { wait.await() })
        assertSame(lateCue, timeline.current(0, 0))
    }

    @Test fun `external player event can wake paused scheduler`() = runBlocking {
        timeline.offer(timeline.generation, cue(pts = 10_000))
        // Consume the offer signal before modelling a paused scheduler wait.
        timeline.awaitInvalidation(timeoutMs = 1L)
        val wait = async {
            timeline.awaitInvalidation(timeoutMs = null)
            true
        }
        yield()

        timeline.invalidate()

        assertTrue(withTimeout(1_000L) { wait.await() })
    }

    @Test fun `disabled captions discard late arrivals without suppressing superimpose`() {
        val state = RecordedCaptionState()
        val lateCaption = cue(pts = 0)
        val superimpose = cue(type = NativeCaptionCue.TYPE_SUPERIMPOSE, pts = 0)
        state.timeline.offer(state.timeline.generation, lateCaption)
        state.timeline.offer(state.timeline.generation, superimpose)

        state.update(enabled = false, positionMs = 0)

        assertNull(state.caption.value)
        assertNull(state.timeline.current(NativeCaptionCue.TYPE_CAPTION, 0))
        assertSame(superimpose, state.superimpose.value)
        assertNull(state.timeline.nextUpdateDelayMs(0, clockRunning = false, playbackSpeed = 1f))
    }

    @Test fun `clearing an already empty layer does not wake its suspended scheduler`() = runBlocking {
        assertFalse(timeline.awaitInvalidation(timeoutMs = 1L))
        timeline.clear(NativeCaptionCue.TYPE_CAPTION)

        assertFalse(timeline.awaitInvalidation(timeoutMs = 20L))
        assertNull(timeline.current(NativeCaptionCue.TYPE_CAPTION, 0))
    }
}
