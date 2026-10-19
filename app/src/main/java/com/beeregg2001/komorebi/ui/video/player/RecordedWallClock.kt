package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.beeregg2001.komorebi.ui.player.PlayerWallClock
import com.beeregg2001.komorebi.ui.player.formatPlayerEndTime
import java.time.ZonedDateTime

internal fun recordedEndTimeLabel(
    now: ZonedDateTime, timeFormat: String, positionMs: Long, durationMs: Long,
    speed: Float,
): String {
    if (durationMs > 0 && positionMs >= durationMs) return "再生終了"
    if (durationMs <= 0 || !speed.isFinite() || speed <= 0f) return "終了時刻未定"
    val remainingMs = (durationMs - positionMs.coerceAtLeast(0)).toDouble() / speed
    if (remainingMs > Long.MAX_VALUE / 1_000_000.0) return "終了時刻未定"
    val end = now.plusNanos((remainingMs * 1_000_000).toLong())
    return formatPlayerEndTime(now, end, timeFormat)
}

@Composable
internal fun RecordedWallClock(
    timeFormat: String,
    modifier: Modifier = Modifier,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
    positionMs: () -> Long = { 0L },
    durationMs: Long = 0L,
    speed: Float = 1f,
) {
    val endTime = remember(timeFormat, positionMs, durationMs, speed) {
        { current: ZonedDateTime ->
            recordedEndTimeLabel(current, timeFormat, positionMs(), durationMs, speed)
        }
    }
    PlayerWallClock(
        timeFormat, modifier.testTag("recorded-wall-clock"), now,
        secondaryText = endTime, secondaryModifier = Modifier.testTag("recorded-end-time"),
    )
}
