@file:androidx.annotation.OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

object HdrToneMapping {
    const val RENDER_MODE_ORIGINAL = "ORIGINAL"
    const val RENDER_MODE_SDR = "SDR_TONE_MAP"

    val isSupported: Boolean by lazy {
        detectSupport()
    }

    fun codecAdapterFactory(
        delegate: MediaCodecAdapter.Factory,
        enabled: Boolean
    ): MediaCodecAdapter.Factory = HdrToneMappingCodecAdapterFactory(delegate, enabled)

    private fun detectSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                3840,
                2160
            ).apply {
                setFloat(MediaFormat.KEY_FRAME_RATE, 59.94f)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(
                    MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                    MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                )
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codecInfo ->
                !codecInfo.isEncoder &&
                    codecInfo.isHardwareAccelerated &&
                    codecInfo.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    } &&
                    codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        .isFormatSupported(format)
            }
        }.onFailure {
            Log.w(TAG, "HDR-to-SDR capability detection failed", it)
        }.getOrDefault(false)
    }

    private class HdrToneMappingCodecAdapterFactory(
        private val delegate: MediaCodecAdapter.Factory,
        private val enabled: Boolean
    ) : MediaCodecAdapter.Factory {
        override fun createAdapter(
            configuration: MediaCodecAdapter.Configuration
        ): MediaCodecAdapter {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorTransfer = configuration.format.colorInfo?.colorTransfer
                if (
                    colorTransfer == C.COLOR_TRANSFER_HLG ||
                    colorTransfer == C.COLOR_TRANSFER_ST2084
                ) {
                    configuration.mediaFormat.setInteger(
                        MediaFormat.KEY_COLOR_TRANSFER_REQUEST,
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    )
                    Log.i(
                        TAG,
                        "Requesting hardware HDR-to-SDR tone mapping: " +
                            "codec=${configuration.codecInfo.name}, transfer=$colorTransfer"
                    )
                }
            }
            return delegate.createAdapter(configuration)
        }
    }

    private const val TAG = "HdrToneMapping"
}
