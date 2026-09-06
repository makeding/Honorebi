package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
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
}
