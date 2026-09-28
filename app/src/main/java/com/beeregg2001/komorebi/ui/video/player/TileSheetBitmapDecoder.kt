package com.beeregg2001.komorebi.ui.video.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * タイルシート (サムネイルを格子状に並べた巨大画像) をメモリ上限内に収めてデコードする共通ポリシー。
 *
 * シートは録画長に応じて非常に大きくなる (例: 16320x4590)。RGB_565 でも等倍デコードすると
 * Canvas が描画できる上限 (RecordingCanvas: 100MB) を超え、drawImage 時に
 * "Canvas: trying to draw too large bitmap" でアプリごと落ちる。
 * そこで先に画像サイズだけ読み、上限に収まる 2 のべき乗の縮小率で等比縮小し、
 * RGB_565 でデコードする。サムネイル用途では色深度は十分。
 */
internal object TileSheetBitmapDecoder {
    /** RGB_565 のバイト数/px */
    const val BYTES_PER_PIXEL_RGB_565 = 2L

    /**
     * デコード後のシートの上限バイト数。Canvas の描画上限 (100MB) に余裕を持たせ、
     * メモリの少ない 32bit TV (3GB クラス) でも保持できる大きさに抑える。
     */
    const val MAX_BITMAP_BYTES = 64L * 1024L * 1024L

    data class Decoded(val bitmap: Bitmap, val sampleSize: Int)

    /** 上限バイト数に収まる最小の (2 のべき乗の) inSampleSize を返す。 */
    fun sampleSizeFor(
        width: Int,
        height: Int,
        maxBytes: Long = MAX_BITMAP_BYTES,
    ): Int {
        if (width <= 0 || height <= 0 || maxBytes <= 0L) return 1
        var sampleSize = 1
        while (
            width.toLong() * height * BYTES_PER_PIXEL_RGB_565 / (sampleSize.toLong() * sampleSize) >
            maxBytes
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** ファイルを等比縮小 + RGB_565 でデコードする。デコード失敗時は null。 */
    fun decode(
        file: File,
        maxBytes: Long = MAX_BITMAP_BYTES,
    ): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxBytes)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return Decoded(bitmap, sampleSize)
    }
}
