package com.beeregg2001.komorebi.ui.video.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TileSheetBitmapDecoderTest {

    @Test
    fun smallSheetDecodesAtFullResolution() {
        // 320x180 RGB_565 = 115,200 bytes は上限 (64MB) 未満なので等倍
        assertEquals(1, TileSheetBitmapDecoder.sampleSizeFor(320, 180))
    }

    @Test
    fun largeRecordedSheetIsDownscaledToFitBudget() {
        // 16320x4590 RGB_565 = 149,817,600 bytes (> 64MB) は 2 のべき乗で 1/2 に縮小
        assertEquals(2, TileSheetBitmapDecoder.sampleSizeFor(16320, 4590))
    }

    @Test
    fun smallerBudgetForcesLargerSampleSize() {
        // 同じシートを 10MB 予算に収めるには 1/4 (37.4MB -> 9.36MB)
        assertEquals(4, TileSheetBitmapDecoder.sampleSizeFor(16320, 4590, maxBytes = 10L * 1024 * 1024))
    }

    @Test
    fun invalidDimensionsFallBackToFullResolution() {
        assertEquals(1, TileSheetBitmapDecoder.sampleSizeFor(0, 100))
        assertEquals(1, TileSheetBitmapDecoder.sampleSizeFor(100, -1))
    }

    @Test
    fun nonPositiveBudgetFallsBackToFullResolution() {
        assertEquals(1, TileSheetBitmapDecoder.sampleSizeFor(100, 100, maxBytes = 0L))
    }

    @Test
    fun decodedSizeStaysWithinBudget() {
        val width = 16320
        val height = 4590
        val sample = TileSheetBitmapDecoder.sampleSizeFor(width, height)
        val decodedBytes =
            (width / sample).toLong() * (height / sample) * TileSheetBitmapDecoder.BYTES_PER_PIXEL_RGB_565
        assert(decodedBytes <= TileSheetBitmapDecoder.MAX_BITMAP_BYTES) {
            "decodedBytes=$decodedBytes exceeds budget"
        }
    }
}
