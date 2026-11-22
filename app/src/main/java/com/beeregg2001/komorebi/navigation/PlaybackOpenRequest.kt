package com.beeregg2001.komorebi.navigation

enum class PlaybackOpenSource {
    Remote,
    DeepLink,
}

sealed interface PlaybackOpenRequest {
    val source: PlaybackOpenSource

    data class Live(
        val displayChannelId: String,
        override val source: PlaybackOpenSource,
    ) : PlaybackOpenRequest

    data class Recording(
        val recordedProgramId: Int,
        val positionSeconds: Double? = null,
        override val source: PlaybackOpenSource,
    ) : PlaybackOpenRequest
}
