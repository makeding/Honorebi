@file:androidx.annotation.OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.Renderer
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.HlgToSdrColorLut
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener

open class LibaribtlvToneMappingRenderersFactory(
    context: Context,
    private val colorLut: HlgToSdrColorLut?
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val firstNewRenderer = out.size
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
        val lut = colorLut ?: return
        val rendererIndex = (firstNewRenderer until out.size)
            .first { out[it]::class.java == MediaCodecVideoRenderer::class.java }
        out[rendererIndex] = LibaribtlvToneMappingVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            lut
        )
    }
}

private class LibaribtlvToneMappingVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val colorLut: HlgToSdrColorLut
) : MediaCodecVideoRenderer(builder) {

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        val videoGraphFactory: VideoGraph.Factory = SingleInputVideoGraph.Factory(
            DefaultVideoFrameProcessor.Factory.Builder()
                .setHlgToSdrColorLut(colorLut)
                .build()
        )
        return PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setVideoGraphFactory(videoGraphFactory)
            .setEnablePlaylistMode(true)
            .setClock(clock)
            .build()
            .apply { setRequestOpenGlToneMapping(true) }
    }
}
