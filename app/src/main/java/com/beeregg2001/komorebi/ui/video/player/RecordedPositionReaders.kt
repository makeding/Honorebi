package com.beeregg2001.komorebi.ui.video.player

import androidx.media3.common.C
import androidx.media3.common.Player

/** Source-offset aware readers used by controls, history and chase playback. */
internal data class RecordedPositionReaders(
    val current: () -> Long,
    val buffered: () -> Long,
)

internal fun recordedPositionReaders(
    player: Player,
    retainedPositionMs: () -> Long,
    isLiveStream: Boolean,
    isRecordingChasePlayback: Boolean,
    playbackOffsetMs: () -> Long,
): RecordedPositionReaders {
    val current = {
        val raw = player.currentPosition
        when {
            raw == C.TIME_UNSET || raw < 0L ||
                (raw == 0L && retainedPositionMs() > 0L && player.playbackState != Player.STATE_READY) ->
                retainedPositionMs()
            isLiveStream && !isRecordingChasePlayback -> playbackOffsetMs() + raw
            else -> raw
        }.coerceAtLeast(0L)
    }
    return RecordedPositionReaders(
        current = current,
        buffered = {
            val position = current()
            val raw = player.bufferedPosition
            when {
                raw == C.TIME_UNSET -> position
                isLiveStream && !isRecordingChasePlayback -> playbackOffsetMs() + raw
                else -> raw
            }.coerceAtLeast(position)
        },
    )
}
