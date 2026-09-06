package com.beeregg2001.komorebi.ui.player

import androidx.media3.common.C
import androidx.media3.common.Player
import com.beeregg2001.komorebi.ui.player.live.livePlayerProfile
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedPlayerBufferProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerFactoryTest {
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
