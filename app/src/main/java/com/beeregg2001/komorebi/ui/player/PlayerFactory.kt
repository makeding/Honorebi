@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource

/** Construction differences are data; every playback slot uses the same factory. */
data class PlayerBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int? = null,
)

data class PlayerProfile(
    val buffer: PlayerBufferProfile,
    val extensionRendererMode: Int = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
    val enableHdrToSdrToneMapping: Boolean = false,
    /** null keeps Media3's default sink; explicit output modes retain broadcast downmix. */
    val audioOutputMode: String? = null,
    val releaseTimeoutMs: Long? = null,
    val detachSurfaceTimeoutMs: Long? = null,
    val handleAudioFocus: Boolean = true,
    val configureAudioAttributes: Boolean = true,
)

internal object PlayerFactory {
    fun create(context: Context, profile: PlayerProfile, mediaSourceFactory: MediaSource.Factory?): ExoPlayer {
        val toneMapping = profile.enableHdrToSdrToneMapping
        val renderers = if (profile.audioOutputMode == null) {
            LibaribtlvToneMappingRenderersFactory(context, if (toneMapping) HdrToneMapping.colorLut else null)
        } else {
            object : LibaribtlvToneMappingRenderersFactory(context, if (toneMapping) HdrToneMapping.colorLut else null) {
                override fun buildAudioSink(ctx: Context, enableFloat: Boolean, enableParams: Boolean): DefaultAudioSink {
                    val processors = if (profile.audioOutputMode == "PASSTHROUGH") emptyArray<AudioProcessor>() else {
                        arrayOf<AudioProcessor>(ChannelMixingAudioProcessor().apply {
                            putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
                            putChannelMixingMatrix(ChannelMixingMatrix(6, 2, floatArrayOf(
                                1f, 0f, 0.707f, 0f, 0.707f, 0f,
                                0f, 1f, 0.707f, 0f, 0f, 0.707f,
                            )))
                        })
                    }
                    return DefaultAudioSink.Builder(ctx).setAudioProcessors(processors).build()
                }
            }
        }
        renderers.setExtensionRendererMode(profile.extensionRendererMode).setEnableDecoderFallback(true)
        val buffer = profile.buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer.minBufferMs, buffer.maxBufferMs, buffer.bufferForPlaybackMs, buffer.bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .apply { buffer.targetBufferBytes?.let { setTargetBufferBytes(it) } }
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMinPlaybackSpeed(1f).setFallbackMaxPlaybackSpeed(1f).build())
            .apply {
                mediaSourceFactory?.let { setMediaSourceFactory(it) }
                profile.releaseTimeoutMs?.let { setReleaseTimeoutMs(it) }
                profile.detachSurfaceTimeoutMs?.let { setDetachSurfaceTimeoutMs(it) }
            }
            .build().apply {
                if (toneMapping) setVideoEffects(emptyList())
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                if (profile.configureAudioAttributes) {
                    setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(), profile.handleAudioFocus)
                }
            }
    }
}
