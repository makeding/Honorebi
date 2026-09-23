package com.beeregg2001.komorebi.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedPlaybackFormatTest {
    @Test
    fun rawPlaybackFollowsContainerRatherThanBroadcastChannel() {
        listOf(
            Triple("BS4K", "MMT/TLV", true),
            Triple("BS4K", "MPEG-TS", false),
            Triple("BS4K", "MPEG-4", false),
            Triple("GR", "MMT/TLV", true),
            Triple("BS4K", "", false),
        ).forEach { (channelType, container, expected) ->
            val program = RecordedProgram(
                id = 42,
                title = "録画",
                description = "",
                startTime = "2026-09-23T00:00:00+09:00",
                endTime = "2026-09-23T01:00:00+09:00",
                duration = 3600.0,
                isPartiallyRecorded = false,
                channel = RecordedChannel("ch", displayChannelId = "ch", type = channelType,
                    name = "局", channelNumber = "1"),
                recordedVideo = RecordedVideo(42, "Recorded", "/recording", duration = 3600.0,
                    containerFormat = container, videoCodec = "HEVC", audioCodec = "AAC"),
            )
            assertEquals("$channelType / $container", expected, program.requiresRawMmtsPlayback)
        }
    }
}
