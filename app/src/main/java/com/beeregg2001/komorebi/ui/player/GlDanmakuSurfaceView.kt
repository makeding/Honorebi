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
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

private const val TAG = "GlDanmakuSurfaceView"

private const val ATLAS_WIDTH = 2048
private const val ATLAS_HEIGHT = 2048
private const val NORMAL_SHELF_HEIGHT = 96
private const val TOTAL_SHELF_COUNT = ATLAS_HEIGHT / NORMAL_SHELF_HEIGHT
private fun danmakuStrokeWidth(textSize: Float): Float = (textSize / 18f).coerceAtLeast(1f)
private const val DANMAKU_PADDING_PX = 10
internal const val TILE_WIDTH = 510
internal const val TILE_HEIGHT = NORMAL_SHELF_HEIGHT - 2
private const val MAX_ATLAS_QUADS = ATLAS_WIDTH / 128 * TOTAL_SHELF_COUNT

internal fun measureDanmakuText(text: String, paint: Paint): Pair<Float, Float> {
    var start = 0
    var count = 0
    var width = 0f
    do {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        width = maxOf(width, paint.measureText(text, start, end))
        count++
        start = end + 1
    } while (start <= text.length)
    val fm = paint.fontMetrics
    return (ceil(width) + DANMAKU_PADDING_PX * 2) to
        (ceil((fm.descent - fm.ascent) * count) + DANMAKU_PADDING_PX)
}

internal fun drawDanmakuText(canvas: Canvas, text: String, x: Float, y: Float, stroke: Paint, fill: Paint) {
    val fm = stroke.fontMetrics
    val lineHeight = fm.descent - fm.ascent
    var start = 0
    var index = 0
    do {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val baseline = y + DANMAKU_PADDING_PX * 0.5f - fm.ascent + index * lineHeight
        canvas.drawText(text, start, end, x + DANMAKU_PADDING_PX, baseline, stroke)
        canvas.drawText(text, start, end, x + DANMAKU_PADDING_PX, baseline, fill)
        start = end + 1
        index++
    } while (start <= text.length)
}

internal fun drawDanmakuTile(canvas: Canvas, text: String, x: Int, y: Int, stroke: Paint, fill: Paint) =
    drawDanmakuText(canvas, text, 1f - x, 1f - y, stroke, fill)

private val BUCKET_WIDTHS = intArrayOf(128, 256, 384, TILE_WIDTH + 2)

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
precision highp float;
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
        val offsetX: Int = 0,
        val offsetY: Int = 0,
    )

    private val engine = DanmakuEngine(MAX_VISIBLE_DANMAKU)
    private val released = AtomicBoolean(false)
    private val surfaceGeneration = AtomicInteger(0)
    private val releaseComplete = CountDownLatch(1)

    @Volatile
    private var preparedFlag = false

    override val currentTimeMs: Long
        get() = clock.positionMs()

    override val isPrepared: Boolean
        get() = preparedFlag && !released.get()

    private val renderThread = HandlerThread("GlDanmakuThread").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    // Render-thread-only state
    private var choreographer: Choreographer? = null
    private var surfaceReady = false
    private var running = false
    private var frameLoopActive = false
    private var pendingWakeScheduled = false
    private var surfaceCleared = true
    private var useGl = false
    private var surfaceWidth = 1920
    private var surfaceHeight = 1080
    private val clock = DanmakuPlaybackClock()
    private var activeHolder: SurfaceHolder? = null

    // EGL / GLES 3.0 state (render thread only)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var uViewportLoc = -1
    private var uAtlasLoc = -1
    private var atlasTexId = 0
    private val vboIds = IntArray(2)
    private val vaoIds = IntArray(2)
    private var vboIndex = 0

    private val vertexArray = FloatArray(MAX_ATLAS_QUADS * 6 * 5)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_ATLAS_QUADS * 6 * 5 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val shelves = Array(TOTAL_SHELF_COUNT) { ArrayList<Span>(8) }
    private val normalBuckets = arrayOfNulls<Bitmap>(BUCKET_WIDTHS.size)
    private val normalCanvases = arrayOfNulls<Canvas>(BUCKET_WIDTHS.size)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = danmakuStrokeWidth(textSize)
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val pendingWakeRunnable = Runnable {
        pendingWakeScheduled = false
        maybeStartFrameLoop()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!surfaceReady || !running || released.get()) {
                frameLoopActive = false
                return
            }
            stepAndRender(frameTimeNanos)
            scheduleNextFrameOrSleep()
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
            engine.enqueue(item, ::recyclePayload)
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
            if (released.get()) return@post
            running = false
            clock.pause()
            stopFrameLoop()
        }
    }

    override fun resume() {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            running = true
            clock.resume()
            maybeStartFrameLoop()
        }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        preparedFlag = false
        holder.removeCallback(this)
        val cleanup = {
            try {
                running = false
                surfaceReady = false
                stopFrameLoop()
                engine.clearAll(::recyclePayload)
                releaseGl()
                releaseScratchBitmaps()
                activeHolder = null
            } finally {
                releaseComplete.countDown()
            }
        }
        if (Looper.myLooper() == renderThread.looper) cleanup() else renderHandler.post(cleanup)
        awaitCompletion(releaseComplete)
        renderThread.quitSafely()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released.get()) return
        val generation = surfaceGeneration.incrementAndGet()
        renderHandler.post {
            if (released.get() || generation != surfaceGeneration.get()) return@post
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
        val generation = surfaceGeneration.get()
        renderHandler.post {
            if (released.get() || generation != surfaceGeneration.get()) return@post
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            engine.setViewport(surfaceWidth, surfaceHeight)
            if (surfaceReady && useGl) {
                GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
                GLES30.glUseProgram(program)
                GLES30.glUniform2f(uViewportLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
                drawCurrentFrame()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released.get()) {
            awaitCompletion(releaseComplete)
            return
        }
        surfaceGeneration.incrementAndGet()
        preparedFlag = false
        val latch = CountDownLatch(1)
        val cleanup = {
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
        if (Looper.myLooper() == renderThread.looper) cleanup() else renderHandler.post(cleanup)
        awaitCompletion(latch)
    }

    private fun awaitCompletion(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun maybeStartFrameLoop() {
        if (!surfaceReady || !running || frameLoopActive || released.get()) return
        if (!engine.hasWork()) return
        if (engine.activeCount() > 0) {
            renderHandler.removeCallbacks(pendingWakeRunnable)
            pendingWakeScheduled = false
            frameLoopActive = true
            choreographer?.postFrameCallback(frameCallback)
        } else {
            scheduleNextFrameOrSleep()
        }
    }

    private fun scheduleNextFrameOrSleep() {
        if (!surfaceReady || !running || released.get()) {
            stopFrameLoop()
            return
        }
        if (engine.activeCount() > 0) {
            renderHandler.removeCallbacks(pendingWakeRunnable)
            pendingWakeScheduled = false
            frameLoopActive = true
            choreographer?.postFrameCallback(frameCallback)
            return
        }
        frameLoopActive = false
        if (!surfaceCleared) {
            drawCurrentFrame()
        }
        val earliest = engine.earliestPendingShowTimeMs() ?: return
        val delayMs = (earliest - clock.positionMs()).coerceAtLeast(0L)
        if (delayMs == 0L) {
            renderHandler.removeCallbacks(pendingWakeRunnable)
            pendingWakeScheduled = false
            frameLoopActive = true
            choreographer?.postFrameCallback(frameCallback)
        } else {
            renderHandler.removeCallbacks(pendingWakeRunnable)
            pendingWakeScheduled = true
            renderHandler.postDelayed(pendingWakeRunnable, delayMs)
        }
    }

    private fun stopFrameLoop() {
        if (frameLoopActive) {
            choreographer?.removeFrameCallback(frameCallback)
            frameLoopActive = false
        }
        if (pendingWakeScheduled) {
            renderHandler.removeCallbacks(pendingWakeRunnable)
            pendingWakeScheduled = false
        }
    }

    private fun stepAndRender(frameTimeNanos: Long) {
        val startedNs = System.nanoTime()
        engine.step(
            dtMs = clock.positionMs(frameTimeNanos) - engine.currentTimeMs,
            measureItem = ::measureItem,
            onActivate = { item, width, height ->
                activateItem(item, width, height) ?: if (engine.discardPrebuilt(::recyclePayload)) {
                    activateItem(item, width, height)
                } else null
            },
            onExpired = ::recyclePayload,
        )
        if (engine.activeCount() > 0 || !surfaceCleared) {
            drawCurrentFrame()
        }
        if (useGl && System.nanoTime() - startedNs < 4_000_000L) {
            engine.prebuildNext(
                measureItem = ::measureItem,
                onActivate = { item, width, height ->
                    if (item.text.length <= 512 && width <= TILE_WIDTH * 2 && height <= TILE_HEIGHT * 2) {
                        activateItem(item, width, height)
                    } else null
                },
            )
        }
    }

    private fun measureItem(item: DanmakuItem): Pair<Float, Float> {
        val effectiveSize = item.textSizePx.coerceIn(12f, 128f)
        strokePaint.textSize = effectiveSize
        strokePaint.strokeWidth = danmakuStrokeWidth(effectiveSize)
        return measureDanmakuText(item.text, strokePaint)
    }

    private fun activateItem(item: DanmakuItem, widthPx: Float, heightPx: Float): Any? {
        if (!useGl) return Unit
        val effectiveSize = item.textSizePx.coerceIn(12f, 128f)
        strokePaint.textSize = effectiveSize
        strokePaint.strokeWidth = danmakuStrokeWidth(effectiveSize)
        fillPaint.textSize = effectiveSize
        fillPaint.color = item.textColor
        val slots = ArrayList<AtlasSlot>()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexId)
        val w = widthPx.toInt()
        val h = heightPx.toInt()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val tileW = minOf(TILE_WIDTH, w - x)
                val tileH = minOf(TILE_HEIGHT, h - y)
                val bucketIdx = pickBucketIndex(tileW + 2)
                val allocated = allocateSlot(BUCKET_WIDTHS[bucketIdx], tileW, tileH)
                if (allocated == null) {
                    recyclePayload(slots)
                    return null
                }
                val slot = AtlasSlot(allocated.shelf, allocated.x, allocated.y, tileW, tileH, x, y)
                slots.add(slot)
                val canvas = normalCanvases[bucketIdx]!!
                val bitmap = normalBuckets[bucketIdx]!!
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawDanmakuTile(canvas, item.text, x, y, strokePaint, fillPaint)
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, slot.x, slot.y, bitmap)
                x += tileW
            }
            y += TILE_HEIGHT
        }
        return slots
    }

    private fun recyclePayload(payload: Any?) {
        val slots = payload as? List<*> ?: return
        for (value in slots) {
            val slot = value as AtlasSlot
            val spans = shelves[slot.shelf]
            for (i in 0 until spans.size) {
                if (spans[i].x == slot.x) {
                    spans.removeAt(i)
                    break
                }
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
            val slots = entry.payload as? List<*> ?: return@forEachActive
            for (value in slots) {
                val slot = value as AtlasSlot
                val x0 = entry.x + slot.offsetX
                val y0 = entry.y + slot.offsetY
                val x1 = x0 + slot.drawW
                val y1 = y0 + slot.drawH
                val u0 = (slot.x + 1) * invW
                val v0 = (slot.y + 1) * invH
                val u1 = (slot.x + 1 + slot.drawW) * invW
                val v1 = (slot.y + 1 + slot.drawH) * invH

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
        }

        if (quadCount > 0) {
            vertexBuffer.clear()
            vertexBuffer.put(vertexArray, 0, idx)
            vertexBuffer.flip()

            val bufIdx = vboIndex
            vboIndex = bufIdx xor 1
            GLES30.glBindVertexArray(vaoIds[bufIdx])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[bufIdx])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, idx * 4, vertexBuffer)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quadCount * 6)
            GLES30.glBindVertexArray(0)
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
                strokePaint.strokeWidth = danmakuStrokeWidth(effectiveSize)
                strokePaint.alpha = alphaInt
                fillPaint.textSize = effectiveSize
                fillPaint.color = entry.item.textColor
                fillPaint.alpha = alphaInt
                drawDanmakuText(canvas, entry.item.text, entry.x, entry.y, strokePaint, fillPaint)
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

    private fun allocateSlot(allocW: Int, drawW: Int, drawH: Int): AtlasSlot? {
        val clampedAlloc = allocW.coerceAtMost(ATLAS_WIDTH)
        for (shelf in 0 until TOTAL_SHELF_COUNT) {
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

    private fun shelfTopY(shelf: Int): Int = shelf * NORMAL_SHELF_HEIGHT

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

        GLES30.glGenVertexArrays(2, vaoIds, 0)
        GLES30.glGenBuffers(2, vboIds, 0)
        val stride = 5 * 4
        val bufferSizeBytes = MAX_ATLAS_QUADS * 6 * stride
        for (i in 0..1) {
            GLES30.glBindVertexArray(vaoIds[i])
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboIds[i])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bufferSizeBytes, null, GLES30.GL_STREAM_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 4 * 4)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uViewportLoc, surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTexId)
        GLES30.glUniform1i(uAtlasLoc, 0)

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
        }
    }

    private fun releaseScratchBitmaps() {
        for (i in BUCKET_WIDTHS.indices) {
            normalBuckets[i]?.recycle()
            normalBuckets[i] = null
            normalCanvases[i] = null
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
                GLES30.glBindVertexArray(0)
                if (vaoIds[0] != 0 || vaoIds[1] != 0) {
                    GLES30.glDeleteVertexArrays(2, vaoIds, 0)
                    vaoIds.fill(0)
                }
                if (vboIds[0] != 0 || vboIds[1] != 0) {
                    GLES30.glDeleteBuffers(2, vboIds, 0)
                    vboIds.fill(0)
                }
                vboIndex = 0
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
    private val clock = DanmakuPlaybackClock()

    @Volatile
    private var preparedFlag = false

    override val currentTimeMs: Long
        get() = clock.positionMs()

    override val isPrepared: Boolean
        get() = preparedFlag && !released.get()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = danmakuStrokeWidth(textSize)
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

    override fun onDetachedFromWindow() {
        preparedFlag = false
        super.onDetachedFromWindow()
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
            if (released.get()) return@post
            running = false
            clock.pause()
        }
    }

    override fun resume() {
        if (released.get()) return
        post {
            if (released.get()) return@post
            running = true
            clock.resume()
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
            engine.step(
                dtMs = clock.positionMs() - engine.currentTimeMs,
                measureItem = { item ->
                    val sz = item.textSizePx.coerceIn(12f, 128f)
                    strokePaint.textSize = sz
                    measureDanmakuText(item.text, strokePaint)
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
            strokePaint.strokeWidth = danmakuStrokeWidth(sz)
            strokePaint.alpha = alphaInt
            fillPaint.textSize = sz
            fillPaint.color = entry.item.textColor
            fillPaint.alpha = alphaInt
            drawDanmakuText(canvas, entry.item.text, entry.x, entry.y, strokePaint, fillPaint)
        }
        strokePaint.alpha = 255
        fillPaint.alpha = 255
    }
}
