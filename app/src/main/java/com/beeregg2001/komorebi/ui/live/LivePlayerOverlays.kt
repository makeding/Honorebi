package com.beeregg2001.komorebi.ui.live

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
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.player.PlayerOverlayEdgePaddingHorizontal
import com.beeregg2001.komorebi.ui.player.PlayerOverlayEdgePaddingVertical
import com.beeregg2001.komorebi.ui.player.PlayerLCropOverlay
import com.beeregg2001.komorebi.ui.player.liveChannelNumberLabel
import com.beeregg2001.komorebi.ui.player.liveChannelTitle
import com.beeregg2001.komorebi.ui.subtitle.rememberCaptionTextBounds
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
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
    timeFormatSetting: String = "24H"
) {
    var currentTime by remember { mutableStateOf("") }

    val displaySdf = remember(timeFormatSetting) {
        if (timeFormatSetting == "12H") SimpleDateFormat("a h:mm", Locale.getDefault())
        else SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = displaySdf.format(Date())
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(PlayerOverlayEdgePaddingHorizontal),
        contentAlignment = Alignment.TopEnd
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.beeregg2001.komorebi.ui.player.PlayerChannelLogo(
                logoUrl, channel.name, shouldCropLogo, Modifier.size(56.dp, 32.dp)
            )
            Spacer(Modifier.width(16.dp))
            liveChannelNumberLabel(channel)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(20.dp))
            }
            Text(
                text = currentTime,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                color = Color.White
            )
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
) {
    val program = channel.programPresent
    val imageLoader = rememberChannelLogoImageLoader()
    var logoLoaded by remember(logoUrl) { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault()) }
    val displaySdf = remember(timeFormatSetting) {
        if (timeFormatSetting == "12H") SimpleDateFormat("a h:mm", Locale.getDefault())
        else SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    var progress by remember { mutableFloatStateOf(-1f) }
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
        showDesc, isRecording, progress >= 0f, restingRootOrigin, overlayRootOrigin, animatedBounds.toMap()
    ) {
        val currentBounds = animatedBounds.filterKeys { key ->
            (isRecording || key != "recording-indicator") &&
                (progress >= 0f || (key != "program-progress" && !key.startsWith("start-time-") && !key.startsWith("end-time-")))
        }.values.map { bounds ->
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

    LaunchedEffect(program) {
        if (program != null && !program.startTime.isNullOrEmpty() && !program.endTime.isNullOrEmpty()) {
            val startMs = sdf.parse(program.startTime)?.time ?: 0L
            val endMs = sdf.parse(program.endTime)?.time ?: 0L
            val total = endMs - startMs
            if (total > 0) {
                while (System.currentTimeMillis() < endMs) {
                    progress =
                        ((System.currentTimeMillis() - startMs).toFloat() / total).coerceIn(0f, 1f)
                    delay(5000)
                }
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val start = runCatching { program?.startTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } }.getOrNull()
                        val end = runCatching { program?.endTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } }.getOrNull()
                        Text(start ?: "時刻不明", color = Color.White.copy(0.6f))
                        Box(Modifier.weight(1f).padding(horizontal = 20.dp).height(4.dp)
                            .background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(Color.White, RoundedCornerShape(2.dp)))
                        }
                        Text(end ?: "時刻不明", color = Color.White.copy(0.6f))
                    }
                    Text("上下キーでスクロール  ·  戻るで閉じる", color = Color.White.copy(0.7f),
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    imageLoader = imageLoader,
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp, 45.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (logoLoaded) Color.White else Color.Transparent)
                        .onGloballyPositioned { coordinates ->
                            recordAvoidanceBounds("channel-logo", Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
                        },
                    // ★ 修正: フラグに基づいてスケールを変更
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                    onSuccess = { logoLoaded = true },
                    onError = { logoLoaded = false }
                )
                Spacer(Modifier.width(24.dp))
                val channelNameBounds = rememberCaptionTextBounds { bounds ->
                    recordTextBounds("channel-name", bounds)
                }
                Text(
                    text = liveChannelTitle(channel),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(0.8f),
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned(channelNameBounds::onPositioned),
                    onTextLayout = channelNameBounds::onTextLayout,
                )

                if (isRecording) {
                    RecordingIndicator(
                        onBoundsChanged = { bounds ->
                            recordAvoidanceBounds("recording-indicator", bounds)
                        },
                    )
                }
            }
            val programTitleBounds = rememberCaptionTextBounds { bounds ->
                recordTextBounds("program-title", bounds)
            }
            Text(
                text = programTitle,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = Color.White,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .onGloballyPositioned(programTitleBounds::onPositioned),
                onTextLayout = programTitleBounds::onTextLayout,
            )

            if (progress >= 0f) {
                val start =
                    program?.startTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } ?: ""
                val end =
                    program?.endTime?.let { sdf.parse(it) }?.let { displaySdf.format(it) } ?: ""
                val startTimeBounds = rememberCaptionTextBounds { bounds ->
                    recordTextBounds("start-time", bounds)
                }
                val endTimeBounds = rememberCaptionTextBounds { bounds ->
                    recordTextBounds("end-time", bounds)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = start,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f),
                        modifier = Modifier.onGloballyPositioned(startTimeBounds::onPositioned),
                        onTextLayout = startTimeBounds::onTextLayout,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 20.dp)
                            .height(4.dp)
                            .background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))
                            .onGloballyPositioned { coordinates ->
                                recordAvoidanceBounds("program-progress", Rect(coordinates.positionInRoot(), Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                    }
                    Text(
                        text = end,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(0.5f),
                        modifier = Modifier.onGloballyPositioned(endTimeBounds::onPositioned),
                        onTextLayout = endTimeBounds::onTextLayout,
                    )
                }
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
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF2B1B1B)),
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
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                if (onCheckCapabilities != null) {
                    Button(
                        onClick = onCheckCapabilities,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
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
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(AppStrings.BUTTON_BACK)
                    }
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
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
