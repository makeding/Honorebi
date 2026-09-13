@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.player

import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks

internal fun isHlgToneMappingContent(format: Format): Boolean =
    format.width >= 3840 && format.height >= 2160 &&
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG

internal fun selectedHlgToneMappingContent(tracks: Tracks): Boolean? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return isHlgToneMappingContent(group.getTrackFormat(index))
        }
    }
    return null
}

/** Keep source classification through renderer recreation, but never across content changes. */
@Composable
internal fun rememberHlgToneMappingContent(player: Player?, contentKey: Any): Boolean {
    var eligible by remember(contentKey) { mutableStateOf(false) }
    DisposableEffect(player, contentKey) {
        fun update(tracks: Tracks) {
            selectedHlgToneMappingContent(tracks)?.let { eligible = it }
        }
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) = update(tracks)
        }
        player?.let { update(it.currentTracks); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
    }
    return eligible
}
