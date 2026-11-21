package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

private const val TAG = "GlDanmakuSurfaceView"

private const val ATLAS_WIDTH = 2048
private const val ATLAS_HEIGHT = 2048
private const val NORMAL_SHELF_HEIGHT = 96
private const val NORMAL_SHELF_COUNT = 16 // 16 * 96 = 1536
private const val LARGE_SHELF_HEIGHT = 160
private const val LARGE_SHELF_COUNT = 3 // 3 * 160 = 480; total = 2016 <= 2048
private const val TOTAL_SHELF_COUNT = NORMAL_SHELF_COUNT + LARGE_SHELF_COUNT
private const val DANMAKU_STROKE_WIDTH_PX = 8.0f
private const val DANMAKU_PADDING_PX = 10

private val BUCKET_WIDTHS = intArrayOf(128, 256, 512, 1024, ATLAS_WIDTH)

private const val VERT_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aAlpha;
uniform vec2 uViewport;
out vec2 vUv;
out float vAlpha;
void main() {
    vec2 ndc = (aPos / uViewport) * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);
    vUv = aUv;
    vAlpha = aAlpha;
}
"""

private const val FRAG_SHADER = """#version 300 es
precision mediump float;
uniform sampler2D uAtlas;
in vec2 vUv;
in float vAlpha;
out vec4 fragColor;
void main() {
    fragColor = texture(uAtlas, vUv) * vAlpha;
}
"""

/**
 * Hardware-accelerated EGL + OpenGL ES 3.0 danmaku renderer on a dedicated [SurfaceView].
 *
 * Each incoming comment is rasterized once into a reusable bucketed scratch [Bitmap] and uploaded
 * into a fixed 2048x2048 RGBA8888 texture atlas (16 MiB). Every frame draws all visible comments
 * in a single batched draw call (`glDrawArrays`) with premultiplied alpha blending, completely
 * decoupled from the UI thread. When paused or idle, the VSync callback stops (0% CPU).
 */
internal class GlDanmakuSurfaceView(
    context: Context,
    private val onPreparedCallback: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback, DanmakuController {

    private class Span(val x: Int, val w: Int)

    private class AtlasSlot(
        val shelf: Int,
        val x: Int,
        val y: Int,
        val drawW: Int,
        val drawH: Int,
    )

    private val engine = DanmakuEngine(MAX_VISIBLE_DANMAKU)
    private val released = AtomicBoolean(false)

    @Volatile
    private var preparedFlag = false

    override val currentTimeMs: Long
        get() = engine.currentTimeMs

    override val isPrepared: Boolean
        get() = preparedFlag && !released.get()

    private val renderThread = HandlerThread("GlDanmakuThread").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    // Render-thread-only state
    private var choreographer: Choreographer? = null
    private var surfaceReady = false
    private var running = false
    private var frameLoopActive = false
    private var surfaceCleared = true
    private var useGl = false
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080
    private var lastFrameNs = 0L
    private var activeHolder: SurfaceHolder? = null

    // EGL / GLES 3.0 state (render thread only)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var uViewportLoc = -1
    private var uAtlasLoc = -1
    private var atlasTexId = 0
    private var vboId = 0

    private val vertexArray = FloatArray(MAX_VISIBLE_DANMAKU * 6 * 5)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_VISIBLE_DANMAKU * 6 * 5 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val shelves = Array(TOTAL_SHELF_COUNT) { ArrayList<Span>(8) }
    private val normalBuckets = arrayOfNulls<Bitmap>(BUCKET_WIDTHS.size)
    private val normalCanvases = arrayOfNulls<Canvas>(BUCKET_WIDTHS.size)
    private val largeBuckets = arrayOfNulls<Bitmap>(BUCKET_WIDTHS.size)
    private val largeCanvases = arrayOfNulls<Canvas>(BUCKET_WIDTHS.size)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = DANMAKU_STROKE_WIDTH_PX
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!surfaceReady || !running || released.get()) {
                frameLoopActive = false
                return
            }
            stepAndRender(frameTimeNanos)
            if (surfaceReady && running && !released.get() && engine.hasWork()) {
                choreographer?.postFrameCallback(this)
            } else {
                frameLoopActive = false
                lastFrameNs = 0L
                if (surfaceReady && !surfaceCleared && !engine.hasWork()) {
                    drawCurrentFrame()
                }
            }
        }
    }

    init {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(this)
    }

    override fun addDanmaku(item: DanmakuItem) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            engine.enqueue(item)
            maybeStartFrameLoop()
        }
    }

    override fun clearAll() {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            engine.clearAll(::recyclePayload)
            if (surfaceReady && !surfaceCleared) {
                drawCurrentFrame()
            }
        }
    }

    override fun updateConfig(speed: Float, opacity: Float, maxLines: Int) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            val opacityChanged = engine.opacity != opacity.coerceIn(0f, 1f)
            engine.updateConfig(speed, opacity, maxLines)
            if (opacityChanged && surfaceReady && !running && engine.activeCount() > 0) {
                drawCurrentFrame()
            }
        }
    }

    override fun pause() {
        if (released.get()) return
        renderHandler.post {
            running = false
            stopFrameLoop()
        }
    }

    override fun resume() {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            running = true
            lastFrameNs = 0L
            maybeStartFrameLoop()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        preparedFlag = false
        holder.removeCallback(this)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                running = false
                surfaceReady = false
                stopFrameLoop()
                engine.clearAll(::recyclePayload)
                releaseGl()
                releaseScratchBitmaps()
                activeHolder = null
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        renderThread.quitSafely()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            activeHolder = holder
            val frame = holder.surfaceFrame
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                surfaceWidth = frame.width()
                surfaceHeight = frame.height()
                engine.setViewport(surfaceWidth, surfaceHeight)
            }
            useGl = initGl(holder)
            if (useGl) {
                initScratchBitmaps()
            } else {
                Log.w(TAG, "EGL/GLES3 init failed; falling back to SurfaceHolder.lockCanvas()")
            }
            choreographer = Choreographer.getInstance()
            surfaceReady = true
            surfaceCleared = false
            drawCurrentFrame()
            if (!preparedFlag) {
                preparedFlag = true
                onPreparedCallback()
            }
            maybeStartFrameLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            engine.setViewport(surfaceWidth, surfaceHeight)
            if (surfaceReady && useGl) {
                GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
                drawCurrentFrame()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released.get()) return
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                surfaceReady = false
                stopFrameLoop()
                engine.clearAll(::recyclePayload)
                releaseGl()
                activeHolder = null
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(1_000L, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun maybeStartFrameLoop() {
        if (!surfaceReady || !running || frameLoopActive || released.get()) return
        if (!engine.hasWork()) return
        frameLoopActive = true
        lastFrameNs = 0L
        choreographer?.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        if (frameLoopActive) {
            choreographer?.removeFrameCallback(frameCallback)
            frameLoopActive = false
        }
        lastFrameNs = 0L
    }

    private fun stepAndRender(frameTimeNanos: Long) {
        val dtMs = if (lastFrameNs == 0L) 16L else ((frameTimeNanos - lastFrameNs) / 1_000_000L).coerceIn(1L, 100L)
        lastFrameNs = frameTimeNanos
        engine.step(
            dtMs = dtMs,
            measureItem = ::measureItem,
            onActivate = ::activateItem,
            onExpired = ::recyclePayload,
        )
        drawCurrentFrame()
    }

    private fun measureItem(item: DanmakuItem): Pair<Float, Float> {
        val effectiveSize = item.textSizePx.coerceIn(12f, 128f)
        strokePaint.textSize = effectiveSize
        val textW = strokePaint.measureText(item.text)
        val fm = strokePaint.fontMetrics
        val w = (ceil(textW) + DANMAKU_PADDING_PX * 2).coerceAtMost(ATLAS_WIDTH.toFloat())
        val h = (ceil(fm.descent - fm.ascent) + DANMAKU_PADDING_PX).coerceAtMost(LARGE_SHELF_HEIGHT.toFloat())
        return w to h
    }

    private fun activateItem(item: DanmakuItem, widthPx: Float, heightPx: Float): Any? {
        if (!useGl) return Unit
        val w = widthPx.toInt().coerceIn(1, ATLAS_WIDTH)
        val h = heightPx.toInt().coerceIn(1, LARGE_SHELF_HEIGHT)
        val useLarge = h > NORMAL_SHELF_HEIGHT
        val bucketIdx = pickBucketIndex(w)
        val bucketW = BUCKET_WIDTHS[bucketIdx]
        val slot = allocateSlot(bucketW, w, h, useLarge) ?: return null

        val canvas = (if (useLarge) largeCanvases else normalCanvases)[bucketIdx] ?: return null
        val bitmap = (if (useLarge) largeBuckets else normalBuckets)[bucketIdx] ?: return null

        val effectiveSize = item.textSizePx.coerceIn(12f, 128f)
        strokePaint.textSize = effectiveSize
        fillPaint.textSize = effectiveSize
        fillPaint.color = item.textColor
        val fm = strokePaint.fontMetrics

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val baseline = DANMAKU_PADDING_PX * 0.5f - fm.ascent
        canvas.drawText(item.text, DANMAKU_PADDING_PX.toFloat(), baseline, strokePaint)
        canvas.drawText(item.text, DANMAKU_PADDING_PX.toFloat(), baseline, fillPaint)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexId)
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, slot.x, slot.y, bitmap)
        return slot
    }

    private fun recyclePayload(payload: Any?) {
        val slot = payload as? AtlasSlot ?: return
        val spans = shelves[slot.shelf]
        for (i in 0 until spans.size) {
            if (spans[i].x == slot.x) {
                spans.removeAt(i)
                return
            }
        }
    }

    private fun drawCurrentFrame() {
        if (!surfaceReady) return
        if (useGl) {
            drawGlFrame()
        } else {
            drawSoftwareSurfaceFrame()
        }
        surfaceCleared = engine.activeCount() == 0
    }

    private fun drawGlFrame() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val alpha = engine.opacity
        var idx = 0
        var quadCount = 0
        val invW = 1f / ATLAS_WIDTH
        val invH = 1f / ATLAS_HEIGHT

        engine.forEachActive { entry ->
            val slot = entry.payload as? AtlasSlot ?: return@forEachActive
            if (quadCount >= MAX_VISIBLE_DANMAKU) return@forEachActive
            val x0 = entry.x
            val y0 = entry.y
            val x1 = x0 + slot.drawW
            val y1 = y0 + slot.drawH
            val u0 = slot.x * invW
            val v0 = slot.y * invH
            val u1 = (slot.x + slot.drawW) * invW
            val v1 = (slot.y + slot.drawH) * invH

            // Triangle 1: TL, BL, TR
            vertexArray[idx++] = x0; vertexArray[idx++] = y0; vertexArray[idx++] = u0; vertexArray[idx++] = v0; vertexArray[idx++] = alpha
            vertexArray[idx++] = x0; vertexArray[idx++] = y1; vertexArray[idx++] = u0; vertexArray[idx++] = v1; vertexArray[idx++] = alpha
            vertexArray[idx++] = x1; vertexArray[idx++] = y0; vertexArray[idx++] = u1; vertexArray[idx++] = v0; vertexArray[idx++] = alpha
            // Triangle 2: TR, BL, BR
            vertexArray[idx++] = x1; vertexArray[idx++] = y0; vertexArray[idx++] = u1; vertexArray[idx++] = v0; vertexArray[idx++] = alpha
            vertexArray[idx++] = x0; vertexArray[idx++] = y1; vertexArray[idx++] = u0; vertexArray[idx++] = v1; vertexArray[idx++] = alpha
            vertexArray[idx++] = x1; vertexArray[idx++] = y1; vertexArray[idx++] = u1; vertexArray[idx++] = v1; vertexArray[idx++] = alpha
            quadCount++
        }

        if (quadCount > 0) {
            GLES30.glUseProgram(program)
            GLES30.glUniform2f(uViewportLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexId)
            GLES30.glUniform1i(uAtlasLoc, 0)

            vertexBuffer.clear()
            vertexBuffer.put(vertexArray, 0, idx)
            vertexBuffer.flip()

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, idx * 4, vertexBuffer)

            val stride = 5 * 4
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 4 * 4)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quadCount * 6)
        }

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawSoftwareSurfaceFrame() {
        val h = activeHolder ?: return
        val canvas = try {
            h.lockCanvas()
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val alphaInt = (engine.opacity * 255f).toInt().coerceIn(0, 255)
            engine.forEachActive { entry ->
                val effectiveSize = entry.item.textSizePx.coerceIn(12f, 128f)
                strokePaint.textSize = effectiveSize
                strokePaint.alpha = alphaInt
                fillPaint.textSize = effectiveSize
                fillPaint.color = entry.item.textColor
                fillPaint.alpha = alphaInt
                val baseline = entry.y + DANMAKU_PADDING_PX * 0.5f - strokePaint.fontMetrics.ascent
                canvas.drawText(entry.item.text, entry.x + DANMAKU_PADDING_PX, baseline, strokePaint)
                canvas.drawText(entry.item.text, entry.x + DANMAKU_PADDING_PX, baseline, fillPaint)
            }
            strokePaint.alpha = 255
            fillPaint.alpha = 255
        } finally {
            try {
                h.unlockCanvasAndPost(canvas)
            } catch (_: Throwable) {
            }
        }
    }

    private fun pickBucketIndex(width: Int): Int {
        for (i in BUCKET_WIDTHS.indices) {
            if (width <= BUCKET_WIDTHS[i]) return i
        }
        return BUCKET_WIDTHS.lastIndex
    }

    private fun allocateSlot(allocW: Int, drawW: Int, drawH: Int, useLarge: Boolean): AtlasSlot? {
        val startShelf = if (useLarge) NORMAL_SHELF_COUNT else 0
        val endShelf = if (useLarge) TOTAL_SHELF_COUNT else NORMAL_SHELF_COUNT
        val clampedAlloc = allocW.coerceAtMost(ATLAS_WIDTH)
        for (shelf in startShelf until endShelf) {
            val spans = shelves[shelf]
            var cursor = 0
            var insertIdx = 0
            for (i in 0 until spans.size) {
                val sp = spans[i]
                if (sp.x - cursor >= clampedAlloc) {
                    spans.add(insertIdx, Span(cursor, clampedAlloc))
                    return AtlasSlot(shelf, cursor, shelfTopY(shelf), drawW, drawH)
                }
                cursor = sp.x + sp.w
                insertIdx = i + 1
            }
            if (ATLAS_WIDTH - cursor >= clampedAlloc) {
                spans.add(Span(cursor, clampedAlloc))
                return AtlasSlot(shelf, cursor, shelfTopY(shelf), drawW, drawH)
            }
        }
        return null
    }

    private fun shelfTopY(shelf: Int): Int =
        if (shelf < NORMAL_SHELF_COUNT) {
            shelf * NORMAL_SHELF_HEIGHT
        } else {
            NORMAL_SHELF_COUNT * NORMAL_SHELF_HEIGHT + (shelf - NORMAL_SHELF_COUNT) * LARGE_SHELF_HEIGHT
        }

    private fun initGl(holder: SurfaceHolder): Boolean {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return false

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null
        ) {
            releaseGl()
            return false
        }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            releaseGl()
            return false
        }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            releaseGl()
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            releaseGl()
            return false
        }

        EGL14.eglSwapInterval(eglDisplay, 1)

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERT_SHADER)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAG_SHADER)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES30.glDeleteShader(vs)
            if (fs != 0) GLES30.glDeleteShader(fs)
            releaseGl()
            return false
        }
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            releaseGl()
            return false
        }

        uViewportLoc = GLES30.glGetUniformLocation(program, "uViewport")
        uAtlasLoc = GLES30.glGetUniformLocation(program, "uAtlas")

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        atlasTexId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, ATLAS_WIDTH, ATLAS_HEIGHT)

        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        vboId = vbo[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, MAX_VISIBLE_DANMAKU * 6 * 5 * 4, null, GLES30.GL_STREAM_DRAW)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)

        Log.i(TAG, "EGL/GLES3 danmaku ready: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
        return true
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun initScratchBitmaps() {
        for (i in BUCKET_WIDTHS.indices) {
            if (normalBuckets[i] == null) {
                val bmp = Bitmap.createBitmap(BUCKET_WIDTHS[i], NORMAL_SHELF_HEIGHT, Bitmap.Config.ARGB_8888)
                normalBuckets[i] = bmp
                normalCanvases[i] = Canvas(bmp)
            }
            if (largeBuckets[i] == null) {
                val bmp = Bitmap.createBitmap(BUCKET_WIDTHS[i], LARGE_SHELF_HEIGHT, Bitmap.Config.ARGB_8888)
                largeBuckets[i] = bmp
                largeCanvases[i] = Canvas(bmp)
            }
        }
    }

    private fun releaseScratchBitmaps() {
        for (i in BUCKET_WIDTHS.indices) {
            normalBuckets[i]?.recycle()
            normalBuckets[i] = null
            normalCanvases[i] = null
            largeBuckets[i]?.recycle()
            largeBuckets[i] = null
            largeCanvases[i] = null
        }
    }

    private fun releaseGl() {
        for (s in shelves) s.clear()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                if (atlasTexId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(atlasTexId), 0)
                    atlasTexId = 0
                }
                if (vboId != 0) {
                    GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
                    vboId = 0
                }
                if (program != 0) {
                    GLES30.glDeleteProgram(program)
                    program = 0
                }
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}

/**
 * Software Canvas [View] fallback (used on emulators where SurfaceView overlay composition differs).
 */
internal class SoftwareDanmakuView(
    context: Context,
    private val onPreparedCallback: () -> Unit,
) : View(context), DanmakuController {

    private val engine = DanmakuEngine(MAX_VISIBLE_DANMAKU)
    private val released = AtomicBoolean(false)
    private var running = false
    private var lastFrameMs = 0L

    @Volatile
    private var preparedFlag = false

    override val currentTimeMs: Long
        get() = engine.currentTimeMs

    override val isPrepared: Boolean
        get() = preparedFlag && !released.get()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = DANMAKU_STROKE_WIDTH_PX
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released.get() && !preparedFlag) {
            preparedFlag = true
            onPreparedCallback()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.setViewport(w, h)
    }

    override fun addDanmaku(item: DanmakuItem) {
        if (released.get()) return
        post {
            if (released.get()) return@post
            engine.enqueue(item)
            if (running) postInvalidateOnAnimation()
        }
    }

    override fun clearAll() {
        if (released.get()) return
        post {
            if (released.get()) return@post
            engine.clearAll()
            invalidate()
        }
    }

    override fun updateConfig(speed: Float, opacity: Float, maxLines: Int) {
        if (released.get()) return
        post {
            if (released.get()) return@post
            engine.updateConfig(speed, opacity, maxLines)
            invalidate()
        }
    }

    override fun pause() {
        if (released.get()) return
        post {
            running = false
            lastFrameMs = 0L
        }
    }

    override fun resume() {
        if (released.get()) return
        post {
            if (released.get()) return@post
            running = true
            lastFrameMs = 0L
            if (engine.hasWork()) postInvalidateOnAnimation()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        preparedFlag = false
        running = false
        engine.clearAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (released.get()) return
        if (running && engine.hasWork()) {
            val nowMs = SystemClock.uptimeMillis()
            val dtMs = if (lastFrameMs == 0L) 16L else (nowMs - lastFrameMs).coerceIn(1L, 100L)
            lastFrameMs = nowMs
            engine.step(
                dtMs = dtMs,
                measureItem = { item ->
                    val sz = item.textSizePx.coerceIn(12f, 128f)
                    strokePaint.textSize = sz
                    val w = ceil(strokePaint.measureText(item.text)) + DANMAKU_PADDING_PX * 2
                    val fm = strokePaint.fontMetrics
                    val h = ceil(fm.descent - fm.ascent) + DANMAKU_PADDING_PX
                    w to h
                },
                onActivate = { _, _, _ -> Unit },
                onExpired = {},
            )
            if (engine.hasWork()) postInvalidateOnAnimation()
        }
        val alphaInt = (engine.opacity * 255f).toInt().coerceIn(0, 255)
        engine.forEachActive { entry ->
            val sz = entry.item.textSizePx.coerceIn(12f, 128f)
            strokePaint.textSize = sz
            strokePaint.alpha = alphaInt
            fillPaint.textSize = sz
            fillPaint.color = entry.item.textColor
            fillPaint.alpha = alphaInt
            val baseline = entry.y + DANMAKU_PADDING_PX * 0.5f - strokePaint.fontMetrics.ascent
            canvas.drawText(entry.item.text, entry.x + DANMAKU_PADDING_PX, baseline, strokePaint)
            canvas.drawText(entry.item.text, entry.x + DANMAKU_PADDING_PX, baseline, fillPaint)
        }
        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }
}
