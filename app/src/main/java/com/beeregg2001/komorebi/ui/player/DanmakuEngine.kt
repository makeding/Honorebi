package com.beeregg2001.komorebi.ui.player

enum class DanmakuEngineMode(val id: String) {
    DFM_HARDWARE("DFM_HARDWARE"),
    GLES("GLES"),
    DFM("DFM");

    companion object {
        fun fromId(id: String?): DanmakuEngineMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DFM_HARDWARE
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
        var speedPxPerSec: Float,
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

    private class Pending(val item: DanmakuItem) {
        var attempted = false
        var width = 0f
        var height = 0f
        var payload: Any? = null
    }
    private val pending = ArrayList<Pending>(64)
    private val active = ArrayList<ActiveItem<Any?>>(maxVisible)
    private val retainer = DanmakuLaneLayout()

    fun setViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            viewportWidth = width.toFloat()
            viewportHeight = height.toFloat()
        }
    }

    fun updateConfig(speed: Float, opacity: Float, maxLines: Int) {
        val nextSpeed = if (speed.isFinite() && speed > 0f) speed else 1.0f
        for (entry in active) entry.speedPxPerSec *= nextSpeed / this.speed
        this.speed = nextSpeed
        this.opacity = opacity.coerceIn(0f, 1f)
        this.maxLines = maxLines.coerceAtLeast(0)
    }

    fun enqueue(item: DanmakuItem, onExpired: (Any?) -> Unit = {}) {
        if (item.text.isBlank()) return
        if (pending.size >= MAX_PENDING_DANMAKU) {
            pending.removeAt(0).payload?.let(onExpired)
        }
        pending.add(Pending(item))
    }

    fun prebuildNext(
        measureItem: (DanmakuItem) -> Pair<Float, Float>,
        onActivate: (DanmakuItem, Float, Float) -> Any?,
    ) {
        if (pending.count { it.payload != null } >= 2) return
        for (entry in pending) {
            if (entry.attempted || entry.item.showTimeMs <= currentTimeMs || entry.item.showTimeMs > currentTimeMs + 500) continue
            entry.attempted = true
            val (width, height) = measureItem(entry.item)
            entry.width = width
            entry.height = height
            if (width > 0f && height > 0f) entry.payload = onActivate(entry.item, width, height)
            break
        }
    }

    fun discardPrebuilt(onExpired: (Any?) -> Unit): Boolean {
        var discarded = false
        for (entry in pending) {
            val payload = entry.payload ?: continue
            entry.payload = null
            onExpired(payload)
            discarded = true
        }
        return discarded
    }

    fun clearAll(onExpired: (Any?) -> Unit = {}) {
        pending.forEach { it.payload?.let(onExpired) }
        pending.clear()
        for (i in 0 until active.size) {
            onExpired(active[i].payload)
        }
        active.clear()
        retainer.clear()
    }

    fun hasWork(): Boolean = active.isNotEmpty() || pending.isNotEmpty()

    fun activeCount(): Int = active.size

    fun pendingCount(): Int = pending.size

    fun earliestPendingShowTimeMs(): Long? {
        var minTime: Long? = null
        for (i in 0 until pending.size) {
            val showTime = pending[i].item.showTimeMs
            if (minTime == null || showTime < minTime) minTime = showTime
        }
        return minTime
    }

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
        val clampedDtMs = dtMs.coerceAtLeast(0L)
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
                retainer.remove(entry)
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
            val entry = pending[i]
            val item = entry.item
            if (item.showTimeMs > currentTimeMs) {
                pending[pendingWriteIdx++] = entry
                continue
            }
            if (active.size >= maxVisible || activatedThisFrame >= 6) {
                // Keep slightly delayed items for the next frame if under cap; drop if screen is full.
                if (active.size < maxVisible) {
                    pending[pendingWriteIdx++] = entry
                } else {
                    entry.payload?.let(onExpired)
                    entry.payload = null
                }
                continue
            }

            val (measuredW, measuredH) = if (entry.attempted) entry.width to entry.height else measureItem(item)
            if (measuredW <= 0f || measuredH <= 0f) {
                entry.payload?.let(onExpired)
                entry.payload = null
                continue
            }

            val ageMs = (currentTimeMs - item.showTimeMs).coerceAtLeast(0L)
            val durationMs = if (item.type == DanmakuType.SCROLL_RL) BASE_SCROLL_DURATION_MS / speed else FIXED_DANMAKU_DURATION_MS
            if (ageMs >= durationMs) {
                entry.payload?.let(onExpired)
                entry.payload = null
                continue
            }

            val placement = retainer.allocateLane(item.type, measuredW, measuredH, ageMs, viewportWidth, viewportHeight, speed, maxLines)
            if (placement == null) {
                entry.payload?.let(onExpired)
                entry.payload = null
                continue
            }
            val payload = entry.payload ?: onActivate(item, measuredW, measuredH) ?: continue
            entry.payload = null

            val durationSec = (BASE_SCROLL_DURATION_MS / speed) / 1_000f
            val speedPxPerSec = if (item.type == DanmakuType.SCROLL_RL) {
                (viewportWidth + measuredW) / durationSec
            } else {
                0f
            }
            val startX = when (item.type) {
                DanmakuType.SCROLL_RL -> viewportWidth - speedPxPerSec * ageMs / 1_000f
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
                    remainingMs = FIXED_DANMAKU_DURATION_MS - ageMs,
                    payload = payload,
                )
            )
            retainer.add(active.last())
            activatedThisFrame++
        }
        while (pending.size > pendingWriteIdx) {
            pending.removeAt(pending.size - 1)
        }
    }

}
