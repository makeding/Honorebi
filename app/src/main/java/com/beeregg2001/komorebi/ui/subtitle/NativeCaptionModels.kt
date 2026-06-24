package com.beeregg2001.komorebi.ui.subtitle

data class NativeCaptionCue(
    val ptsMs: Long,
    val durationMs: Long,
    val clearScreen: Boolean,
    val planeWidth: Int,
    val planeHeight: Int,
    val images: List<NativeCaptionImage>
)

data class NativeCaptionImage(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val stride: Int,
    val rgba: ByteArray
)
