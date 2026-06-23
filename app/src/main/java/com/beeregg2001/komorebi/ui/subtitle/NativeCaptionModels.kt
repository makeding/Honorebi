package com.beeregg2001.komorebi.ui.subtitle

import android.util.Base64
import org.json.JSONObject

data class NativeCaptionCue(
    val ptsMs: Long,
    val durationMs: Long,
    val clearScreen: Boolean,
    val planeWidth: Int,
    val planeHeight: Int,
    val images: List<NativeCaptionImage>
) {
    companion object {
        fun fromJson(json: String): NativeCaptionCue {
            val root = JSONObject(json)
            val imagesJson = root.optJSONArray("images")
            val images = buildList {
                if (imagesJson != null) {
                    for (i in 0 until imagesJson.length()) {
                        val imageJson = imagesJson.getJSONObject(i)
                        add(
                            NativeCaptionImage(
                                x = imageJson.optInt("x"),
                                y = imageJson.optInt("y"),
                                width = imageJson.optInt("width"),
                                height = imageJson.optInt("height"),
                                stride = imageJson.optInt("stride"),
                                rgba = Base64.decode(imageJson.optString("rgba"), Base64.DEFAULT)
                            )
                        )
                    }
                }
            }
            return NativeCaptionCue(
                ptsMs = root.optLong("ptsMs"),
                durationMs = root.optLong("durationMs"),
                clearScreen = root.optBoolean("clearScreen"),
                planeWidth = root.optInt("planeWidth", 1920).takeIf { it > 0 } ?: 1920,
                planeHeight = root.optInt("planeHeight", 1080).takeIf { it > 0 } ?: 1080,
                images = images
            )
        }
    }
}

data class NativeCaptionImage(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val stride: Int,
    val rgba: ByteArray
)
