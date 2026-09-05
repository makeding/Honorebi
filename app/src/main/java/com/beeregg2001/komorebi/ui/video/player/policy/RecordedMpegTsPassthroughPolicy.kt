package com.beeregg2001.komorebi.ui.video.player.policy

import com.beeregg2001.komorebi.data.model.StreamQuality

/** Selects the native tsreadex path used by recorded MPEG-2 hardware-DI playback. */
object RecordedMpegTsPassthroughPolicy {
    fun shouldUseTsReadEx(
        containerFormat: String?,
        videoCodec: String?,
        qualityValue: String,
    ): Boolean = containerFormat.equals("MPEG-TS", ignoreCase = true) &&
        videoCodec.equals("MPEG-2", ignoreCase = true) &&
        qualityValue == StreamQuality.ORIGINAL_MPEG_TS_VALUE

    fun tsReadExArguments(serviceId: Int): Array<String> = arrayOf(
        "tsreadex",
        "-x", "18/38/39",
        "-n", serviceId.toString(),
        "-a", "13",
        "-b", "5",
        "-c", "5",
        "-u", "1",
        "-d", "13",
    )
}
