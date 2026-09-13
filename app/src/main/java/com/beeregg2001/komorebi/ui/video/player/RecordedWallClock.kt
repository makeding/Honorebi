package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

// Resolve the system zone on each read, including while playback is paused or seeking.
internal fun formatRecordedWallClock(now: ZonedDateTime, timeFormat: String): String =
    now.format(DateTimeFormatter.ofPattern(if (timeFormat == "12H") "a h:mm" else "HH:mm", Locale.JAPANESE))

internal fun recordedEndTimeLabel(
    now: ZonedDateTime, timeFormat: String, positionMs: Long, durationMs: Long,
    speed: Float,
): String {
    if (durationMs > 0 && positionMs >= durationMs) return "再生終了"
    if (durationMs <= 0 || !speed.isFinite() || speed <= 0f) return "終了時刻未定"
    val remainingMs = (durationMs - positionMs.coerceAtLeast(0)).toDouble() / speed
    if (remainingMs > Long.MAX_VALUE / 1_000_000.0) return "終了時刻未定"
    val end = now.plusNanos((remainingMs * 1_000_000).toLong())
    val day = when (end.toLocalDate()) {
        now.toLocalDate() -> ""
        now.toLocalDate().plusDays(1) -> "翌日 "
        else -> end.format(DateTimeFormatter.ofPattern("M/d "))
    }
    return "$day${formatRecordedWallClock(end, timeFormat)} 終了予定"
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
    fun read(): Pair<String, String> {
        val current = now()
        return formatRecordedWallClock(current, timeFormat) to recordedEndTimeLabel(
            current, timeFormat, positionMs(), durationMs, speed,
        )
    }
    var display by remember(timeFormat, durationMs, speed) { mutableStateOf(read()) }
    // This component exists only in the visible status branch; disposal cancels polling.
    LaunchedEffect(timeFormat, now, positionMs, durationMs, speed) {
        while (true) {
            val latest = read()
            if (latest != display) display = latest
            delay(1_000)
        }
    }
    Column(
        modifier.size(256.dp, 56.dp).testTag("recorded-wall-clock"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(display.first, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, modifier = Modifier.height(34.dp),
            style = TextStyle(fontFeatureSettings = "tnum",
                shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 4f)))
        Text(display.second, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
            maxLines = 1, modifier = Modifier.height(22.dp).testTag("recorded-end-time"),
            style = TextStyle(fontFeatureSettings = "tnum",
                shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 4f)))
    }
}
