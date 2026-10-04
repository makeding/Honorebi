package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.data.model.Channel
/** Channel-number prefix shared by live and recorded presentation. */
fun formatChannelType(type: String): String = when (type.uppercase()) {
    "GR" -> "地デジ"
    "BS" -> "BS"
    "CS" -> "CS"
    else -> type
}

/** IPTV has no broadcast channel number; do not invent a label or separator. */
fun liveChannelNumberLabel(channel: Channel): String? =
    if (channel.type.equals("IPTV", ignoreCase = true) || channel.supportsLiveStreamSession()) null
    else "${formatChannelType(channel.type)}${channel.channelNumber}"

fun liveChannelTitle(channel: Channel): String =
    liveChannelNumberLabel(channel)?.let { "$it  ${channel.name}" } ?: channel.name
