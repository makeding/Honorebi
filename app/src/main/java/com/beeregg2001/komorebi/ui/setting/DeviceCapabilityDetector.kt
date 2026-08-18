package com.beeregg2001.komorebi.ui.setting

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager

data class DeviceCapabilityReport(
    val deviceName: String,
    val androidVersion: String,
    val hevcDecoders: List<String>,
    val maxVerifiedMode: String,
    val supportsHevcMain10: Boolean,
    val supports4k60: Boolean,
    val supports8k30: Boolean,
    val supports8k60: Boolean,
    val hdrTypes: List<String>,
    val maxReportedAudioChannels: Int?
) {
    val supportsBs4kDirect: Boolean
        get() = supports4k60 && supportsHevcMain10

    val supportsBs8kDirect: Boolean
        get() = supports8k60 && supportsHevcMain10
}

object DeviceCapabilityDetector {
    private data class VideoMode(val width: Int, val height: Int, val fps: Double) {
        val label: String = "${width}x${height} @ ${fps.toInt()}fps"
    }

    private val probeModes = listOf(
        VideoMode(7680, 4320, 60.0),
        VideoMode(7680, 4320, 30.0),
        VideoMode(4096, 2160, 60.0),
        VideoMode(3840, 2160, 60.0),
        VideoMode(3840, 2160, 30.0),
        VideoMode(1920, 1080, 60.0)
    )

    fun detect(context: Context): DeviceCapabilityReport {
        val hevcCapabilities = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .mapNotNull { codecInfo ->
                    val hevcType = codecInfo.supportedTypes.firstOrNull {
                        it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    } ?: return@mapNotNull null
                    runCatching { codecInfo to codecInfo.getCapabilitiesForType(hevcType) }.getOrNull()
                }
                .toList()
        }.getOrDefault(emptyList())

        fun supports(mode: VideoMode): Boolean = hevcCapabilities.any { (_, capabilities) ->
            runCatching {
                capabilities.videoCapabilities?.areSizeAndRateSupported(
                    mode.width,
                    mode.height,
                    mode.fps
                ) == true
            }.getOrDefault(false)
        }

        val main10Profiles = buildSet {
            add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)
            }
        }
        val supportsMain10 = hevcCapabilities.any { (_, capabilities) ->
            capabilities.profileLevels.any { it.profile in main10Profiles }
        }

        val hdrTypes = runCatching {
            displayFor(context)?.hdrCapabilities?.supportedHdrTypes
                ?.let(::hdrTypeLabels)
                .orEmpty()
        }.getOrDefault(emptyList())

        val maxAudioChannels = runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .flatMap { it.channelCounts.asIterable() }
                .maxOrNull()
        }.getOrNull()

        val mode8k60 = probeModes[0]
        val mode8k30 = probeModes[1]
        val mode4k60 = probeModes.first { it.width == 3840 && it.fps == 60.0 }

        return DeviceCapabilityReport(
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            hevcDecoders = hevcCapabilities.map { it.first.name }.distinct(),
            maxVerifiedMode = probeModes.firstOrNull(::supports)?.label ?: "HEVC decoder not detected",
            supportsHevcMain10 = supportsMain10,
            supports4k60 = supports(mode4k60),
            supports8k30 = supports(mode8k30),
            supports8k60 = supports(mode8k60),
            hdrTypes = hdrTypes,
            maxReportedAudioChannels = maxAudioChannels
        )
    }

    private fun displayFor(context: Context): Display? {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        return display?.takeIf(Display::isValid)
    }

    internal fun hdrTypeLabels(supportedHdrTypes: IntArray): List<String> = buildList {
        supportedHdrTypes.forEach { hdrType ->
            val label = when (hdrType) {
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                else -> null
            }
            if (label != null && label !in this) add(label)
        }
    }
}
