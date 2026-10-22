package com.beeregg2001.komorebi.ui.subtitle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beeregg2001.komorebi.NativeLib
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the production decoder, JNI bitmap copy and Android Canvas on a device. */
@RunWith(AndroidJUnit4::class)
class NativeCaptionRenderingDeviceTest {
    private val native = NativeLib()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withDecoder(type: Int = NativeCaptionCue.TYPE_CAPTION, block: (Long) -> Unit) {
        val font = SubtitleFontFiles.installFont(context, SubtitleFontFiles.DEFAULT_ID)
        val handle = native.openCaptionDecoder(type, font)
        assertNotEquals(0L, handle)
        try { block(handle) } finally { native.closeCaptionDecoder(handle) }
    }

    private fun document(body: String, layout: String = "") = """
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:tts="http://www.w3.org/ns/ttml#styling"
            xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
            xmlns:smpte="http://www.smpte-ra.org/schemas/2052-1/2013/smpte-tt"
            ttp:extent="1920px 1080px">
          <head><layout>
            <region xml:id="image" tts:origin="20px 20px" tts:extent="30px 10px"/>
            <region xml:id="text" tts:origin="40px 60px" tts:extent="400px 80px"/>
            $layout
          </layout></head><body><div>$body</div></body>
        </tt>
    """.trimIndent().toByteArray()

    private fun png(): ByteArray {
        val source = Bitmap.createBitmap(intArrayOf(
            Color.argb(128, 32, 96, 160), Color.rgb(20, 60, 100), Color.TRANSPARENT
        ), 3, 1, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { stream ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { source.recycle() }
    }

    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="30" height="10">
        <rect width="30" height="10" fill="#4080c0" fill-opacity="0.5"/></svg>""".toByteArray()

    private fun decode(handle: Long, xml: ByteArray, resources: Array<ByteArray> = arrayOf(png(), svg)) =
        native.decodeB62Captions(
            handle, xml, 0L, 1, 3, Long.MIN_VALUE, 1L,
            intArrayOf(10, 11), intArrayOf(1, 2), resources, false
        ).toList()

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        for (shift in intArrayOf(0, 8, 16, 24)) {
            assertTrue("expected=%08x actual=%08x".format(expected, actual),
                abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= tolerance)
        }
    }

    private fun assertCanvasPixel(bitmap: Bitmap, x: Int, foreground: Int) {
        assertTrue(bitmap.isPremultiplied)
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.rgb(20, 40, 60))
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            val alpha = Color.alpha(foreground)
            fun over(value: Int, background: Int) = (value * alpha + background * (255 - alpha) + 127) / 255
            assertColor(Color.rgb(over(Color.red(foreground), 20),
                over(Color.green(foreground), 40), over(Color.blue(foreground), 60)), output.getPixel(x, 5))
        } finally { output.recycle() }
    }

    @Test fun imageOnlyPngAndSvgKeepTimingAlphaAndClearGaps() {
        for (type in listOf(NativeCaptionCue.TYPE_CAPTION, NativeCaptionCue.TYPE_SUPERIMPOSE)) {
            withDecoder(type) { handle ->
                val cues = decode(handle, document("""
                    <div region="image" begin="0s" end="1s" smpte:backgroundImage="subt://10"/>
                    <div region="image" begin="2s" end="3s" smpte:backgroundImage="subt://11"/>
                """))
                assertEquals(listOf(0L, 1000L, 2000L, 3000L), cues.map { it.ptsMs })
                assertTrue(cues.all { it.type == type })
                assertTrue(cues[1].clearScreen && cues[1].images.isEmpty())
                assertTrue(cues[3].clearScreen && cues[3].images.isEmpty())
                assertEquals(1000L, cues[0].durationMs)
                assertEquals(1000L, cues[2].durationMs)
                val image = cues[0].images.single()
                assertFalse(cues[0].clearScreen)
                assertEquals(listOf(20, 20, 30, 10), listOf(image.x, image.y, image.width, image.height))
                assertTrue(image.regions.isEmpty())
                assertColor(Color.argb(128, 32, 96, 160), image.bitmap.getPixel(5, 5))
                assertCanvasPixel(image.bitmap, 5, Color.argb(128, 32, 96, 160))
                assertCanvasPixel(image.bitmap, 15, Color.rgb(20, 60, 100))
                assertCanvasPixel(image.bitmap, 25, Color.TRANSPARENT)
                val raw = ByteBuffer.allocate(image.bitmap.byteCount)
                image.bitmap.copyPixelsToBuffer(raw)
                assertEquals(listOf(16, 48, 80, 128), raw.array().take(4).map { it.toInt() and 255 })
                val svgImage = cues[2].images.single()
                assertColor(Color.argb(128, 64, 128, 192), svgImage.bitmap.getPixel(5, 5))
                assertCanvasPixel(svgImage.bitmap, 5, Color.argb(128, 64, 128, 192))
            }
        }
    }

    @Test fun fullFrameBackgroundAndTextKeepSeparateAvoidanceBounds() = withDecoder { handle ->
        val cues = decode(handle, document("""
            <div region="full" begin="0s" end="1s" smpte:backgroundImage="subt://11"/>
            <p region="text" begin="0s" end="1s" tts:fontSize="36px">字幕</p>
        """, """<region xml:id="full" tts:origin="0px 0px" tts:extent="1920px 1080px"/>"""))
        val images = cues.first().images
        assertEquals(2, images.size)
        assertEquals(1920, images[0].width)
        assertEquals(1080, images[0].height)
        assertTrue(images[0].regions.isEmpty())
        assertTrue(images[1].regions.isNotEmpty())
        assertCanvasPixel(images[0].bitmap, 5, Color.argb(128, 64, 128, 192))
        assertTrue(cues.last().clearScreen)
    }

    @Test fun tinyTextDoesNotStealBackgroundGeometryAndFlushDropsOldResources() = withDecoder { handle ->
        val xml = document("""
            <div region="image" begin="0s" end="1s" smpte:backgroundImage="subt://10"/>
            <p region="tiny" begin="0s" end="1s" tts:fontSize="1px">A</p>
        """, """<region xml:id="tiny" tts:origin="80px 80px" tts:extent="1px 1px"/>""")
        val first = decode(handle, xml).first()
        assertTrue(first.images.isNotEmpty())
        assertTrue(first.images[0].regions.isEmpty())
        native.flushCaptionDecoder(handle)
        val afterFlush = native.decodeB62Captions(handle,
            document("""<div region="image" begin="0s" end="1s" smpte:backgroundImage="subt://10"/>"""),
            0L, 1, 3, Long.MIN_VALUE, 2L, intArrayOf(), intArrayOf(), emptyArray(), false)
        assertTrue(afterFlush.all { it.images.isEmpty() })
        assertTrue(decode(handle, document("""
            <div region="image" begin="0s" end="1s" smpte:backgroundImage="subt://10"/>
        """)).first().images.isNotEmpty())
    }
}
