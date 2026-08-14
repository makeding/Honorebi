package com.beeregg2001.komorebi.util.mmts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun preservesTheAudioGroupWhenSwitchingVideoLayers() {
        val selection = selectTlvLayer(
            tracks = sampleTracks(),
            requestedVideoPacketId = 0xf301,
            selectedAudioPacketId = 0xf311
        )

        assertEquals(TlvLayerSelection(0xf301, 0xf315), selection)
    }

    @Test
    fun selectsMainOrSubAudioWithinTheActiveVideoLayer() {
        assertEquals(
            TlvLayerSelection(0xf301, 0xf314),
            selectTlvLayer(sampleTracks(), 0xf301, 0xf315, preferredMainAudio = true)
        )
        assertEquals(
            TlvLayerSelection(0xf301, 0xf315),
            selectTlvLayer(sampleTracks(), 0xf301, 0xf314, preferredMainAudio = false)
        )
    }

    @Test
    fun rejectsLayerSwitchWhenTheRequestedAudioRoleIsUnavailable() {
        val tracksWithoutSubAudio = sampleTracks().filter { it.audioMainComponent || it.kind == TlvTrackKind.VIDEO }

        assertEquals(
            null,
            selectTlvLayer(
                tracksWithoutSubAudio,
                0xf301,
                0xf310,
                preferredMainAudio = false
            )
        )
    }

    @Test
    fun resolvesThePrimaryVideoFromSelectionLevelZero() {
        assertEquals(
            TlvLayerSelection(0xf300, 0xf310),
            selectTlvLayer(sampleTracks(), null, null)
        )
    }

    private fun sampleTracks(): List<TlvTrackInfo> = listOf(
        track(0xf300, TlvTrackKind.VIDEO, 0x00, 0),
        track(0xf301, TlvTrackKind.VIDEO, 0x00, 1),
        track(0xf310, TlvTrackKind.AUDIO, 0x10, 0, main = true),
        track(0xf311, TlvTrackKind.AUDIO, 0x11, 0),
        track(0xf314, TlvTrackKind.AUDIO, 0x10, 1, main = true),
        track(0xf315, TlvTrackKind.AUDIO, 0x11, 1)
    )

    private fun track(
        packetId: Int,
        kind: TlvTrackKind,
        groupIdentification: Int,
        selectionLevel: Int,
        main: Boolean = false
    ) = TlvTrackInfo(
        packetId = packetId,
        kind = kind,
        assetGroups = listOf(TlvAssetGroup(groupIdentification, selectionLevel)),
        audioMainComponent = main
    )
}
