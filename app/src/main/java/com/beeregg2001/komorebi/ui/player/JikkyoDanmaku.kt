package com.beeregg2001.komorebi.ui.player

import android.graphics.Color

internal fun jikkyoDanmakuType(position: String): DanmakuType = when (position) {
    "top" -> DanmakuType.FIX_TOP
    "bottom" -> DanmakuType.FIX_BOTTOM
    else -> DanmakuType.SCROLL_RL
}

internal fun jikkyoDanmaku(
    text: String,
    color: String,
    size: String,
    type: DanmakuType,
    baseSizePx: Float,
    showTimeMs: Long,
    colorCache: MutableMap<String, Int>,
): DanmakuItem {
    val scale = when (size) {
        "big" -> 1.5f
        "small" -> 0.8f
        else -> 1f
    }
    val argb = colorCache.getOrPut(color) {
        runCatching { Color.parseColor(color) }.getOrDefault(Color.WHITE)
    }
    return DanmakuItem(text, argb, baseSizePx * scale, type, showTimeMs)
}
