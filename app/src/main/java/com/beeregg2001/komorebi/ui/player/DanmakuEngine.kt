package com.beeregg2001.komorebi.ui.player

import kotlin.math.max

enum class DanmakuEngineMode(val id: String) {
    GLES("GLES"),
    DFM("DFM");

    companion object {
        fun fromId(id: String?): DanmakuEngineMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GLES
    }
}

enum class DanmakuType {
    SCROLL_RL,
    FIX_TOP,
    FIX_BOTTOM,
}

data class DanmakuItem(
    val text: String,
    val textColor: Int,
    val textSizePx: Float,
    val type: DanmakuType = DanmakuType.SCROLL_RL,
    val showTimeMs: Long = 0L,
)

interface DanmakuController {
    val currentTimeMs: Long
    val isPrepared: Boolean
    fun addDanmaku(item: DanmakuItem)
    fun clearAll()
    fun updateConfig(speed: Float, opacity: Float, maxLines: Int)
    fun pause()
    fun resume()
    fun release()
}

internal const val MAX_VISIBLE_DANMAKU = 80
private const val BASE_SCROLL_DURATION_MS = 5_500f
private const val FIXED_DANMAKU_DURATION_MS = 3_800f
private const val SCROLL_LANE_HORIZONTAL_GAP_PX = 48f
private const val TOP_MARGIN_PX = 16f
private const val LANE_VERTICAL_PADDING_PX = 6f
private const val MAX_PENDING_DANMAKU = 256

/**
 * Pure-Kotlin danmaku scheduler, lane allocator, and position stepper.
 *
 * Thread safety: callers serialize access on the owning render thread (or lock)
 * except for [currentTimeMs], which is volatile for lock-free scheduling reads.
 */
internal class DanmakuEngine(
    private val maxVisible: Int = MAX_VISIBLE_DANMAKU,
) {
    class ActiveItem<T>(
        val item: DanmakuItem,
        val widthPx: Float,
        val heightPx: Float,
        val lane: Int,
        var x: Float,
        val y: Float,
        val speedPxPerSec: Float,
        var remainingMs: Float,
        val payload: T,
    )

    @Volatile
    var currentTimeMs: Long = 0L
        private set

    var speed: Float = 1.0f
        private set
    var opacity: Float = 1.0f
        private set
    var maxLines: Int = 0
        private set

    private var viewportWidth: Float = 1920f
    private var viewportHeight: Float = 1080f

    private val pending = ArrayList<DanmakuItem>(64)
    private val active = ArrayList<ActiveItem<Any?>>(maxVisible)

    fun setViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width.toFloat()
            viewportHeight = height.toFloat()
        }
    }

    fun updateConfig(speed: Float, opacity: Float, maxLines: Int) {
        this.speed = if (speed.isFinite() && speed > 0f) speed else 1.0f
        this.opacity = opacity.coerceIn(0f, 1f)
        this.maxLines = maxLines.coerceAtLeast(0)
    }

    fun enqueue(item: DanmakuItem) {
        if (item.text.isBlank()) return
        if (pending.size >= MAX_PENDING_DANMAKU) {
            pending.removeAt(0)
        }
        pending.add(item)
    }

    fun clearAll(onExpired: (Any?) -> Unit = {}) {
        pending.clear()
        for (i in 0 until active.size) {
            onExpired(active[i].payload)
        }
        active.clear()
    }

    fun hasWork(): Boolean = active.isNotEmpty() || pending.isNotEmpty()

    fun activeCount(): Int = active.size

    fun pendingCount(): Int = pending.size

    fun forEachActive(block: (ActiveItem<Any?>) -> Unit) {
        for (i in 0 until active.size) {
            block(active[i])
        }
    }

    /**
     * Advances the engine clock by [dtMs] milliseconds, activates due pending items,
     * updates positions, and recycles off-screen/expired items.
     */
    fun step(
        dtMs: Long,
        measureItem: (DanmakuItem) -> Pair<Float, Float>,
        onActivate: (DanmakuItem, Float, Float) -> Any?,
        onExpired: (Any?) -> Unit,
    ) {
        val clampedDtMs = dtMs.coerceIn(0L, 100L)
        currentTimeMs += clampedDtMs
        val dtSec = clampedDtMs / 1_000f

        // 1. Advance existing items and remove finished ones first so lanes & slots free up.
        var writeIdx = 0
        for (i in 0 until active.size) {
            val entry = active[i]
            val expired = when (entry.item.type) {
                DanmakuType.SCROLL_RL -> {
                    entry.x -= entry.speedPxPerSec * dtSec
                    entry.x + entry.widthPx < -16f
                }
                DanmakuType.FIX_TOP, DanmakuType.FIX_BOTTOM -> {
                    entry.remainingMs -= clampedDtMs
                    entry.remainingMs <= 0f
                }
            }
            if (expired) {
                onExpired(entry.payload)
            } else {
                active[writeIdx++] = entry
            }
        }
        while (active.size > writeIdx) {
            active.removeAt(active.size - 1)
        }

        // 2. Activate due pending items (bounded per frame to avoid upload spikes).
        var activatedThisFrame = 0
        var pendingWriteIdx = 0
        for (i in 0 until pending.size) {
            val item = pending[i]
            if (item.showTimeMs > currentTimeMs) {
                pending[pendingWriteIdx++] = item
                continue
            }
            // Drop stale items that are already far behind the current clock.
            if (currentTimeMs - item.showTimeMs > 2_500L) {
                continue
            }
            if (active.size >= maxVisible || activatedThisFrame >= 6) {
                // Keep slightly delayed items for the next frame if under cap; drop if screen is full.
                if (active.size < maxVisible) {
                    pending[pendingWriteIdx++] = item
                }
                continue
            }

            val (measuredW, measuredH) = measureItem(item)
            if (measuredW <= 0f || measuredH <= 0f) continue

            val placement = allocateLane(item.type, measuredW, measuredH) ?: continue
            val payload = onActivate(item, measuredW, measuredH) ?: continue

            val durationSec = (BASE_SCROLL_DURATION_MS / speed) / 1_000f
            val speedPxPerSec = if (item.type == DanmakuType.SCROLL_RL) {
                (viewportWidth + measuredW) / durationSec
            } else {
                0f
            }
            val startX = when (item.type) {
                DanmakuType.SCROLL_RL -> viewportWidth
                DanmakuType.FIX_TOP, DanmakuType.FIX_BOTTOM -> (viewportWidth - measuredW) * 0.5f
            }
            active.add(
                ActiveItem(
                    item = item,
                    widthPx = measuredW,
                    heightPx = measuredH,
                    lane = placement.lane,
                    x = startX,
                    y = placement.y,
                    speedPxPerSec = speedPxPerSec,
                    remainingMs = FIXED_DANMAKU_DURATION_MS,
                    payload = payload,
                )
            )
            activatedThisFrame++
        }
        while (pending.size > pendingWriteIdx) {
            pending.removeAt(pending.size - 1)
        }
    }

    private data class LanePlacement(val lane: Int, val y: Float)

    private fun allocateLane(type: DanmakuType, widthPx: Float, heightPx: Float): LanePlacement? {
        val laneStep = max(heightPx + LANE_VERTICAL_PADDING_PX, 32f)
        val availableHeight = max(laneStep, viewportHeight - TOP_MARGIN_PX * 2f)
        val viewportLanes = max(1, (availableHeight / laneStep).toInt())

        return when (type) {
            DanmakuType.SCROLL_RL -> {
                val laneCount = if (maxLines > 0) minOf(maxLines, viewportLanes) else viewportLanes
                val durationSec = (BASE_SCROLL_DURATION_MS / speed) / 1_000f
                val newSpeed = (viewportWidth + widthPx) / durationSec
                for (lane in 0 until laneCount) {
                    if (isScrollLaneAvailable(lane, newSpeed)) {
                        return LanePlacement(lane, TOP_MARGIN_PX + lane * laneStep)
                    }
                }
                null
            }
            DanmakuType.FIX_TOP -> {
                val laneCount = minOf(max(1, viewportLanes / 2), viewportLanes)
                for (lane in 0 until laneCount) {
                    if (isFixedLaneAvailable(DanmakuType.FIX_TOP, lane)) {
                        return LanePlacement(lane, TOP_MARGIN_PX + lane * laneStep)
                    }
                }
                null
            }
            DanmakuType.FIX_BOTTOM -> {
                val laneCount = minOf(max(1, viewportLanes / 2), viewportLanes)
                for (lane in 0 until laneCount) {
                    if (isFixedLaneAvailable(DanmakuType.FIX_BOTTOM, lane)) {
                        val y = viewportHeight - TOP_MARGIN_PX - (lane + 1) * laneStep
                        return LanePlacement(lane, y.coerceAtLeast(TOP_MARGIN_PX))
                    }
                }
                null
            }
        }
    }

    private fun isScrollLaneAvailable(lane: Int, newSpeedPxPerSec: Float): Boolean {
        for (i in 0 until active.size) {
            val existing = active[i]
            if (existing.item.type != DanmakuType.SCROLL_RL || existing.lane != lane) continue
            val existingRight = existing.x + existing.widthPx
            // Must have fully entered the screen with horizontal breathing room.
            if (existingRight + SCROLL_LANE_HORIZONTAL_GAP_PX > viewportWidth) {
                return false
            }
            // Prevent a faster new comment from catching up to the tail of an existing comment.
            if (newSpeedPxPerSec > existing.speedPxPerSec && existing.speedPxPerSec > 0f) {
                val existingTimeToExit = existingRight / existing.speedPxPerSec
                val newTimeToLeftEdge = (viewportWidth - SCROLL_LANE_HORIZONTAL_GAP_PX) / newSpeedPxPerSec
                if (newTimeToLeftEdge < existingTimeToExit) {
                    return false
                }
            }
        }
        return true
    }

    private fun isFixedLaneAvailable(type: DanmakuType, lane: Int): Boolean {
        for (i in 0 until active.size) {
            val existing = active[i]
            if (existing.item.type == type && existing.lane == lane) {
                return false
            }
        }
        return true
    }
}
