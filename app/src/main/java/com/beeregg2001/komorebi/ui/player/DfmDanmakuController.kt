package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuSurfaceView
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * Legacy DanmakuFlameMaster adapter implementing [DanmakuController] so users can switch
 * between the hardware OpenGL ES 3.0 renderer and DFM from Settings.
 */
internal class DfmDanmakuController(
    context: Context,
    private val useSoftwareRendering: Boolean,
    private val onPreparedCallback: () -> Unit,
) : DanmakuController {

    private val danmakuContext: DanmakuContext = DanmakuContext.create().apply {
        setDanmakuStyle(1, 8.0f)
        setTypeface(Typeface.DEFAULT_BOLD)
        setDanmakuBold(true)
        setDuplicateMergingEnabled(false)
        setDanmakuSync(null)
        setCacheStuffer(SimpleTextCacheStuffer(), null)
        setMaximumVisibleSizeInScreen(MAX_VISIBLE_DANMAKU)
        preventOverlapping(
            mapOf(
                BaseDanmaku.TYPE_SCROLL_RL to true,
                BaseDanmaku.TYPE_FIX_TOP to true,
            )
        )
    }

    private val parser: BaseDanmakuParser = object : BaseDanmakuParser() {
        override fun parse(): IDanmakus = Danmakus()
    }

    private var lastSpeed: Float = Float.NaN
    private var lastOpacity: Float = Float.NaN
    private var lastMaxLines: Int = Int.MIN_VALUE

    val view: View
    private val danmakuView: IDanmakuView

    init {
        val created: View = if (useSoftwareRendering) {
            DanmakuView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
        } else {
            DanmakuSurfaceView(context)
        }
        created.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val idv = created as IDanmakuView
        idv.enableDanmakuDrawingCache(false)
        idv.setCallback(object : DrawHandler.Callback {
            override fun prepared() = onPreparedCallback()
            override fun updateTimer(timer: DanmakuTimer?) = Unit
            override fun danmakuShown(danmaku: BaseDanmaku?) = Unit
            override fun drawingFinished() = Unit
        })
        view = created
        danmakuView = idv
    }

    fun prepareWhenAttached(isActive: () -> Boolean) {
        view.post {
            if (isActive() && view.isAttachedToWindow) {
                danmakuView.prepare(parser, danmakuContext)
            }
        }
    }

    fun start() {
        danmakuView.start()
    }

    override val currentTimeMs: Long
        get() = danmakuView.currentTime

    override val isPrepared: Boolean
        get() = danmakuView.isPrepared

    override fun addDanmaku(item: DanmakuItem) {
        val dfmType = when (item.type) {
            DanmakuType.SCROLL_RL -> BaseDanmaku.TYPE_SCROLL_RL
            DanmakuType.FIX_TOP -> BaseDanmaku.TYPE_FIX_TOP
            DanmakuType.FIX_BOTTOM -> BaseDanmaku.TYPE_FIX_BOTTOM
        }
        val danmaku = danmakuView.config?.mDanmakuFactory?.createDanmaku(dfmType) ?: return
        danmaku.text = item.text
        danmaku.padding = 5
        danmaku.textSize = item.textSizePx
        danmaku.textColor = item.textColor
        danmaku.textShadowColor = AndroidColor.BLACK
        danmaku.setTime(item.showTimeMs)
        danmakuView.addDanmaku(danmaku)
    }

    override fun clearAll() {
        danmakuView.removeAllDanmakus(true)
    }

    override fun updateConfig(speed: Float, opacity: Float, maxLines: Int) {
        if (speed != lastSpeed) {
            danmakuContext.setScrollSpeedFactor(if (speed > 0f) 1.0f / speed else 1.0f)
            lastSpeed = speed
        }
        if (opacity != lastOpacity) {
            danmakuContext.setDanmakuTransparency(opacity)
            lastOpacity = opacity
        }
        if (maxLines != lastMaxLines) {
            danmakuContext.setMaximumLines(
                if (maxLines > 0) mapOf(BaseDanmaku.TYPE_SCROLL_RL to maxLines) else emptyMap(),
            )
            lastMaxLines = maxLines
        }
        if (view is DanmakuView) {
            val layerType = if (useSoftwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_HARDWARE
            if (view.layerType != layerType) view.setLayerType(layerType, null)
        }
    }

    override fun pause() {
        danmakuView.pause()
    }

    override fun resume() {
        danmakuView.resume()
    }

    override fun release() {
        try {
            danmakuView.stop()
        } finally {
            danmakuView.release()
        }
    }
}
