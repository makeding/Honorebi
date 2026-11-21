/*
 * Copyright (C) 2015 xqq <xqq@0ginr.com>
 * SPDX-License-Identifier: LGPL-2.1-only
 * Android Kotlin adaptation, 2026.
 * libwtfdanmaku 405952d90f6247375f053813dea2d5a61447536a:
 * R2LDanmaku.cpp, TopDanmaku.cpp, BottomDanmaku.cpp.
 */
package com.beeregg2001.komorebi.ui.player

internal class DanmakuLaneLayout {
    data class LanePlacement(val lane: Int, val y: Float)
    private val retainers = DanmakuType.entries.associateWith { java.util.TreeMap<Int, DanmakuEngine.ActiveItem<Any?>>() }

    fun clear() = retainers.values.forEach { it.clear() }

    fun remove(entry: DanmakuEngine.ActiveItem<Any?>) {
        retainers[entry.item.type]!!.values.remove(entry)
    }

    fun add(entry: DanmakuEngine.ActiveItem<Any?>) {
        val key = if (entry.item.type == DanmakuType.FIX_BOTTOM) (entry.y + entry.heightPx).toInt() else entry.y.toInt()
        retainers[entry.item.type]!![key] = entry
    }

    fun allocateLane(type: DanmakuType, widthPx: Float, heightPx: Float, ageMs: Long, viewportWidth: Float, viewportHeight: Float, speed: Float, maxLines: Int): LanePlacement? {
        val retained = retainers[type]!!
        val entries = if (type == DanmakuType.FIX_BOTTOM) retained.descendingMap().values else retained.values
        val limit = viewportHeight
        val newSpeed = (viewportWidth + widthPx) / (5500f / speed / 1000f)
        val newLeft = viewportWidth - ageMs / 1000f * newSpeed
        var edge = if (type == DanmakuType.FIX_BOTTOM) viewportHeight - 1f else 0f
        var lane = 0
        for (existing in entries) {
            if (type == DanmakuType.FIX_BOTTOM) {
                val itemBottom = existing.y + existing.heightPx
                if (itemBottom < edge && heightPx <= edge - itemBottom) break
                edge = existing.y - 1f
                if (edge - heightPx < 0f) {
                    edge = viewportHeight - 1f
                    break
                }
            } else {
                if (existing.y > edge && heightPx <= existing.y - edge) break
                if (type == DanmakuType.SCROLL_RL) {
                    val right = existing.x + existing.widthPx
                    val speedDiff = newSpeed - existing.speedPxPerSec
                    val suitable = if (newLeft <= right + 30f) false
                    else if (speedDiff <= 0f) right <= viewportWidth - 30f
                    else {
                        val elapsed = (viewportWidth - existing.x) / existing.speedPxPerSec
                        val meetMs = (((existing.speedPxPerSec * elapsed - existing.widthPx) / speedDiff) * 1000f).toLong()
                        meetMs * newSpeed / 1000f >= viewportWidth + 30f
                    }
                    if (suitable) {
                        edge = existing.y
                        break
                    }
                }
                edge = existing.y + existing.heightPx + 1f
                if (edge + heightPx > limit) {
                    if (type == DanmakuType.SCROLL_RL) return null
                    edge = 0f
                    break
                }
            }
            lane++
        }
        if (type == DanmakuType.SCROLL_RL && maxLines > 0 && lane >= maxLines) return null
        return LanePlacement(lane, if (type == DanmakuType.FIX_BOTTOM) edge - heightPx else edge)
    }
}
