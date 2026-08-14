package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap

data class NativeCaptionCue(
    val ptsMs: Long,
    val durationMs: Long,
    val clearScreen: Boolean,
    val planeWidth: Int,
    val planeHeight: Int,
    val images: List<NativeCaptionImage>,
    val timelineCommand: Int = TIMELINE_COMMAND_NONE,
    val type: Int = TYPE_CAPTION
) {
    companion object {
        const val TYPE_CAPTION = 0
        const val TYPE_SUPERIMPOSE = 1

        const val TIMELINE_COMMAND_NONE = 0
        const val TIMELINE_COMMAND_RESET = 1
        const val TIMELINE_COMMAND_REPLACE_FROM = 2
    }
}

data class NativeCaptionImage(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val bitmap: Bitmap,
    val regions: List<NativeCaptionRegion> = emptyList()
)

data class NativeCaptionRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class NativeCaptionLanguage(
    val id: Int,
    val iso6392Code: String
) {
    val displayName: String
        get() = when (iso6392Code) {
            "jpn" -> "日本語"
            "eng" -> "英語"
            "por" -> "ポルトガル語"
            "spa" -> "スペイン語"
            "tgl" -> "タガログ語"
            else -> iso6392Code.ifBlank { "第${id}言語" }
        }
}
