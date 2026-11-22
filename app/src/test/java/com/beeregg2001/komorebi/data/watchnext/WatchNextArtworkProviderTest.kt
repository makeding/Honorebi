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
}
