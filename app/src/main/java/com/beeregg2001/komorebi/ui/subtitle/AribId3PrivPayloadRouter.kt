package com.beeregg2001.komorebi.ui.subtitle

/** Protocol routing for aribb24.js ID3 PRIV payloads emitted by tsreadex. */
internal object AribId3PrivPayloadRouter {
    sealed interface Route {
        data class Caption(val render: Boolean) : Route
        data object Superimpose : Route
        data object Ignore : Route
    }

    fun route(privateData: ByteArray, captionsEnabled: Boolean): Route {
        if (privateData.size < MIN_PAYLOAD_SIZE ||
            privateData[1].toInt() and 0xff != PRIVATE_STREAM_ID
        ) {
            return Route.Ignore
        }
        return when (privateData[0].toInt() and 0xff) {
            DATA_IDENTIFIER_CAPTION -> Route.Caption(render = captionsEnabled)
            DATA_IDENTIFIER_SUPERIMPOSE -> Route.Superimpose
            else -> Route.Ignore
        }
    }

    fun shouldResetForPositionDiscontinuity(reason: Int): Boolean =
        reason != POSITION_DISCONTINUITY_REASON_INTERNAL

    private const val MIN_PAYLOAD_SIZE = 3
    private const val PRIVATE_STREAM_ID = 0xff
    private const val DATA_IDENTIFIER_CAPTION = 0x80
    private const val DATA_IDENTIFIER_SUPERIMPOSE = 0x81

    // Mirrors Player.DISCONTINUITY_REASON_INTERNAL without coupling this pure router to Media3.
    internal const val POSITION_DISCONTINUITY_REASON_INTERNAL = 5
}
