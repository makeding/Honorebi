package com.beeregg2001.komorebi.ui.live

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.ui.player.DanmakuItem
import com.beeregg2001.komorebi.ui.player.DanmakuType
import com.beeregg2001.komorebi.ui.player.DanmakuViewHandle
import kotlinx.coroutines.flow.SharedFlow

private const val DANMAKU_WINDOW_MS = 1_000L
private const val SCROLL_DANMAKU_LIMIT_PER_WINDOW = 16
private const val FIXED_DANMAKU_LIMIT_PER_WINDOW = 4

/** Source-specific comment scheduling, kept outside the common Danmaku view. */
@Composable
internal fun LiveDanmakuEffect(
    comments: SharedFlow<LiveComment>,
    sessionToken: LiveChannelSessionToken?,
    enabled: Boolean,
    heavyUiReady: Boolean,
    dualDisplay: Boolean,
    fontSizeScale: Float,
    danmakuView: MutableState<DanmakuViewHandle?>,
) {
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHeavyUiReady by rememberUpdatedState(heavyUiReady)
    val currentDualDisplay by rememberUpdatedState(dualDisplay)
    val currentFontSizeScale by rememberUpdatedState(fontSizeScale)

    LaunchedEffect(sessionToken) {
        danmakuView.value?.ifActive { it.clearAll() }
        var rateWindowStartMs = System.currentTimeMillis()
        var scrollDanmakuCount = 0
        var fixedDanmakuCount = 0
        val colorCache = HashMap<String, Int>()

        comments.collect { comment ->
            if (comment.sessionToken != sessionToken || !currentEnabled ||
                !currentHeavyUiReady || currentDualDisplay
            ) return@collect

            val nowMs = System.currentTimeMillis()
            if (nowMs - rateWindowStartMs >= DANMAKU_WINDOW_MS) {
                rateWindowStartMs = nowMs
                scrollDanmakuCount = 0
                fixedDanmakuCount = 0
            }
            val danmakuType = when (comment.position) {
                "top" -> DanmakuType.FIX_TOP
                "bottom" -> DanmakuType.FIX_BOTTOM
                else -> DanmakuType.SCROLL_RL
            }
            if (danmakuType == DanmakuType.SCROLL_RL) {
                if (scrollDanmakuCount >= SCROLL_DANMAKU_LIMIT_PER_WINDOW) return@collect
                scrollDanmakuCount++
            } else {
                if (fixedDanmakuCount >= FIXED_DANMAKU_LIMIT_PER_WINDOW) return@collect
                fixedDanmakuCount++
            }
            val sizeFactor = when (comment.size) {
                "big" -> 1.5f
                "small" -> 0.8f
                else -> 1.0f
            }
            val textColor = colorCache.getOrPut(comment.color) {
                runCatching { AndroidColor.parseColor(comment.color) }.getOrDefault(AndroidColor.WHITE)
            }
            val textSizePx = (32f * currentFontSizeScale * sizeFactor) * density
            danmakuView.value?.ifActive { controller ->
                if (!controller.isPrepared) return@ifActive
                controller.addDanmaku(
                    DanmakuItem(
                        text = comment.text,
                        textColor = textColor,
                        textSizePx = textSizePx,
                        type = danmakuType,
                        showTimeMs = controller.currentTimeMs + 10L,
                    )
                )
            }
        }
    }
}
