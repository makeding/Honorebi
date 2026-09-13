package com.beeregg2001.komorebi.ui.player.live

import androidx.media3.exoplayer.DefaultRenderersFactory
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the configuration formerly held by LivePlayerFactory. */
class LivePlayerProfileTest {
    @Test
    fun savedSdrPreferenceDoesNotEnableToneMappingFor2k() {
        assertFalse(livePlayerProfile("STEREO", HdrToneMapping.RENDER_MODE_SDR).enableHdrToSdrToneMapping)
    }

    @Test
    fun preservesLiveBufferRendererAndTeardownConfiguration() {
        val profile = livePlayerProfile("STEREO", HdrToneMapping.RENDER_MODE_ORIGINAL)

        assertEquals(3_000, profile.buffer.minBufferMs)
        assertEquals(10_000, profile.buffer.maxBufferMs)
        assertEquals(1_000, profile.buffer.bufferForPlaybackMs)
        assertEquals(1_500, profile.buffer.bufferForPlaybackAfterRebufferMs)
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, profile.extensionRendererMode)
        assertEquals(10_000L, profile.releaseTimeoutMs)
        assertEquals(10_000L, profile.detachSurfaceTimeoutMs)
        assertEquals("STEREO", profile.audioOutputMode)
        assertFalse(profile.enableHdrToSdrToneMapping)
    }

    @Test
    fun keepsPassthroughAndHdrToneMappingAsIndependentChoices() {
        val profile = livePlayerProfile("PASSTHROUGH", HdrToneMapping.RENDER_MODE_SDR, isUhdChannel = true)

        assertEquals("PASSTHROUGH", profile.audioOutputMode)
        assertEquals(HdrToneMapping.isSupported, profile.enableHdrToSdrToneMapping)
        assertTrue(profile.configureAudioAttributes)
        assertTrue(profile.handleAudioFocus)
    }
}
