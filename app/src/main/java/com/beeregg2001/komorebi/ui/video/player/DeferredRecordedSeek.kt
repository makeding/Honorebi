@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player

/** Owns only committed targets waiting for a seek map; gesture previews stay separate. */
@Stable
internal class DeferredRecordedSeek {
    var targetMs by mutableStateOf<Long?>(null)
        private set

    fun request(positionMs: Long) { targetMs = positionMs.coerceAtLeast(0L) }
    fun cancel() { targetMs = null }

    fun takeIfReady(seekable: Boolean, current: Boolean, foreground: Boolean): Long? {
        if (!current || !foreground) { cancel(); return null }
        if (!seekable) return null
        return targetMs.also { targetMs = null }
    }
}

@Composable
internal fun AwaitRecordedSeek(
    pending: DeferredRecordedSeek,
    player: Player,
    playbackIdentity: Any,
    isCurrent: () -> Boolean,
    commit: (Long) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(isCurrent)
    val onCommit by rememberUpdatedState(commit)
    DisposableEffect(pending, player, playbackIdentity, owner) {
        fun drain() {
            pending.takeIfReady(
                seekable = player.isCurrentMediaItemSeekable,
                current = current(),
                foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )?.let(onCommit)
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = drain()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_DESTROY) pending.cancel()
        }
        player.addListener(listener)
        owner.lifecycle.addObserver(observer)
        onDispose {
            pending.cancel()
            player.removeListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
}
