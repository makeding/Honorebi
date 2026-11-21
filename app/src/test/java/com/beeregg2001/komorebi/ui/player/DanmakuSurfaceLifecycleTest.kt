package com.beeregg2001.komorebi.ui.player

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DanmakuSurfaceLifecycleTest {
    @Test
    fun softwareViewReportsAttachmentAndRelease() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = android.widget.FrameLayout(activity)
        activity.setContentView(container)
        var prepared = 0
        val view = SoftwareDanmakuView(activity) { prepared++ }
        assertFalse(view.isPrepared)
        container.addView(view)
        assertTrue(view.isPrepared)
        assertEquals(1, prepared)
        container.removeView(view)
        assertFalse(view.isPrepared)
        container.addView(view)
        assertTrue(view.isPrepared)
        assertEquals(2, prepared)
        view.release()
        assertFalse(view.isPrepared)
        container.removeView(view)
        container.addView(view, ViewGroup.LayoutParams(1, 1))
        assertFalse(view.isPrepared)
        assertEquals(2, prepared)
    }
}
