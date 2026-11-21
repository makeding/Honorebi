package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun createDanmakuBackend(
    context: Context,
    mode: DanmakuEngineMode,
    useSoftwareRendering: Boolean,
    onPrepared: () -> Unit,
): Pair<View, DanmakuController> = when (mode) {
    DanmakuEngineMode.GLES -> if (useSoftwareRendering) {
        SoftwareDanmakuView(context, onPrepared).let { it to it }
    } else {
        GlDanmakuSurfaceView(context, onPrepared).let { it to it }
    }
    DanmakuEngineMode.DFM -> DfmDanmakuController(context, useSoftwareRendering, onPrepared).let { it.view to it }
    DanmakuEngineMode.DFM_HARDWARE -> DfmDanmakuController(context, false, onPrepared, useHardwareView = true).let { it.view to it }
}

/** Common Android Danmaku view. Callers retain source-specific scheduling and session fencing. */
@Composable
fun DanmakuOverlay(
    modifier: Modifier = Modifier,
    useSoftwareRendering: Boolean = false,
    engineMode: DanmakuEngineMode = DanmakuEngineMode.DFM_HARDWARE,
    speed: Float = 1.0f,
    opacity: Float = 1.0f,
    maxLines: Int = 0,
    sessionKey: Any? = null,
    running: Boolean,
    onViewCreated: (DanmakuViewHandle) -> Unit,
    onViewPrepared: (DanmakuViewHandle) -> Unit = {},
    onViewReleased: (DanmakuViewHandle) -> Unit = {},
) {
    val latestRunning by rememberUpdatedState(running)
    val latestOnViewCreated by rememberUpdatedState(onViewCreated)
    val latestOnViewPrepared by rememberUpdatedState(onViewPrepared)
    val latestOnViewReleased by rememberUpdatedState(onViewReleased)
    val viewBindings = remember { IdentityHashMap<View, Pair<DanmakuController, DanmakuViewLifecycle>>() }

    key(sessionKey, useSoftwareRendering, engineMode) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val lifecycle = DanmakuViewLifecycle()
                lateinit var handle: DanmakuViewHandle
                lateinit var hostView: View

                val onPrepared: () -> Unit = {
                    hostView.post {
                        if (!handle.view.isPrepared) return@post
                        val accepted = lifecycle.prepared(
                            running = latestRunning,
                            start = {
                                val controller = handle.view
                                if (controller is DfmDanmakuController) {
                                    controller.start()
                                } else {
                                    controller.resume()
                                }
                            },
                            pause = { handle.view.pause() },
                        )
                        if (accepted) {
                            latestOnViewPrepared(handle)
                        }
                    }
                }

                val (createdView, controller) = createDanmakuBackend(context, engineMode, useSoftwareRendering, onPrepared)
                hostView = createdView

                handle = DanmakuViewHandle(controller, lifecycle)
                hostView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                controller.updateConfig(speed = speed, opacity = opacity, maxLines = maxLines)
                if (controller is DfmDanmakuController) {
                    controller.prepareWhenAttached { lifecycle.ifActive { } }
                }
                viewBindings[hostView] = controller to lifecycle
                latestOnViewCreated(handle)
                hostView
            },
            update = { view ->
                viewBindings[view]?.let { (controller, lifecycle) ->
                    controller.updateConfig(speed = speed, opacity = opacity, maxLines = maxLines)
                    lifecycle.updateRunning(
                        running = running,
                        isPrepared = controller.isPrepared,
                        resume = { controller.resume() },
                        pause = { controller.pause() },
                    )
                }
            },
            onRelease = { view ->
                viewBindings.remove(view)?.let { (controller, lifecycle) ->
                    val handle = DanmakuViewHandle(controller, lifecycle)
                    try {
                        lifecycle.release {
                            controller.release()
                        }
                    } finally {
                        latestOnViewReleased(handle)
                    }
                }
            },
        )
    }
}

class DanmakuViewHandle internal constructor(
    val view: DanmakuController,
    private val lifecycle: DanmakuViewLifecycle,
) {
    fun ifActive(block: (DanmakuController) -> Unit): Boolean = lifecycle.ifActive {
        block(view)
    }
}

/** Serializes prepare, background scheduling, state updates, and release for one view. */
internal class DanmakuViewLifecycle {
    private val released = AtomicBoolean(false)
    private var appliedRunning: Boolean? = null

    fun ifActive(block: () -> Unit): Boolean = synchronized(this) {
        if (released.get()) return@synchronized false
        block()
        true
    }

    fun prepared(
        running: Boolean,
        start: () -> Unit,
        pause: () -> Unit,
    ): Boolean = ifActive {
        start()
        if (!running) pause()
        appliedRunning = running
    }

    fun updateRunning(
        running: Boolean,
        isPrepared: Boolean,
        resume: () -> Unit,
        pause: () -> Unit,
    ): Boolean {
        var changed = false
        ifActive {
            if (isPrepared && appliedRunning != null && appliedRunning != running) {
                if (running) resume() else pause()
                appliedRunning = running
                changed = true
            }
        }
        return changed
    }

    fun release(block: () -> Unit): Boolean = synchronized(this) {
        if (!released.compareAndSet(false, true)) return@synchronized false
        block()
        true
    }
}
