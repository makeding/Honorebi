package com.beeregg2001.komorebi.ui.live

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.SharedFlow
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku

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
    danmakuView: MutableState<IDanmakuView?>,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHeavyUiReady by rememberUpdatedState(heavyUiReady)
    val currentDualDisplay by rememberUpdatedState(dualDisplay)
    val currentFontSizeScale by rememberUpdatedState(fontSizeScale)

    LaunchedEffect(sessionToken) {
        danmakuView.value?.removeAllDanmakus(true)
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
                "top" -> BaseDanmaku.TYPE_FIX_TOP
                "bottom" -> BaseDanmaku.TYPE_FIX_BOTTOM
                else -> BaseDanmaku.TYPE_SCROLL_RL
            }
            if (danmakuType == BaseDanmaku.TYPE_SCROLL_RL) {
                if (scrollDanmakuCount >= SCROLL_DANMAKU_LIMIT_PER_WINDOW) return@collect
                scrollDanmakuCount++
            } else {
                if (fixedDanmakuCount >= FIXED_DANMAKU_LIMIT_PER_WINDOW) return@collect
                fixedDanmakuCount++
            }
            danmakuView.value?.let { view ->
                (view as? android.view.View)?.post {
                    if (comment.sessionToken != sessionToken || danmakuView.value !== view ||
                        !currentEnabled || !currentHeavyUiReady || currentDualDisplay || !view.isPrepared
                    ) return@post
                    val danmaku = view.config.mDanmakuFactory.createDanmaku(danmakuType) ?: return@post
                    danmaku.text = comment.text
                    danmaku.padding = 5
                    val sizeFactor = when (comment.size) {
                        "big" -> 1.5f
                        "small" -> 0.8f
                        else -> 1.0f
                    }
                    danmaku.textSize =
                        (32f * currentFontSizeScale * sizeFactor) * view.context.resources.displayMetrics.density
                    danmaku.textColor = colorCache.getOrPut(comment.color) {
                        runCatching { AndroidColor.parseColor(comment.color) }.getOrDefault(AndroidColor.WHITE)
                    }
                    danmaku.textShadowColor = AndroidColor.BLACK
                    danmaku.setTime(view.currentTime + 10)
                    view.addDanmaku(danmaku)
                }
            }
        }
    }
}
