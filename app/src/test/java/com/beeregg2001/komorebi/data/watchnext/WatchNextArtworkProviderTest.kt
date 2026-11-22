package com.beeregg2001.komorebi.data.watchnext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNextArtworkProviderTest {

    @Test
    fun `parseArtworkId accepts valid rec positive integer segments`() {
        assertEquals(12, WatchNextArtworkProvider.parseArtworkId(listOf("rec", "12")))
        assertEquals(1, WatchNextArtworkProvider.parseArtworkId(listOf("rec", "1")))
        assertEquals(999999, WatchNextArtworkProvider.parseArtworkId(listOf("rec", "999999")))
    }

    @Test
    fun `parseArtworkId rejects path traversal, non-numeric, non-positive, wrong prefix, and extra segments`() {
        assertNull(WatchNextArtworkProvider.parseArtworkId(emptyList()))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "12", "extra")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "..")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "../12")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("..", "12")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "0")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "-1")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "+12")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "abc")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("rec", "12.img")))
        assertNull(WatchNextArtworkProvider.parseArtworkId(listOf("live", "12")))
    }

    @Test
    fun `parsePreviewArtwork accepts valid preview rec and live segments`() {
        assertEquals(
            PreviewArtworkKind.RECORDING to "42",
            WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "rec", "42")),
        )
        val sha1 = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            PreviewArtworkKind.LIVE to sha1,
            WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", sha1)),
        )
        assertEquals(
            PreviewArtworkKind.LIVE to "abc123",
            WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "abc123")),
        )
    }

    @Test
    fun `parsePreviewArtwork rejects invalid segments, path traversal, uppercase, and overlong keys`() {
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(emptyList()))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "rec")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "rec", "42", "extra")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("rec", "42")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("other", "rec", "42")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "unknown", "42")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "rec", "")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "rec", "..")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "../42")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "42.img")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "ABC123")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "a-b")))
        assertNull(WatchNextArtworkProvider.parsePreviewArtwork(listOf("preview", "live", "a".repeat(41))))
    }
}
