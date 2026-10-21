@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import kotlin.math.pow

/** Match HonomiTV's four AAC gain steps (three for surround) after PCM decoding. */
internal fun originalTsAudioGainProcessor() = ChannelMixingAudioProcessor().apply {
    for (channels in 1..8) {
        val gain = if (channels <= 2) 2f else 2.0.pow(0.75).toFloat()
        putChannelMixingMatrix(ChannelMixingMatrix(channels, channels,
            FloatArray(channels * channels) { index ->
                if (index / channels == index % channels) gain else 0f
            }))
    }
}
