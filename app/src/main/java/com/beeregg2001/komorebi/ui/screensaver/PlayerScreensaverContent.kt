package com.beeregg2001.komorebi.ui.screensaver

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Transparent surface: the already paused player remains the background. */
@Composable
fun PlayerScreensaverContent(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("player-screensaver")) {
        ScreensaverReadabilityOverlay()
        val compact = maxWidth < 720.dp
        val edge = if (compact) 32.dp else 48.dp
        val titleHeight = with(LocalDensity.current) { 88.sp.toDp() }
        val subtitleHeight = with(LocalDensity.current) { 32.sp.toDp() }
        Column(
            Modifier.align(Alignment.BottomStart).padding(edge).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White, fontSize = if (compact) 88.sp else 112.sp,
                lineHeight = if (compact) 100.sp else 124.sp,
                fontWeight = FontWeight.Light, maxLines = 1,
                modifier = Modifier.fillMaxWidth().testTag("player-screensaver-clock"),
            )
            Text(screensaverDate(now), color = Color.White, fontSize = 22.sp, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            // Fixed title and subtitle slots keep neighboring bounds stable as metadata changes.
            Text(
                title.ifBlank { "番組情報なし" }, color = Color.White,
                fontSize = 28.sp, lineHeight = 36.sp, minLines = 2, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(titleHeight).testTag("player-screensaver-title"),
            )
            Text(
                subtitle.ifBlank { "一時停止中" }, color = Color.White.copy(alpha = 0.9f),
                fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(subtitleHeight).testTag("player-screensaver-subtitle"),
            )
        }
    }
}
