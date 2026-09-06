package com.beeregg2001.komorebi.ui.player.live

import androidx.media3.exoplayer.DefaultRenderersFactory
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.player.PlayerBufferProfile
import com.beeregg2001.komorebi.ui.player.PlayerProfile

/** Live slots share one profile; main and dual only differ by their source/session. */
fun livePlayerProfile(audioOutputMode: String, hdrRenderMode: String): PlayerProfile =
    PlayerProfile(
        buffer = PlayerBufferProfile(
            minBufferMs = 3_000,
            maxBufferMs = 10_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 1_500,
        ),
        extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
        enableHdrToSdrToneMapping =
            hdrRenderMode == HdrToneMapping.RENDER_MODE_SDR && HdrToneMapping.isSupported,
        audioOutputMode = audioOutputMode,
        releaseTimeoutMs = 10_000,
        detachSurfaceTimeoutMs = 10_000,
    )
