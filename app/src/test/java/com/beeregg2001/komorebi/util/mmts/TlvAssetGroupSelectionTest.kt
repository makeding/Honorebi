package com.beeregg2001.komorebi.util.mmts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlvAssetGroupSelectionTest {
    @Test
    fun acceptsLegacyTracksWithoutAssetGroupMetadata() {
        assertTrue(isAudioLayerCompatible(emptySet(), intArrayOf(1)))
        assertTrue(isAudioLayerCompatible(setOf(1), intArrayOf()))
    }

    @Test
    fun acceptsAudioFromTheSelectedVideoLayer() {
        assertTrue(isAudioLayerCompatible(setOf(1), intArrayOf(0, 1)))
    }

    @Test
    fun rejectsAudioFromAnotherVideoLayer() {
        assertFalse(isAudioLayerCompatible(setOf(1), intArrayOf(0)))
    }
}
