@file:androidx.annotation.OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.VideoGraph
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.HlgToSdrColorLut
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.nio.ByteBuffer

open class LibaribtlvToneMappingRenderersFactory(
    context: Context,
    private val colorLut: HlgToSdrColorLut?,
    private val recoverLateVideoFrames: Boolean = false,
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
        val mode = toneMappingMode(colorLut != null)
        Log.i(TAG, "Video renderer mode=$mode")
        if (colorLut == null && !recoverLateVideoFrames) return
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
            colorLut,
            recoverLateVideoFrames,
        )
    }
}

internal const val TONE_MAPPING_MODE_ORIGINAL = "ORIGINAL"
internal const val TONE_MAPPING_MODE_SDR_LUT = "SDR_LUT"

internal fun toneMappingMode(hasColorLut: Boolean): String =
    if (hasColorLut) TONE_MAPPING_MODE_SDR_LUT else TONE_MAPPING_MODE_ORIGINAL

private const val TAG = "LibaribtlvRenderers"

private class LibaribtlvToneMappingVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val colorLut: HlgToSdrColorLut?,
    private val recoverLateVideoFrames: Boolean,
) : MediaCodecVideoRenderer(builder) {

    private var nextSyncLogUs = 0L

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean =
        if (recoverLateVideoFrames) earlyUs < -30_000L && !isLastBuffer
        else super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer)

    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean =
        if (recoverLateVideoFrames) earlyUs < -500_000L && !isLastBuffer
        else super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer)

    override fun maybeDropBuffersToKeyframe(positionUs: Long, treatDroppedBuffersAsSkipped: Boolean): Boolean {
        val caughtUp = super.maybeDropBuffersToKeyframe(positionUs, treatDroppedBuffersAsSkipped)
        if (recoverLateVideoFrames && caughtUp) {
            Log.w("RecordedVideoSync", "Keyframe catch-up: clock_us=${positionUs - outputStreamOffsetUs}")
        }
        return caughtUp
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format,
    ): Boolean {
        if (recoverLateVideoFrames && elapsedRealtimeUs >= nextSyncLogUs) {
            nextSyncLogUs = elapsedRealtimeUs + 5_000_000L
            val offsetUs = outputStreamOffsetUs
            Log.i("RecordedVideoSync", "Video timing: clock_us=${positionUs - offsetUs} " +
                "video_us=${bufferPresentationTimeUs - offsetUs} " +
                "early_us=${bufferPresentationTimeUs - positionUs} decode_only=$isDecodeOnlyBuffer")
        }
        return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
            bufferFlags, sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
    }

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        val lut = colorLut ?: return super.createPlaybackVideoGraphWrapper(context, videoFrameReleaseControl)
        val videoGraphFactory: VideoGraph.Factory = SingleInputVideoGraph.Factory(
            DefaultVideoFrameProcessor.Factory.Builder()
                .setHlgToSdrColorLut(lut)
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
