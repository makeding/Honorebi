package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
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

@Composable
internal fun RecordedWallClock(
    timeFormat: String,
    modifier: Modifier = Modifier,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    var display by remember(timeFormat) { mutableStateOf(formatRecordedWallClock(now(), timeFormat)) }
    // This component exists only in the visible status branch; disposal cancels polling.
    LaunchedEffect(timeFormat, now) {
        while (true) {
            val latest = formatRecordedWallClock(now(), timeFormat)
            if (latest != display) display = latest
            delay(1_000)
        }
    }
    Box(
        modifier.size(160.dp, 48.dp).testTag("recorded-wall-clock")
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(display, color = Color.White, fontSize = 20.sp, maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"))
    }
}
