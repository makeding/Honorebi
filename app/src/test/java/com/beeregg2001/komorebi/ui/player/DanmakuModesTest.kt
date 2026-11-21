package com.beeregg2001.komorebi.ui.player

import android.app.Application
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.View
import androidx.test.core.app.ApplicationProvider
import master.flame.danmaku.ui.widget.DanmakuSurfaceView
import master.flame.danmaku.ui.widget.DanmakuView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DanmakuModesTest {
    @Test
    fun modesSelectTheirActualRenderBackends() {
        // The Android native EGL initializer installs these three sentinel handles.
        // Robolectric omits it; this factory test constructs an unattached View, not a GL context.
        EGL14.EGL_NO_DISPLAY = Shadow.newInstanceOf(EGLDisplay::class.java)
        EGL14.EGL_NO_CONTEXT = Shadow.newInstanceOf(EGLContext::class.java)
        EGL14.EGL_NO_SURFACE = Shadow.newInstanceOf(EGLSurface::class.java)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val (surface, _) = createDanmakuBackend(context, DanmakuEngineMode.DFM, false) {}
        assertTrue(surface is DanmakuSurfaceView)
        val (software, _) = createDanmakuBackend(context, DanmakuEngineMode.DFM, true) {}
        assertTrue(software is DanmakuView)
        assertEquals(View.LAYER_TYPE_SOFTWARE, software.layerType)
        for (emulator in listOf(false, true)) {
            val (hardware, _) = createDanmakuBackend(context, DanmakuEngineMode.DFM_HARDWARE, emulator) {}
            assertTrue(hardware is DanmakuView)
            assertEquals(View.LAYER_TYPE_HARDWARE, hardware.layerType)
            val (gl, controller) = createDanmakuBackend(context, DanmakuEngineMode.GLES, emulator) {}
            assertTrue(if (emulator) gl is SoftwareDanmakuView else gl is GlDanmakuSurfaceView)
            controller.release()
        }
    }
}
