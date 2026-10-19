package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.subtitle.rememberCaptionTextBounds
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

/** Live and recorded playback share the title, fixed time slots and track drawing. */
@Composable
fun PlayerTimelineTitle(
    title: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onBoundsChanged: (List<Rect>) -> Unit = {},
) {
    val bounds = rememberCaptionTextBounds(onBoundsChanged)
    Text(
        text = title.ifBlank { "タイトルなし" },
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
        color = Color.White, maxLines = 1,
        modifier = modifier.fillMaxWidth().height(24.dp)
            .onGloballyPositioned(bounds::onPositioned)
            .basicMarquee(iterations = if (isVisible) Int.MAX_VALUE else 0, initialDelayMillis = 2000),
        onTextLayout = bounds::onTextLayout,
    )
}

@Composable
fun PlayerTimelineRow(
    positionMs: Long?,
    durationMs: Long?,
    trackModifier: Modifier = Modifier,
    trackHeight: Dp = 48.dp,
    onElapsedBoundsChanged: (List<Rect>) -> Unit = {},
    onDurationBoundsChanged: (List<Rect>) -> Unit = {},
    speedContent: @Composable BoxScope.() -> Unit = {},
    track: @Composable BoxScope.() -> Unit,
) {
    val elapsedBounds = rememberCaptionTextBounds(onElapsedBoundsChanged)
    val durationBounds = rememberCaptionTextBounds(onDurationBoundsChanged)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = positionMs?.let(::formatPlayerDurationMillis) ?: "--:--",
            color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp).testTag("playback-time")
                .onGloballyPositioned(elapsedBounds::onPositioned),
            onTextLayout = elapsedBounds::onTextLayout,
        )
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).height(trackHeight).testTag("playback-track").then(trackModifier),
            contentAlignment = Alignment.CenterStart, content = track)
        Spacer(Modifier.width(16.dp))
        // Reserve the same speed slot in both modes so time/track bounds never shift.
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd, content = speedContent)
        Text(
            text = durationMs?.let(::formatPlayerDurationMillis) ?: "--:--",
            color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp).testTag("playback-duration")
                .onGloballyPositioned(durationBounds::onPositioned),
            onTextLayout = durationBounds::onTextLayout,
        )
    }
}

@Composable
fun BoxScope.PlayerTimelineTrack(
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long = 0,
    focused: Boolean = false,
    trackHeight: Dp = 6.dp,
    playHeadSize: Dp = 12.dp,
    playHeadVerticalOffset: Dp = (playHeadSize - trackHeight) / 2,
    onTrackBoundsChanged: (Rect) -> Unit = {},
    onPlayHeadBoundsChanged: (Rect) -> Unit = {},
    decorations: @Composable BoxScope.() -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    Box(Modifier.fillMaxWidth().height(trackHeight).align(Alignment.BottomStart)
        .onGloballyPositioned { onTrackBoundsChanged(it.boundsInRoot()) }
        .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
    decorations()
    val buffered = (bufferedPositionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    if (buffered > 0f) {
        Box(Modifier.fillMaxWidth(buffered).height(trackHeight).align(Alignment.BottomStart)
            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
    }
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    if (progress > 0f) {
        Box(Modifier.fillMaxWidth(progress).height(trackHeight).align(Alignment.BottomStart)
            .background(if (focused) colors.accent else colors.accent.copy(alpha = 0.8f), RoundedCornerShape(4.dp)))
    }
    Box(Modifier.fillMaxWidth(progress).fillMaxHeight(), contentAlignment = Alignment.BottomEnd) {
        Box(Modifier.offset(x = playHeadSize / 2, y = playHeadVerticalOffset).size(playHeadSize)
            .onGloballyPositioned { onPlayHeadBoundsChanged(it.boundsInRoot()) }
            .background(if (focused) colors.accent else Color.White, CircleShape))
    }
}
