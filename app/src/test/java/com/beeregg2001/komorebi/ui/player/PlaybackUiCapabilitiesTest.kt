package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.beeregg2001.komorebi.ui.video.smb.SmbPlaybackMetadata

class PlaybackUiCapabilitiesTest {
    @Test
    fun smbKeepsLocalPlaybackControlsAndDisablesProviderOnlyActions() {
        val capabilities = PlaybackUiCapabilities.Smb

        assertTrue(capabilities.programInfo)
        assertTrue(capabilities.audio)
        assertTrue(capabilities.speed)
        assertTrue(capabilities.subtitles)
        assertTrue(capabilities.crop)
        assertFalse(capabilities.quickSelection)
        assertFalse(capabilities.thumbnailGrid)
        assertFalse(capabilities.chapters)
        assertFalse(capabilities.quality)
        assertFalse(capabilities.comments)
        assertFalse(capabilities.cmSkip)
        assertFalse(capabilities.hdr)
        assertFalse(capabilities.dataBroadcasting)
    }

    @Test
    fun smbProgramPresentationShowsOnlyLocalFacts() {
        val presentation = PlayerProgramPresentation.smb(
            SmbPlaybackMetadata(
                stableId = "smb:/video/local.ts",
                title = "local.ts",
                location = "/video/local.ts",
                thumbnailUrl = null,
                sizeBytes = 1_024L,
                lastModifiedMs = 0L,
            ),
            "24h",
        )

        assertEquals("ローカルファイル", presentation.channelName)
        assertEquals("local.ts", presentation.title)
        assertEquals(listOf("場所", "サイズ", "更新日時"), presentation.metadata.map { it.first })
        assertFalse(presentation.metadata.any { it.first.contains("放送") || it.first.contains("EPG") })
    }
}
