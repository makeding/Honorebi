package com.beeregg2001.komorebi.ui.subtitle

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import kotlin.math.ceil

private const val UNKNOWN_DURATION_MS = 5_000L
private const val MAX_RUNNING_RECHECK_MS = 1_000L

/** Recorded captions have a media clock and a separate, monotonic transition deadline. */
class RecordedCaptionTimeline(private val nowMs: () -> Long) {
    private class Layer {
        val queued = TreeMap<Long, NativeCaptionCue>()
        var visible: NativeCaptionCue? = null
        var deadline: Long? = null
    }
    private val layers = Array(2) { Layer() }
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    var generation: Long = 0L
        private set

    @Synchronized
    fun switchQuality(
        caption: NativeCaptionCue? = layers[0].visible,
        superimpose: NativeCaptionCue? = layers[1].visible,
    ) {
        generation++
        val displayed = arrayOf(caption, superimpose)
        layers.forEachIndexed { index, layer ->
            layer.visible = displayed[index]
            layer.queued.clear()
            if (layer.visible != null && layer.deadline == null) layer.deadline = nowMs() + 5_000L
        }
        invalidate()
    }

    @Synchronized
    fun clear(type: Int? = null) {
        var changed = false
        layers.forEachIndexed { index, layer ->
            if (type == null || type == index) {
                changed = changed || layer.queued.isNotEmpty() || layer.visible != null || layer.deadline != null
                layer.queued.clear()
                layer.visible = null
                layer.deadline = null
            }
        }
        if (changed) invalidate()
    }

    @Synchronized
    fun offer(source: Long, cue: NativeCaptionCue) {
        if (source != generation) return
        val layer = layers.getOrNull(cue.type) ?: return
        when (cue.timelineCommand) {
            NativeCaptionCue.TIMELINE_COMMAND_RESET -> {
                layer.queued.clear()
                layer.visible = null
                layer.deadline = null
            }
            NativeCaptionCue.TIMELINE_COMMAND_REPLACE_FROM -> layer.queued.tailMap(cue.ptsMs, true).clear()
            else -> {
                // The first new source cue retires only its own transition layer.
                layer.deadline = null
                layer.visible = null
                layer.queued[cue.ptsMs] = cue
                while (layer.queued.size > 12) layer.queued.pollFirstEntry()
            }
        }
        invalidate()
    }

    @Synchronized
    fun current(type: Int, positionMs: Long): NativeCaptionCue? {
        val layer = layers[type]
        layer.deadline?.let { deadline ->
            if (nowMs() < deadline) return layer.visible
            layer.deadline = null
            layer.visible = null
        }
        val entry = layer.queued.floorEntry(positionMs)
        val cue = entry?.value
        layer.visible = when {
            cue == null || cue.clearScreen || cue.images.isEmpty() -> null
            cue.durationMs == -1L -> cue
            positionMs < cue.ptsMs + (if (cue.durationMs > 0) cue.durationMs else 5_000L) -> cue
            else -> null
        }
        if (entry != null) layer.queued.headMap(entry.key, false).clear()
        return layer.visible
    }

    /**
     * Returns the next point where [current] can produce a different result.
     *
     * Media-time boundaries do not advance while paused. Quality-transition
     * deadlines use the monotonic wall clock and therefore remain active while
     * paused or buffering. A bounded recheck while running covers small player
     * clock changes that do not emit a discontinuity event.
     */
    @Synchronized
    fun nextUpdateDelayMs(
        positionMs: Long,
        clockRunning: Boolean,
        playbackSpeed: Float,
    ): Long? {
        val now = nowMs()
        var nextDelayMs: Long? = null
        layers.forEach { layer ->
            val transitionDelay = layer.deadline?.minus(now)?.coerceAtLeast(0L)
            nextDelayMs = minDelay(nextDelayMs, transitionDelay)
            if (layer.deadline != null || !clockRunning) return@forEach

            val currentEntry = layer.queued.floorEntry(positionMs)
            val currentCue = currentEntry?.value
            val expiryMs = currentCue
                ?.takeUnless { it.clearScreen || it.images.isEmpty() || it.durationMs == -1L }
                ?.let { cue -> cue.ptsMs + if (cue.durationMs > 0L) cue.durationMs else UNKNOWN_DURATION_MS }
                ?.takeIf { it > positionMs }
            val nextCueMs = layer.queued.higherKey(positionMs)
            val nextBoundaryMs = listOfNotNull(expiryMs, nextCueMs).minOrNull()
            if (nextBoundaryMs != null) {
                val speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
                val mediaDelay = ceil((nextBoundaryMs - positionMs).toDouble() / speed.toDouble())
                    .toLong()
                    .coerceAtLeast(1L)
                    .coerceAtMost(MAX_RUNNING_RECHECK_MS)
                nextDelayMs = minDelay(nextDelayMs, mediaDelay)
            }
        }
        return nextDelayMs
    }

    fun invalidate() {
        invalidations.trySend(Unit)
    }

    suspend fun awaitInvalidation(timeoutMs: Long?): Boolean {
        return if (timeoutMs == null) {
            invalidations.receive()
            true
        } else if (timeoutMs > 0L) {
            withTimeoutOrNull(timeoutMs) {
                invalidations.receive()
                true
            } ?: false
        } else false
    }

    private fun minDelay(current: Long?, candidate: Long?): Long? = when {
        candidate == null -> current
        current == null -> candidate
        else -> minOf(current, candidate)
    }
}
