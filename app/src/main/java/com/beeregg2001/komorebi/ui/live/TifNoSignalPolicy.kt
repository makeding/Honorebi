package com.beeregg2001.komorebi.ui.live

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.beeregg2001.komorebi.data.TifNoSignalStyle

internal enum class NoSignalRenderMode {
    BANNER,
    BOUNCE,
    SCREENSAVER,
    EFFECT,
}

internal const val TIF_NO_SIGNAL_MIN_WIDTH_DP = 480f

internal fun previewNoSignalRenderMode(style: TifNoSignalStyle): NoSignalRenderMode =
    when (style) {
        TifNoSignalStyle.BANNER -> NoSignalRenderMode.BANNER
        TifNoSignalStyle.SCREENSAVER -> NoSignalRenderMode.SCREENSAVER
        TifNoSignalStyle.DVD_BOUNCE,
        TifNoSignalStyle.HONOREBI_BOUNCE -> NoSignalRenderMode.BOUNCE
        TifNoSignalStyle.ARIB_COLOR_BARS,
        TifNoSignalStyle.ANALOG_STATIC,
        TifNoSignalStyle.SIGNAL_WAVE,
        TifNoSignalStyle.MINIMAL_CLOCK,
        TifNoSignalStyle.AURORA_AMBIENT,
        TifNoSignalStyle.DOT_MATRIX,
        TifNoSignalStyle.STARFIELD,
        TifNoSignalStyle.SIGNAL_RADAR -> NoSignalRenderMode.EFFECT
    }

internal fun resolveNoSignalRenderMode(
    style: TifNoSignalStyle,
    themedReady: Boolean,
    suppressed: Boolean,
    hardwareAccelerated: Boolean,
    animationsEnabled: Boolean,
    degraded: Boolean,
    containerWidthDp: Float,
): NoSignalRenderMode {
    if (!themedReady || suppressed || containerWidthDp < TIF_NO_SIGNAL_MIN_WIDTH_DP) {
        return NoSignalRenderMode.BANNER
    }
    return when (style) {
        TifNoSignalStyle.BANNER -> NoSignalRenderMode.BANNER
        TifNoSignalStyle.SCREENSAVER -> NoSignalRenderMode.SCREENSAVER
        TifNoSignalStyle.ARIB_COLOR_BARS,
        TifNoSignalStyle.MINIMAL_CLOCK -> NoSignalRenderMode.EFFECT
        TifNoSignalStyle.DVD_BOUNCE,
        TifNoSignalStyle.HONOREBI_BOUNCE -> {
            if (hardwareAccelerated && animationsEnabled && !degraded) {
                NoSignalRenderMode.BOUNCE
            } else {
                NoSignalRenderMode.BANNER
            }
        }
        TifNoSignalStyle.ANALOG_STATIC,
        TifNoSignalStyle.SIGNAL_WAVE,
        TifNoSignalStyle.AURORA_AMBIENT,
        TifNoSignalStyle.DOT_MATRIX,
        TifNoSignalStyle.STARFIELD,
        TifNoSignalStyle.SIGNAL_RADAR -> {
            if (hardwareAccelerated && animationsEnabled && !degraded) {
                NoSignalRenderMode.EFFECT
            } else {
                NoSignalRenderMode.BANNER
            }
        }
    }
}

internal fun areSystemAnimatorsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
