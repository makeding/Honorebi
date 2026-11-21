package com.beeregg2001.komorebi.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.ui.player.jikkyoDanmaku
import com.beeregg2001.komorebi.ui.player.jikkyoDanmakuType
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
    fontSizeScale: Float,
    danmakuView: MutableState<DanmakuViewHandle?>,
) {
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentFontSizeScale by rememberUpdatedState(fontSizeScale)

    LaunchedEffect(sessionToken) {
        danmakuView.value?.ifActive { it.clearAll() }
        var rateWindowStartMs = System.currentTimeMillis()
        var scrollDanmakuCount = 0
        var fixedDanmakuCount = 0
        val colorCache = HashMap<String, Int>()

        comments.collect { comment ->
            if (comment.sessionToken != sessionToken || !currentEnabled) return@collect

            val nowMs = System.currentTimeMillis()
            if (nowMs - rateWindowStartMs >= DANMAKU_WINDOW_MS) {
                rateWindowStartMs = nowMs
                scrollDanmakuCount = 0
                fixedDanmakuCount = 0
            }
            val danmakuType = jikkyoDanmakuType(comment.position)
            if (danmakuType == DanmakuType.SCROLL_RL) {
                if (scrollDanmakuCount >= SCROLL_DANMAKU_LIMIT_PER_WINDOW) return@collect
                scrollDanmakuCount++
            } else {
                if (fixedDanmakuCount >= FIXED_DANMAKU_LIMIT_PER_WINDOW) return@collect
                fixedDanmakuCount++
            }
            danmakuView.value?.ifActive { controller ->
                if (!controller.isPrepared) return@ifActive
                controller.addDanmaku(
                    jikkyoDanmaku(
                        comment.text, comment.color, comment.size, danmakuType,
                        32f * currentFontSizeScale * density, controller.currentTimeMs + 10L, colorCache,
                    )
                )
            }
        }
    }
}
