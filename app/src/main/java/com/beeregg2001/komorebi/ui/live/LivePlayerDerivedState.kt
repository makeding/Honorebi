package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource

/**
 * The channel data used by the live-player UI, derived from the currently selected
 * channel and the displayable channel list. Keeping this independent from Compose lets
 * every entry point (including mini-player selection) use the same navigation rules.
 */
internal data class LiveChannelNavigation(
    val flatChannels: List<Channel>,
    val currentChannel: Channel,
    val recentChannels: List<Channel>,
    val previousChannel: Channel?,
    val nextChannel: Channel?
)

internal fun deriveLiveChannelNavigation(
    selectedChannel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    lastWatchedChannels: List<Channel>
): LiveChannelNavigation {
    val flatChannels = groupedChannels.values.flatten()
    val currentChannel = flatChannels.find { it.id == selectedChannel.id } ?: selectedChannel
    val channelsByUniqueId = flatChannels.associateBy(Channel::uniqueId)
    val recentChannels = lastWatchedChannels.mapNotNull { channelsByUniqueId[it.uniqueId] }
    val currentIndex = flatChannels.indexOfFirst { it.id == currentChannel.id }
    val canNavigate = flatChannels.size > 1 && currentIndex >= 0

    return LiveChannelNavigation(
        flatChannels = flatChannels,
        currentChannel = currentChannel,
        recentChannels = recentChannels,
        previousChannel = if (canNavigate) {
            flatChannels[(currentIndex - 1 + flatChannels.size) % flatChannels.size]
        } else {
            null
        },
        nextChannel = if (canNavigate) flatChannels[(currentIndex + 1) % flatChannels.size] else null
    )
}

internal fun effectiveLiveQualities(
    availableQualities: List<StreamQuality>,
    channel: Channel
): List<StreamQuality> =
    if (channel.type.equals("BS4K", ignoreCase = true)) {
        StreamQuality.rawMmtsQualities(channel)
    } else {
        availableQualities
    }

internal fun isLiveMediaSessionLoading(
    currentChannelId: String,
    lastChannelIdForSwitchHint: String,
    hasRenderedFirstFrame: Boolean,
    isBuffering: Boolean
): Boolean =
    currentChannelId != lastChannelIdForSwitchHint || !hasRenderedFirstFrame || isBuffering

internal data class LiveLoadingPresentation(
    val isVisible: Boolean,
    val message: String
)

/** Pure loading decision for the main live playback surface. */
internal fun liveLoadingPresentation(
    streamSource: StreamSource,
    isEdcbDirect: Boolean,
    sseStatus: String,
    sseDetail: String,
    playerError: String?,
    isBuffering: Boolean,
    hasRenderedFirstFrame: Boolean,
    isPlaybackReady: Boolean,
    statusLoadingText: String
): LiveLoadingPresentation {
    val isVisible = when {
        playerError != null -> false
        !hasRenderedFirstFrame -> true
        streamSource == StreamSource.KONOMITV ->
            (sseStatus == "Standby" || sseStatus == "Offline") && sseDetail.isNotEmpty()
        streamSource == StreamSource.EDCB && !isEdcbDirect ->
            sseStatus == "Standby" || isBuffering
        else -> isBuffering
    }

    val message = when {
        !hasRenderedFirstFrame && isPlaybackReady -> "映像をデコード中..."
        !hasRenderedFirstFrame && sseDetail.isBlank() -> "映像データを待っています..."
        streamSource == StreamSource.KONOMITV -> sseDetail
        streamSource == StreamSource.EDCB && !isEdcbDirect && sseStatus == "Standby" -> sseDetail
        else -> statusLoadingText
    }
    return LiveLoadingPresentation(isVisible = isVisible, message = message)
}
