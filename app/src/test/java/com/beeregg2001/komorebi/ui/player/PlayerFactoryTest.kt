@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.beeregg2001.komorebi.ui.player.live.livePlayerProfile
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedPlayerBufferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerFactoryTest {
    @Test fun originalTsRendererCanCatchUpWithoutDroppingTimelyOrTerminalFrames() {
        val runtime = PlayerRuntime(RuntimeEnvironment.getApplication(), PlayerProfile(
            buffer = PlayerBufferProfile(5_000, 15_000, 500, 1_000),
            recoverLateVideoFrames = true,
        ))
        try {
            val renderer = hardwareVideoRenderer(runtime)
            assertFalse(renderer.shouldDropFrame(10_000, 0, false))
            assertFalse(renderer.shouldDropFrame(-30_000, 0, false))
            assertTrue(renderer.shouldDropFrame(-30_001, 0, false))
            assertTrue(renderer.shouldDropFrame(-2_000_000, 0, false))
            assertFalse(renderer.shouldDropFrame(-2_000_000, 0, true))
            assertFalse(shouldDropToKeyframe(renderer, -500_000, false))
            assertTrue(shouldDropToKeyframe(renderer, -500_001, false))
            assertFalse(shouldDropToKeyframe(renderer, -2_000_000, true))
        } finally {
            runtime.release()
        }
    }

    @Test fun otherPlaybackProfilesKeepTheirExistingBroadcastRenderer() {
        val runtime = PlayerRuntime(RuntimeEnvironment.getApplication(),
            livePlayerProfile("STEREO", HdrToneMapping.RENDER_MODE_ORIGINAL))
        try {
            val renderer = hardwareVideoRenderer(runtime)
            assertFalse(renderer.shouldDropFrame(-2_000_000, 0, false))
            assertFalse(shouldDropToKeyframe(renderer, -2_000_000, false))
        } finally {
            runtime.release()
        }
    }

    private fun shouldDropToKeyframe(renderer: MediaCodecVideoRenderer, earlyUs: Long, last: Boolean): Boolean =
        MediaCodecVideoRenderer::class.java.getDeclaredMethod("shouldDropBuffersToKeyframe",
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, earlyUs, 0L, last) as Boolean

    private fun hardwareVideoRenderer(runtime: PlayerRuntime): MediaCodecVideoRenderer =
        (0 until runtime.player.rendererCount).map(runtime.player::getRenderer)
            .filterIsInstance<MediaCodecVideoRenderer>().single()

    @Test fun allSourceProfilesConstructThroughTheSameRuntime() {
        val recordedProfiles = listOf(
            RecordedPlayerBufferProfile.Recorded,
            RecordedPlayerBufferProfile.RawMmts,
            RecordedPlayerBufferProfile.Chase,
        ).map { buffer ->
            PlayerProfile(PlayerBufferProfile(
                buffer.minBufferMs, buffer.maxBufferMs, buffer.bufferForPlaybackMs,
                buffer.bufferForPlaybackAfterRebufferMs, buffer.targetBufferBytes,
            ))
        }
        val profiles = listOf(
            livePlayerProfile("PASSTHROUGH", HdrToneMapping.RENDER_MODE_ORIGINAL),
            livePlayerProfile("STEREO", HdrToneMapping.RENDER_MODE_ORIGINAL),
        ) + recordedProfiles
        for (profile in profiles) {
            val runtime = PlayerRuntime(RuntimeEnvironment.getApplication(), profile)
            try {
                assertEquals(Player.STATE_IDLE, runtime.player.playbackState)
                assertEquals(C.USAGE_MEDIA, runtime.player.audioAttributes.usage)
                assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, runtime.player.audioAttributes.contentType)
                assertEquals(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF, runtime.player.videoChangeFrameRateStrategy)
                runtime.pause()
            } finally {
                runtime.release()
            }
        }
    }
}
