package com.beeregg2001.komorebi.ui.player

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuSurfaceView
import master.flame.danmaku.ui.widget.DanmakuView
import android.graphics.Color as AndroidColor

private const val MAX_VISIBLE_DANMAKU = 80

/** Common Android Danmaku view. Callers retain source-specific scheduling and session fencing. */
@Composable
fun DanmakuOverlay(
    modifier: Modifier = Modifier,
    useSoftwareRendering: Boolean = false,
    speed: Float = 1.0f,
    opacity: Float = 1.0f,
    maxLines: Int = 0,
    sessionKey: Any? = null,
    onViewCreated: (IDanmakuView) -> Unit,
) {
    val danmakuContext = remember(sessionKey) {
        DanmakuContext.create().apply {
            setDanmakuStyle(1, 8.0f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setDanmakuBold(true)
            setDuplicateMergingEnabled(false)
            setDanmakuSync(null)
            setCacheStuffer(
                master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer(),
                null,
            )
            setMaximumVisibleSizeInScreen(MAX_VISIBLE_DANMAKU)
            preventOverlapping(
                mapOf(
                    BaseDanmaku.TYPE_SCROLL_RL to true,
                    BaseDanmaku.TYPE_FIX_TOP to true,
                )
            )
        }
    }
    val parser = remember(sessionKey) {
        object : BaseDanmakuParser() {
            override fun parse(): IDanmakus = Danmakus()
        }
    }

    LaunchedEffect(danmakuContext, speed, opacity, maxLines) {
        danmakuContext.setScrollSpeedFactor(if (speed > 0f) 1.0f / speed else 1.0f)
        danmakuContext.setDanmakuTransparency(opacity)
        danmakuContext.setMaximumLines(
            if (maxLines > 0) mapOf(BaseDanmaku.TYPE_SCROLL_RL to maxLines) else emptyMap(),
        )
    }

    key(sessionKey) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                fun <T> setup(view: T): T where T : View, T : IDanmakuView = view.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Live and archived comments are overwhelmingly one-shot strings. Keeping the
                    // library bitmap cache disabled avoids competing with video/caption memory.
                    enableDanmakuDrawingCache(false)
                    setCallback(object : DrawHandler.Callback {
                        override fun prepared() = start()
                        override fun updateTimer(timer: DanmakuTimer?) = Unit
                        override fun danmakuShown(danmaku: BaseDanmaku?) = Unit
                        override fun drawingFinished() = Unit
                    })
                    post { if (isAttachedToWindow) prepare(parser, danmakuContext) }
                    onViewCreated(this)
                }
                if (useSoftwareRendering) {
                    setup(DanmakuView(context)).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    }
                } else {
                    // DanmakuView は UI スレッドの onDraw で描くため、Compose の再コンポーズや GC で
                    // UI スレッドが 100ms 程度止まるだけで流れるコメントが目に見えて止まる。
                    // DanmakuSurfaceView はライブラリ側の描画スレッドが専用 Surface に直接描くので、
                    // UI スレッドの引っかかりからコメントの動きを切り離せる（低スペック TV 向け）。
                    // DanmakuTextureView は Compose の AndroidView 配下だと SurfaceTexture が生成されず
                    // 何も描画されないため使えない。SurfaceView は init で setZOrderMediaOverlay(true) と
                    // 半透明フォーマットが設定済みで、動画の SurfaceView の上・Compose UI の下に重なる。
                    setup(DanmakuSurfaceView(context))
                }
            },
            update = { view ->
                if (view is DanmakuView) {
                    val layerType = if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
                    if (view.layerType != layerType) view.setLayerType(layerType, null)
                }
            },
            onRelease = { view ->
                (view as? IDanmakuView)?.let {
                    it.stop()
                    it.release()
                }
            },
        )
    }
}
