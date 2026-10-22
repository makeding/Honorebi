package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.PlayerClockMode

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.player.PlayerOverlayEdgePaddingHorizontal
import com.beeregg2001.komorebi.ui.player.PlayerOverlayEdgePaddingVertical
import com.beeregg2001.komorebi.ui.player.PlayerChannelStatus
import com.beeregg2001.komorebi.ui.player.PlayerTimelineTitle
import com.beeregg2001.komorebi.ui.player.PlayerTimelineRow
import com.beeregg2001.komorebi.ui.player.PlayerTimelineTrack
import com.beeregg2001.komorebi.ui.player.formatPlayerEndTime
import com.beeregg2001.komorebi.ui.player.PlayerWallClock
import androidx.compose.ui.platform.testTag
import java.time.ZonedDateTime
import com.beeregg2001.komorebi.ui.player.PlayerLCropOverlay
import com.beeregg2001.komorebi.ui.player.liveChannelNumberLabel
import com.beeregg2001.komorebi.ui.player.liveChannelTitle
import com.beeregg2001.komorebi.ui.subtitle.rememberCaptionTextBounds
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.util.*

/**
 * REGZA風の信号情報オーバーレイ
 */
@Composable
fun SignalInfoOverlay(info: SignalMetadata) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = PlayerOverlayEdgePaddingHorizontal,
                bottom = PlayerOverlayEdgePaddingVertical
            ),
        contentAlignment = Alignment.BottomStart
    ) {
        Column(
            modifier = Modifier
                .background(colors.background.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(24.dp)
                .width(320.dp)
        ) {
            Text(
                text = "信号情報",
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SignalRow("解像度", info.videoRes)
            SignalRow("垂直周波数", info.verticalFreq)
            SignalRow("映像形式", info.videoCodec)
            SignalRow("ビットレート", info.videoBitrate)
            SignalRow("音声形式", info.audioCodec)
            SignalRow("音声出力", info.audioChannels)
            SignalRow("サンプリング", info.audioSampleRate)
            SignalRow("バッファ量", info.bufferDuration)
            SignalRow("ドロップ数", info.droppedFrames)
        }
    }
}

@Composable
private fun SignalRow(label: String, value: String) {
    val colors = KomorebiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(100.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
            color = colors.textSecondary.copy(alpha = 0.8f)
        )
        Text(
            text = ": $value",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = colors.textPrimary
        )
    }
}


@Composable
fun StatusOverlay(
    channel: Channel,
    logoUrl: String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
    timeFormatSetting: String = "24H",
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
    isRecording: Boolean = false,
    clockMode: PlayerClockMode = PlayerClockMode.ALWAYS_ON,
) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        val program = channel.programPresent
        val timeline = remember(program) { LiveProgramTimeline.from(program) }
        val showCurrentTime = clockMode.showsCurrentTime(program?.genres?.map { it.major })
        val endText = remember(timeline, timeFormatSetting) {
            { current: ZonedDateTime ->
                when {
                    timeline == null -> ""
                    current.toInstant() >= timeline.end -> "終了時刻未定"
                    else -> formatPlayerEndTime(current, timeline.end.atZone(current.zone), timeFormatSetting)
                }
            }
        }
        if (showCurrentTime || timeline != null) {
            PlayerWallClock(
                timeFormatSetting,
                Modifier.align(Alignment.TopStart).testTag("live-wall-clock"),
                now = now, secondaryText = endText,
                secondaryModifier = Modifier.testTag("live-end-time"),
                showCurrentTime = showCurrentTime,
            )
        }
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
        PlayerChannelStatus(
            channelName = channel.name,
            logoUrl = logoUrl,
            shouldCropLogo = shouldCropLogo,
            modifier = Modifier.testTag("live-channel-status"),
            nameModifier = Modifier.testTag("live-channel-name"),
        )
            if (isRecording) {
                Spacer(Modifier.height(8.dp))
                RecordingIndicator()
            }
        }
    }
}

@Composable
fun LiveOverlayUI(
    channel: Channel,
    programTitle: String,
    logoUrl: String,
    shouldCropLogo: Boolean, // ★ 追加: クロップフラグ
    showDesc: Boolean,
    isRecording: Boolean,
    scrollState: ScrollState,
    timeFormatSetting: String = "24H",
    restingRootOrigin: Offset = Offset.Zero,
    onAvoidanceObstaclesChanged: (List<Rect>) -> Unit = {},
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    val program = channel.programPresent
    val timeline = remember(program) { LiveProgramTimeline.from(program) }
    val currentNow by rememberUpdatedState(now)
    var currentTime by remember(timeline) { mutableStateOf(currentNow().toInstant()) }
    val hasTiming = timeline != null && currentTime < timeline.end
    val positionMs = if (hasTiming) timeline.positionMs(currentTime) else null
    val durationMs = if (hasTiming) timeline.durationMs else null
    val density = LocalDensity.current
    val obstaclePaddingPx = with(density) { 8.dp.toPx() }
    val animatedBounds = remember { mutableStateMapOf<String, Rect>() }
    var overlayRootOrigin by remember { mutableStateOf(Offset.Zero) }
    val recordAvoidanceBounds: (String, Rect) -> Unit = { key, bounds ->
        animatedBounds[key] = bounds
    }
    val recordTextBounds: (String, List<Rect>) -> Unit = { prefix, lineBounds ->
        animatedBounds.keys.filter { it.startsWith("$prefix-") }.forEach(animatedBounds::remove)
        lineBounds.forEachIndexed { index, bounds -> recordAvoidanceBounds("$prefix-$index", bounds) }
    }

    LaunchedEffect(
        showDesc, restingRootOrigin, overlayRootOrigin, animatedBounds.toMap()
    ) {
        val currentBounds = animatedBounds.values.map { bounds ->
            val restingBounds = bounds.translate(restingRootOrigin - overlayRootOrigin)
            Rect(
                left = restingBounds.left - obstaclePaddingPx,
                top = restingBounds.top - obstaclePaddingPx,
                right = restingBounds.right + obstaclePaddingPx,
                bottom = restingBounds.bottom + obstaclePaddingPx,
            )
        }
        onAvoidanceObstaclesChanged(if (showDesc) emptyList() else currentBounds)
    }

    // Read once immediately on entry/EPG change, then update only this visible timeline.
    LaunchedEffect(timeline) {
        if (timeline != null) {
            while (true) {
                currentTime = currentNow().toInstant()
                delay(1_000)
            }
        }
    }

    if (showDesc) {
        com.beeregg2001.komorebi.ui.player.PlayerProgramPanel(
            channelName = liveChannelTitle(channel),
            logoUrl = logoUrl, shouldCropLogo = shouldCropLogo, title = programTitle,
            description = program?.description, detail = program?.detail,
            metadata = listOf("放送日時" to (program?.let {
                com.beeregg2001.komorebi.ui.video.player.formatBroadcastTime(it.startTime, it.endTime, timeFormatSetting)
            } ?: "番組情報なし")), scrollState = scrollState,
            channelAdornment = { if (isRecording) RecordingIndicator() },
            feedback = {
                Column(Modifier.fillMaxWidth()) {
                    PlayerTimelineRow(positionMs, durationMs) {
                        PlayerTimelineTrack(positionMs ?: 0, durationMs ?: 0)
                    }
                    Text("下キーでスクロール  ·  上キーでメニュー  ·  戻るで閉じる", color = Color.White.copy(0.7f),
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates -> overlayRootOrigin = coordinates.positionInRoot() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(0.95f)
                        )
                    )
                )
                .padding(
                    horizontal = PlayerOverlayEdgePaddingHorizontal,
                    vertical = PlayerOverlayEdgePaddingVertical
                )
        ) {
            PlayerTimelineTitle(
                programTitle, Modifier.testTag("live-title"),
                onBoundsChanged = { bounds -> recordTextBounds("program-title", bounds) },
            )
            Spacer(Modifier.height(8.dp))
            PlayerTimelineRow(
                positionMs, durationMs,
                onElapsedBoundsChanged = { recordTextBounds("start-time", it) },
                onDurationBoundsChanged = { recordTextBounds("end-time", it) },
            ) {
                PlayerTimelineTrack(
                    positionMs ?: 0, durationMs ?: 0,
                    onTrackBoundsChanged = { recordAvoidanceBounds("program-progress", it) },
                    onPlayHeadBoundsChanged = { recordAvoidanceBounds("program-play-head", it) },
                )
            }
        }
    }
}


@Composable
fun RecordingIndicator(onBoundsChanged: (Rect) -> Unit = {}) {
    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates -> onBoundsChanged(Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))) }
            .background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFF5252).copy(0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFFFF5252), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "録画中",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveErrorDialog(
    errorMessage: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onCheckCapabilities: (() -> Unit)? = null
) {
    val colors = KomorebiTheme.colors
    val retryButtonFocusRequester = remember { FocusRequester() }
    val capabilityButtonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(onCheckCapabilities) {
        (if (onCheckCapabilities != null) capabilityButtonFocusRequester else retryButtonFocusRequester)
            .requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(450.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF5252),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 16.dp)
                )
                Text(
                    text = AppStrings.LIVE_PLAYER_ERROR_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                if (onCheckCapabilities != null) {
                    DialogActionButton(
                        role = DialogActionRole.Primary,
                        onClick = onCheckCapabilities,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(capabilityButtonFocusRequester)
                    ) {
                        Icon(Icons.Default.Memory, null)
                        Spacer(Modifier.width(8.dp))
                        Text("テレビの再生能力を確認")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.BUTTON_BACK)
                    }
                    DialogActionButton(
                        role = DialogActionRole.Primary,
                        onClick = onRetry,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(retryButtonFocusRequester)
                    ) {
                        Text(AppStrings.BUTTON_RETRY)
                    }
                }
            }
        }
    }
}

/**
 * L字クロップ機能の設定・調整用オーバーレイ
 * (パネル本体は ui/player/PlayerCropOverlay.kt のライブ/録画共通実装に委譲)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LCropOverlay(
    state: LivePlayerState,
    onClose: () -> Unit
) {
    PlayerLCropOverlay(crop = state.crop, onClose = onClose)
}
