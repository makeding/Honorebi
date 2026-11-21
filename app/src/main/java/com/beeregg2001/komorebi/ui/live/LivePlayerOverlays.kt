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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
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
 * REGZA風の信号情報オーバーレイ (コンパクト2カラムHUD)
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
                .background(com.beeregg2001.komorebi.ui.theme.OsdHudSurface, RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .padding(14.dp)
                .width(460.dp)
        ) {
            Text(
                text = "信号情報",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = colors.accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Column 1 (Video)
                Column(modifier = Modifier.weight(1f)) {
                    CompactSignalRow("解像度", info.videoRes)
                    CompactSignalRow("周波数", info.verticalFreq)
                    CompactSignalRow("映像形式", info.videoCodec)
                    CompactSignalRow("ビットレート", info.videoBitrate)
                    val isDropped = (info.droppedFrames.toIntOrNull() ?: 0) > 0
                    CompactSignalRow(
                        "ドロップ数",
                        info.droppedFrames,
                        valueColor = if (isDropped) colors.danger else Color.White
                    )
                }

                // Column 2 (Audio & Buffer)
                Column(modifier = Modifier.weight(1f)) {
                    CompactSignalRow("音声形式", info.audioCodec)
                    CompactSignalRow("音声出力", info.audioChannels)
                    CompactSignalRow("サンプリング", info.audioSampleRate)
                    CompactSignalRow("バッファ量", info.bufferDuration)
                }
            }
        }
    }
}

@Composable
private fun CompactSignalRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    val colors = KomorebiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = colors.textSecondary.copy(alpha = 0.85f),
            maxLines = 1
        )
        Text(
            text = ": $value",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = valueColor,
            maxLines = 1
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
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var bottomBarSize by remember { mutableStateOf(IntSize.Zero) }
    val latestOnAvoidanceObstaclesChanged by rememberUpdatedState(onAvoidanceObstaclesChanged)

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val panelObstacle = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        val rootWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
        val rootHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val panelHeightPx = with(density) { 440.dp.toPx() }
        listOf(
            Rect(
                left = 0f,
                top = (rootHeightPx - panelHeightPx).coerceAtLeast(0f),
                right = rootWidthPx,
                bottom = rootHeightPx
            )
        )
    }
    val restingBottomBarObstacle = remember(
        restingRootOrigin, rootSize, bottomBarSize, obstaclePaddingPx
    ) {
        if (rootSize == IntSize.Zero || bottomBarSize == IntSize.Zero) {
            emptyList()
        } else {
            val left = restingRootOrigin.x + ((rootSize.width - bottomBarSize.width) / 2f)
            val top = restingRootOrigin.y + (rootSize.height - bottomBarSize.height).coerceAtLeast(0)
            listOf(
                Rect(
                    left = (left - obstaclePaddingPx).coerceAtLeast(restingRootOrigin.x),
                    top = (top - obstaclePaddingPx).coerceAtLeast(restingRootOrigin.y),
                    right = left + bottomBarSize.width + obstaclePaddingPx,
                    bottom = top + bottomBarSize.height + obstaclePaddingPx,
                )
            )
        }
    }
    val activeObstacles = if (showDesc) panelObstacle else restingBottomBarObstacle

    LaunchedEffect(activeObstacles) {
        latestOnAvoidanceObstaclesChanged(activeObstacles)
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
            feedbackHeight = 88.dp,
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

    val bottomBarGradient = remember {
        Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Black.copy(0.95f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { if (it != rootSize) rootSize = it },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (it != bottomBarSize) bottomBarSize = it }
                .background(bottomBarGradient)
                .padding(
                    horizontal = PlayerOverlayEdgePaddingHorizontal,
                    vertical = PlayerOverlayEdgePaddingVertical
                )
        ) {
            PlayerTimelineTitle(
                programTitle, Modifier.testTag("live-title"),
            )
            Spacer(Modifier.height(8.dp))
            PlayerTimelineRow(
                positionMs, durationMs,
            ) {
                PlayerTimelineTrack(
                    positionMs ?: 0, durationMs ?: 0,
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
