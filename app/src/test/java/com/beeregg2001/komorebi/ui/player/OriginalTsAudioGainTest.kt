@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class OriginalTsAudioGainTest {
    private fun amplify(samples: ShortArray, channels: Int): ShortArray {
        val processor = originalTsAudioGainProcessor()
        processor.configure(AudioProcessor.AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val result = ShortArray(output.remaining() / 2) { output.short }
        processor.reset()
        return result
    }

    @Test fun stereoDoublesAndSaturatesWithoutWrappingOrChangingLength() {
        val input = shortArrayOf(1000, -1000, 20000, -20000, 0, 32767)
        assertTrue(shortArrayOf(2000, -2000, 32767, -32768, 0, 32767)
            .contentEquals(amplify(input, 2)))
    }

    @Test fun surroundUsesThreeStepsAndPreservesChannelOrder() {
        val output = amplify(shortArrayOf(1000, 0, -1000, 2000, 0, -2000), 6)
        val expected = intArrayOf(1681, 0, -1681, 3363, 0, -3363)
        expected.indices.forEach { assertEquals(expected[it].toDouble(), output[it].toDouble(), 1.0) }
    }

    @Test fun realRecordingPcmKeepsEverySampleAndDoublesBelowClipping() {
        val path = System.getenv("KOMOREBI_GAIN_PCM")
        assumeTrue("Set KOMOREBI_GAIN_PCM to decoded stereo PCM", path != null)
        val bytes = File(path!!).readBytes()
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(bytes.size / 2) { input.short }
        val output = amplify(samples, 2)
        assertEquals(samples.size, output.size)
        assertTrue(samples.any { it != 0.toShort() })
        samples.indices.forEach {
            assertEquals((samples[it].toInt() * 2).coerceIn(-32768, 32767), output[it].toInt())
        }
        println("REAL_TS_GAIN samples=${samples.size} duration_s=${samples.size / 96000.0} gain=2.0")
    }
}
