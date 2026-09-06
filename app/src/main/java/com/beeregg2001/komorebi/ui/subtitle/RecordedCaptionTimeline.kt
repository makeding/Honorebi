package com.beeregg2001.komorebi.ui.subtitle

import java.util.TreeMap

/** Recorded captions have a media clock and a separate, monotonic transition deadline. */
class RecordedCaptionTimeline(private val nowMs: () -> Long) {
    private class Layer {
        val queued = TreeMap<Long, NativeCaptionCue>()
        var visible: NativeCaptionCue? = null
        var deadline: Long? = null
    }
    private val layers = Array(2) { Layer() }
    var generation: Long = 0L
        private set

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
    }

    fun clear(type: Int? = null) {
        layers.forEachIndexed { index, layer ->
            if (type == null || type == index) {
                layer.queued.clear()
                layer.visible = null
                layer.deadline = null
            }
        }
    }

    fun offer(source: Long, cue: NativeCaptionCue) {
        if (source != generation) return
        val layer = layers.getOrNull(cue.type) ?: return
        when (cue.timelineCommand) {
            NativeCaptionCue.TIMELINE_COMMAND_RESET -> clear(cue.type)
            NativeCaptionCue.TIMELINE_COMMAND_REPLACE_FROM -> layer.queued.tailMap(cue.ptsMs, true).clear()
            else -> {
                // The first new source cue retires only its own transition layer.
                layer.deadline = null
                layer.visible = null
                layer.queued[cue.ptsMs] = cue
                while (layer.queued.size > 12) layer.queued.pollFirstEntry()
            }
        }
    }

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
}
