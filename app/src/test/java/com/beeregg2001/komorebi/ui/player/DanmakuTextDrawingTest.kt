package com.beeregg2001.komorebi.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class DanmakuTextDrawingTest {
    @Test
    fun longMultilineTextIsMeasuredAndDrawnBeyondOldAtlasLimits() {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 80f
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val fill = Paint(stroke).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val text = "W".repeat(50) + "\nsecond line\nthird line"
        val (width, height) = measureDanmakuText(text, stroke)
        assertTrue(width > 2048f)
        assertTrue(height > 160f)
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val output = Canvas(bitmap)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            val tileHeight = minOf(TILE_HEIGHT, bitmap.height - y)
            while (x < bitmap.width) {
                val tileWidth = minOf(TILE_WIDTH, bitmap.width - x)
                val tile = Bitmap.createBitmap(tileWidth + 2, TILE_HEIGHT + 2, Bitmap.Config.ARGB_8888)
                drawDanmakuTile(Canvas(tile), text, x, y, stroke, fill)
                output.drawBitmap(tile, Rect(1, 1, tileWidth + 1, tileHeight + 1), Rect(x, y, x + tileWidth, y + tileHeight), null)
                x += tileWidth
            }
            y += tileHeight
        }
        fun hasPixels(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            for (y in top until bottom) for (x in left until right) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) return true
            }
            return false
        }
        assertTrue(hasPixels(2048, 0, bitmap.width, bitmap.height))
        assertTrue(hasPixels(0, 160, bitmap.width, bitmap.height))
        val reference = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        drawDanmakuText(Canvas(reference), text, 0f, 0f, stroke, fill)
        assertTrue("A tile seam changed the text pixels", bitmap.sameAs(reference))
    }
}
