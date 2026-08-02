package com.beeregg2001.komorebi.ui.live

internal const val DATA_BROADCASTING_DIRECTION_DOUBLE_TAP_MS = 350L

internal enum class DataBroadcastingDirection(
    val remoteKey: String,
    val colorKey: DataBroadcastingColorKey
) {
    Up("up", DataBroadcastingColorKey.Blue),
    Right("right", DataBroadcastingColorKey.Red),
    Down("down", DataBroadcastingColorKey.Green),
    Left("left", DataBroadcastingColorKey.Yellow)
}

internal data class DataBroadcastingDirectionTapResult(
    val immediateRemoteKey: String? = null,
    val pendingToken: Long? = null,
    val colorKey: DataBroadcastingColorKey? = null
)

/**
 * Distinguishes a normal direction-key tap from the receiver's double-tap color shortcut.
 * Timing is owned by the caller so this state machine stays independent from Android/Compose.
 */
internal class DataBroadcastingDirectionShortcut {
    private var pendingDirection: DataBroadcastingDirection? = null
    private var token = 0L

    fun onTap(direction: DataBroadcastingDirection): DataBroadcastingDirectionTapResult {
        val previousDirection = pendingDirection
        token += 1L

        if (previousDirection == direction) {
            pendingDirection = null
            return DataBroadcastingDirectionTapResult(colorKey = direction.colorKey)
        }

        pendingDirection = direction
        return DataBroadcastingDirectionTapResult(
            immediateRemoteKey = previousDirection?.remoteKey,
            pendingToken = token
        )
    }

    fun onTimeout(pendingToken: Long): String? {
        if (pendingToken != token) return null
        val direction = pendingDirection ?: return null
        pendingDirection = null
        token += 1L
        return direction.remoteKey
    }

    fun flush(): String? {
        val remoteKey = pendingDirection?.remoteKey
        reset()
        return remoteKey
    }

    fun reset() {
        pendingDirection = null
        token += 1L
    }
}
