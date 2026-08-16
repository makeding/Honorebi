@file:androidx.annotation.OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.graphics.Bitmap
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HlgToSdrColorLut
import com.beeregg2001.komorebi.NativeLib
import java.io.IOException

class HdrToneMappingRejectedException(
    detail: String,
    cause: Throwable? = null
) : IOException("$ERROR_CODE: $detail", cause)

internal data class HlgSdrLutLayout(
    val size: Int,
    val width: Int,
    val height: Int
)

internal fun parseHlgSdrLutLayout(data: IntArray): HlgSdrLutLayout {
    require(data.size >= LUT_HEADER_SIZE) { "missing LUT header" }
    val layout = HlgSdrLutLayout(data[0], data[1], data[2])
    require(layout.size >= 2) { "invalid LUT size" }
    require(layout.width % layout.size == 0 && layout.height % layout.size == 0) {
        "invalid LUT texture dimensions"
    }
    require((layout.width / layout.size) * (layout.height / layout.size) >= layout.size) {
        "not enough LUT slices"
    }
    require(data.size == LUT_HEADER_SIZE + layout.width * layout.height) {
        "invalid LUT pixel count"
    }
    return layout
}

object HdrToneMapping {
    const val RENDER_MODE_ORIGINAL = "ORIGINAL"
    const val RENDER_MODE_SDR = "SDR_TONE_MAP"

    val isSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val colorLut: HlgToSdrColorLut by lazy {
        try {
            val data = NativeLib().getHlgSdrPrototypeColorLut()
            val layout = parseHlgSdrLutLayout(data)
            val bitmap = Bitmap.createBitmap(
                data,
                LUT_HEADER_SIZE,
                layout.width,
                layout.width,
                layout.height,
                Bitmap.Config.ARGB_8888
            )
            HlgToSdrColorLut(bitmap, layout.size)
        } catch (error: Throwable) {
            throw HdrToneMappingRejectedException(
                "libaribtlv BT.2446 LUT could not be created",
                error
            )
        }
    }

    fun rejectionCause(error: Throwable): Throwable? {
        val pending = ArrayDeque<Throwable>()
        pending.add(error)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (
                current is HdrToneMappingRejectedException ||
                current.message?.contains(ERROR_CODE) == true
            ) {
                return current
            }
            current.cause?.let(pending::add)
        }
        return null
    }
}

internal const val ERROR_CODE = "HLG_TO_SDR_LUT_UNSUPPORTED"
private const val LUT_HEADER_SIZE = 3
