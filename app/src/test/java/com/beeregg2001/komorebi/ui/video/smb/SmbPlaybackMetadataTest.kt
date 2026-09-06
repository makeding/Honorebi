package com.beeregg2001.komorebi.ui.video.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbPlaybackMetadataTest {
    @Test fun `file metadata preserves only local file presentation fields`() {
        val item = SmbItem(
            name = "episode.ts",
            path = "smb://nas/media/episode.ts",
            isDirectory = false,
            size = 1234L,
            lastModified = 5678L,
        )

        val metadata = SmbPlaybackMetadata.from(item)

        assertEquals("smb:smb://nas/media/episode.ts", metadata.stableId)
        assertEquals("episode.ts", metadata.title)
        assertEquals(item.path, metadata.location)
        assertEquals(1234L, metadata.sizeBytes)
        assertEquals(5678L, metadata.lastModifiedMs)
        assertNull(metadata.thumbnailUrl)
    }
}
