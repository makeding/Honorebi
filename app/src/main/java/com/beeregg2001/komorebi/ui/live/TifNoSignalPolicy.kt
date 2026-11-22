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
}

internal const val TIF_NO_SIGNAL_MIN_WIDTH_DP = 480f

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
        TifNoSignalStyle.DVD_BOUNCE,
        TifNoSignalStyle.HONOREBI_BOUNCE -> {
            if (hardwareAccelerated && animationsEnabled && !degraded) {
                NoSignalRenderMode.BOUNCE
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
