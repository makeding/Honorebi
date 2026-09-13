@file:kotlin.OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.player.PlaybackMediaInfo
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerControls(
    mediaInfo: PlaybackMediaInfo,
    capabilities: PlaybackUiCapabilities = PlaybackUiCapabilities.Recorded,
    timeFormat: String,
    allComments: List<ArchivedComment>,
    tiledThumbnailUrl: String?,
    isVisible: Boolean,
    isSeekingPreviewVisible: Boolean,
    isModernUi: Boolean,
    isPlaying: Boolean,
    hasChapters: Boolean,
    externalChapters: List<ChapterInfo> = emptyList(),
    initialPositionMs: Long,
    totalDurationMs: Long,
    initialBufferedPositionMs: Long,
    displayPositionMsProvider: () -> Long,
    displayBufferedPositionMsProvider: () -> Long,
    controlsFocusRequester: FocusRequester,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekRequested: (Long) -> Unit, // ★ 追加: シークバーでのシーク用コールバック
    onSkipPreviousChapter: () -> Unit = {},
    onSkipNextChapter: () -> Unit = {},
    canOpenKeyframeGrid: Boolean = false,
    onKeyframeGridToggle: () -> Unit = {},
    onChapterListToggle: () -> Unit,
    onInfoToggle: () -> Unit,
    onSettingsToggle: () -> Unit,
    onAvoidanceObstaclesChanged: (List<Rect>) -> Unit = {},
    infoFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val loader = remember { TileSheetLoader(context) }
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }
    val avoidanceBounds = remember { mutableStateMapOf<String, Rect>() }
    val obstaclePaddingPx = with(density) { 8.dp.toPx() }
    val recordAvoidanceBounds: (String, Rect) -> Unit = { key, bounds ->
        avoidanceBounds[key] = Rect(
            left = bounds.left - obstaclePaddingPx,
            top = bounds.top - obstaclePaddingPx,
            right = bounds.right + obstaclePaddingPx,
            bottom = bounds.bottom + obstaclePaddingPx,
        )
    }

    val buttonObstacleKeys = remember {
        setOf(
            "info", "chapter-list", "keyframe-grid", "previous-chapter", "seek-back",
            "play-pause", "seek-forward", "next-chapter", "settings",
        )
    }
    LaunchedEffect(isVisible, isModernUi, allComments.isNotEmpty(), avoidanceBounds.toMap()) {
        val currentBounds = avoidanceBounds.filterKeys { key ->
            (isModernUi || key !in buttonObstacleKeys) &&
                (allComments.isNotEmpty() || key != "comment-heatmap")
        }.values.toList()
        onAvoidanceObstaclesChanged(if (isVisible) currentBounds else emptyList())
    }

    DisposableEffect(Unit) { onDispose { loader.release() } }

    val displayProgress = rememberRecordedControlsDisplayProgress(
        isVisible = isVisible,
        initialPositionMs = initialPositionMs,
        initialBufferedPositionMs = initialBufferedPositionMs,
        positionMs = displayPositionMsProvider,
        bufferedPositionMs = displayBufferedPositionMsProvider,
    )

    val tileInfo = mediaInfo.thumbnailInfo?.tile
    val tileColumns = tileInfo?.columnCount ?: 1
    val tileInterval = tileInfo?.intervalSec ?: 10.0
    val tileWidth = tileInfo?.tileWidth ?: 320
    val tileHeight = tileInfo?.tileHeight ?: 180

    var isSeekBarFocused by remember { mutableStateOf(false) }
    val trackHeight by animateDpAsState(if (isSeekBarFocused) 8.dp else 6.dp, label = "trackHeight")
    val playHeadSize by animateDpAsState(
        if (isSeekBarFocused) 16.dp else 12.dp,
        label = "playHeadSize"
    )
    val playHeadVerticalOffset = (playHeadSize - trackHeight) / 2
    val graphHeight = 48.dp
    val chapterControlsEnabled = capabilities.chapters && hasChapters
    val keyframeGridEnabled = capabilities.thumbnailGrid && canOpenKeyframeGrid
    LaunchedEffect(isVisible, isModernUi) {
        if (isVisible && isModernUi) {
            delay(100)
            try {
                if (!isSeekBarFocused) {
                    controlsFocusRequester.requestFocus()
                }
            } catch (e: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = if (isVisible) 1f else 0f
                translationY = if (isVisible) 0f else 80f
            }
            .focusProperties { canFocus = isVisible }
    ) {
        RecordedSeekingPreview(
            visible = isSeekingPreviewVisible && isModernUi,
            controlsHeight = controlsHeight,
            displayProgress = displayProgress,
            totalDurationMs = totalDurationMs,
            tiledThumbnailUrl = tiledThumbnailUrl,
            tileColumns = tileColumns,
            tileInterval = tileInterval,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            loader = loader,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isModernUi) Modifier
                        .focusGroup()
                        .focusRestorer() else Modifier
                )
                // ★ 修正: onPreviewKeyEvent から onKeyEvent（ボトムアップ型）に変更
                // これにより、最優先される子要素（早送り/巻き戻しボタン）側で消費されなかった
                // 長押しイベントのみがここへ上昇してキャッチされ、MainRootへのイベントのすり抜けを完璧にブロックします。
                .onKeyEvent { event ->
                    if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount > 0) {
                            return@onKeyEvent true
                        }
                    }
                    false
                },
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        controlsHeight = with(density) { it.size.height.toDp() }
                    }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.95f))))
                    .testTag("recorded-controls")
                    .padding(horizontal = 48.dp, vertical = 40.dp)
            ) {
                var titleOrigin by remember { mutableStateOf(Offset.Zero) }
                var titleGlyphBounds by remember { mutableStateOf<Rect?>(null) }
                LaunchedEffect(titleOrigin, titleGlyphBounds) {
                    titleGlyphBounds?.let { glyphBounds ->
                        recordAvoidanceBounds("title", glyphBounds.translate(titleOrigin))
                    }
                }
                Text(
                    text = mediaInfo.title.ifBlank { "タイトルなし" },
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth().height(24.dp).testTag("recorded-title")
                        .onGloballyPositioned { coordinates ->
                            titleOrigin = coordinates.positionInRoot()
                        }
                        .basicMarquee(
                            iterations = if (isVisible) Int.MAX_VALUE else 0,
                            initialDelayMillis = 2000,
                        ),
                    onTextLayout = { layout ->
                        titleGlyphBounds = if (layout.lineCount == 0) {
                            null
                        } else {
                            Rect(
                                layout.getLineLeft(0),
                                layout.getLineTop(0),
                                layout.getLineRight(0),
                                layout.getLineBottom(0),
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))

                RecordedControlsProgressRow(
                    displayProgress = displayProgress,
                    displayPositionMsProvider = displayPositionMsProvider,
                    totalDurationMs = totalDurationMs,
                    allComments = allComments,
                    hasChapters = hasChapters,
                    mediaInfo = mediaInfo,
                    externalChapters = externalChapters,
                    isModernUi = isModernUi,
                    isSeekBarFocused = isSeekBarFocused,
                    onSeekBarFocusChanged = {
                        isSeekBarFocused = it
                        onSeekBarFocusChanged(it)
                    },
                    trackHeight = trackHeight,
                    playHeadSize = playHeadSize,
                    playHeadVerticalOffset = playHeadVerticalOffset,
                    graphHeight = graphHeight,
                    onSeekRequested = onSeekRequested,
                    onProgressBoundsChanged = { bounds -> recordAvoidanceBounds("progress", bounds) },
                    onElapsedTimeBoundsChanged = { bounds -> recordAvoidanceBounds("elapsed-time", bounds) },
                    onDurationBoundsChanged = { bounds -> recordAvoidanceBounds("duration", bounds) },
                    onHeatmapBoundsChanged = { bounds -> recordAvoidanceBounds("comment-heatmap", bounds) },
                )

                if (isModernUi) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OsdIconButton(
                                icon = Icons.Default.Info,
                                label = "番組詳細",
                                onClick = onInfoToggle,
                                modifier = Modifier.focusRequester(infoFocusRequester),
                                enabled = capabilities.programInfo,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("info", bounds) },
                            )
                            OsdIconButton(
                                icon = Icons.Default.FormatListBulleted,
                                label = "チャプター",
                                onClick = onChapterListToggle,
                                enabled = chapterControlsEnabled,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("chapter-list", bounds) },
                            )
                            OsdIconButton(
                                icon = Icons.Default.GridView,
                                label = "サムネイル",
                                onClick = onKeyframeGridToggle,
                                enabled = keyframeGridEnabled,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("keyframe-grid", bounds) },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OsdIconButton(
                                icon = Icons.Default.SkipPrevious,
                                label = "前のチャプター",
                                onClick = onSkipPreviousChapter,
                                buttonSize = 48.dp,
                                iconSize = 24.dp,
                                allowContinuousPress = true,
                                enabled = chapterControlsEnabled,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("previous-chapter", bounds) },
                            )

                            OsdIconButton(
                                icon = Icons.Default.FastRewind,
                                label = "-10秒",
                                onClick = onSeekBack,
                                buttonSize = 56.dp,
                                iconSize = 32.dp,
                                allowContinuousPress = true,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("seek-back", bounds) },
                            )
                            OsdIconButton(
                                icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = "再生/一時停止",
                                onClick = onPlayPauseToggle,
                                buttonSize = 64.dp,
                                iconSize = 36.dp,
                                isPrimary = true,
                                modifier = Modifier.focusRequester(controlsFocusRequester),
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("play-pause", bounds) },
                            )
                            OsdIconButton(
                                icon = Icons.Default.FastForward,
                                label = "+30秒",
                                onClick = onSeekForward,
                                buttonSize = 56.dp,
                                iconSize = 32.dp,
                                allowContinuousPress = true,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("seek-forward", bounds) },
                            )

                            OsdIconButton(
                                icon = Icons.Default.SkipNext,
                                label = "次のチャプター",
                                onClick = onSkipNextChapter,
                                buttonSize = 48.dp,
                                iconSize = 24.dp,
                                allowContinuousPress = true,
                                enabled = chapterControlsEnabled,
                                onBoundsChanged = { bounds -> recordAvoidanceBounds("next-chapter", bounds) },
                            )
                        }

                        OsdIconButton(
                            icon = Icons.Default.Settings,
                            label = "設定",
                            onClick = onSettingsToggle,
                            onBoundsChanged = { bounds -> recordAvoidanceBounds("settings", bounds) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.RecordedSeekingPreview(
    visible: Boolean,
    controlsHeight: Dp,
    displayProgress: State<RecordedControlsDisplayProgress>,
    totalDurationMs: Long,
    tiledThumbnailUrl: String?,
    tileColumns: Int,
    tileInterval: Double,
    tileWidth: Int,
    tileHeight: Int,
    loader: TileSheetLoader,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomStart)
            .padding(start = 128.dp, end = 128.dp, bottom = controlsHeight),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val displayPositionMs = displayProgress.value.positionMs
        val colors = KomorebiTheme.colors
        val progress = if (totalDurationMs > 0) {
            (displayPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
        } else 0f
        val horizontalBias = (progress * 2f) - 1f
        var thumbnailRegion by remember { mutableStateOf<TileSheetRegion?>(null) }
        val timeSec = displayPositionMs / 1000
        val tileIndex = floor(timeSec / tileInterval).toInt()
        val col = tileIndex % tileColumns
        val row = tileIndex / tileColumns

        LaunchedEffect(tiledThumbnailUrl, col, row) {
            if (tiledThumbnailUrl.isNullOrBlank()) return@LaunchedEffect
            loader.loadRegion(tiledThumbnailUrl, col, row, tileWidth, tileHeight)?.let {
                thumbnailRegion = it
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp).padding(bottom = 12.dp),
        ) {
            val previewModifier = Modifier
                .align(androidx.compose.ui.BiasAlignment(horizontalBias, 1f))
            if (thumbnailRegion != null) {
                Box(
                    modifier = previewModifier
                        .size(144.dp, 81.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.DarkGray.copy(alpha = 0.8f))
                        .border(2.dp, colors.accent, RoundedCornerShape(6.dp)),
                ) {
                    TileSheetRegionImage(
                        region = thumbnailRegion!!,
                        contentDescription = "Seek Preview",
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = formatMillisToTime(displayPositionMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .background(
                                Color.Black.copy(alpha = 0.7f),
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            } else {
                Text(
                    text = formatMillisToTime(displayPositionMs),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = previewModifier
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                        .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordedControlsProgressRow(
    displayProgress: State<RecordedControlsDisplayProgress>,
    displayPositionMsProvider: () -> Long,
    totalDurationMs: Long,
    allComments: List<ArchivedComment>,
    hasChapters: Boolean,
    mediaInfo: PlaybackMediaInfo,
    externalChapters: List<ChapterInfo>,
    isModernUi: Boolean,
    isSeekBarFocused: Boolean,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    trackHeight: Dp,
    playHeadSize: Dp,
    playHeadVerticalOffset: Dp,
    graphHeight: Dp,
    onSeekRequested: (Long) -> Unit,
    onProgressBoundsChanged: (Rect) -> Unit,
    onElapsedTimeBoundsChanged: (Rect) -> Unit,
    onDurationBoundsChanged: (Rect) -> Unit,
    onHeatmapBoundsChanged: (Rect) -> Unit,
) {
    val displayPositionMs = displayProgress.value.positionMs
    val bufferedPositionMs = displayProgress.value.bufferedPositionMs
    val colors = KomorebiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatMillisToTime(displayPositionMs),
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp).testTag("playback-time")
                .onGloballyPositioned { coordinates -> onElapsedTimeBoundsChanged(coordinates.boundsInRoot()) },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Box(
            modifier = Modifier.weight(1f).height(graphHeight).testTag("playback-track")
                .onFocusChanged { onSeekBarFocusChanged(it.isFocused) }
                .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                .focusable(isModernUi)
                .onKeyEvent { event ->
                    if (isSeekBarFocused && event.type == KeyEventType.KeyDown) {
                        val step = (10_000L * (1 + event.nativeKeyEvent.repeatCount / 5)).coerceAtMost(60_000L)
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onSeekRequested(
                                    resolveRecordedControlsSeekTarget(
                                        latestPositionMs = displayPositionMsProvider(),
                                        stepMs = step,
                                        totalDurationMs = totalDurationMs,
                                        forward = false,
                                    )
                                )
                                return@onKeyEvent true
                            }
                            Key.DirectionRight -> {
                                onSeekRequested(
                                    resolveRecordedControlsSeekTarget(
                                        latestPositionMs = displayPositionMsProvider(),
                                        stepMs = step,
                                        totalDurationMs = totalDurationMs,
                                        forward = true,
                                    )
                                )
                                return@onKeyEvent true
                            }
                        }
                    }
                    false
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (allComments.isNotEmpty() && totalDurationMs > 0) {
                CommentMomentumGraph(
                    comments = allComments,
                    totalDurationMs = totalDurationMs,
                    currentPositionMs = displayPositionMs,
                    playedColor = colors.accent.copy(alpha = 0.6f),
                    unplayedColor = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().fillMaxHeight().align(Alignment.BottomStart)
                        .onGloballyPositioned { coordinates -> onHeatmapBoundsChanged(coordinates.boundsInRoot()) },
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(trackHeight).align(Alignment.BottomStart)
                    .onGloballyPositioned { coordinates -> onProgressBoundsChanged(coordinates.boundsInRoot()) }
                    .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
            )
            if (hasChapters && totalDurationMs > 0L) {
                val apiCmSections = mediaInfo.cmSections
                val renderSections = if (apiCmSections.isNotEmpty()) {
                    apiCmSections.map { ChapterInfo((it.startTime * 1000).toLong(), (it.endTime * 1000).toLong(), isCm = true) }
                } else externalChapters
                Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight).align(Alignment.BottomStart)) {
                    renderSections.filter { it.isCm && !it.isMarkerOnly }.forEach { section ->
                        val start = (section.startTimeMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) * size.width
                        val end = (section.endTimeMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) * size.width
                        drawRect(Color.Red.copy(alpha = 0.5f), Offset(start, 0f), Size(end - start, size.height))
                    }
                    renderSections.forEach { section ->
                        val start = (section.startTimeMs.toFloat() / totalDurationMs).coerceIn(0f, 1f) * size.width
                        drawRect(Color.White.copy(alpha = 0.8f), Offset(start, 0f), Size(2.dp.toPx(), size.height))
                    }
                }
            }
            val duration = totalDurationMs.coerceAtLeast(1L)
            val bufferedProgress = (bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f)
            if (bufferedProgress > 0f) {
                Box(
                    modifier = Modifier.fillMaxWidth(bufferedProgress).height(trackHeight).align(Alignment.BottomStart)
                        .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                )
            }
            val playProgress = if (totalDurationMs > 0) {
                (displayPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
            } else 0f
            if (playProgress > 0f) {
                Box(
                    modifier = Modifier.fillMaxWidth(playProgress).height(trackHeight).align(Alignment.BottomStart)
                        .background(
                            if (isSeekBarFocused) colors.accent else colors.accent.copy(alpha = 0.8f),
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth(playProgress).fillMaxHeight(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    modifier = Modifier.offset(x = playHeadSize / 2, y = playHeadVerticalOffset)
                        .size(playHeadSize)
                        .background(if (isSeekBarFocused) colors.accent else Color.White, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = formatMillisToTime(totalDurationMs),
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(64.dp).testTag("playback-duration")
                .onGloballyPositioned { coordinates -> onDurationBoundsChanged(coordinates.boundsInRoot()) },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OsdIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    isPrimary: Boolean = false,
    allowContinuousPress: Boolean = false,
    enabled: Boolean = true,
    onBoundsChanged: (Rect) -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    var lastRepeatTime by remember { mutableLongStateOf(0L) }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) colors.accent else Color.White.copy(alpha = 0.1f),
            focusedContainerColor = Color.White,
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            focusedContentColor = Color.Black,
        ),
        modifier = modifier
            .size(buttonSize)
            .onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInRoot()) }
            // ★ 恩恵のトップダウン処理: SurfaceがKeyDownイベントを内部消費してアニメーションする「直前」に
            // 連打イベントをインターセプトし、onClick を 200ms 間隔で連続発火させます。
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) {
                        if (enabled && allowContinuousPress) {
                            val now = System.currentTimeMillis()
                            if (now - lastRepeatTime > 200) {
                                onClick()
                                lastRepeatTime = now
                            }
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    }
}

private fun formatMillisToTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(
        Locale.getDefault(),
        "%d:%02d:%02d",
        hours,
        minutes,
        seconds
    )
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun CommentMomentumGraph(
    comments: List<ArchivedComment>,
    totalDurationMs: Long,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    bucketCount: Int = 150,
    playedColor: Color = Color.White.copy(alpha = 0.8f),
    unplayedColor: Color = Color.White.copy(alpha = 0.3f)
) {
    val momentumData = remember(comments, totalDurationMs, bucketCount) {
        if (totalDurationMs <= 0 || comments.isEmpty()) return@remember List(bucketCount) { 0f }

        val buckets = IntArray(bucketCount)
        val bucketDurationMs = totalDurationMs / bucketCount.toFloat()

        comments.forEach { comment ->
            val commentPosMs = (comment.time * 1000).toLong()
            val index = (commentPosMs / bucketDurationMs).toInt().coerceIn(0, bucketCount - 1)
            buckets[index]++
        }

        val maxComments = buckets.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
        buckets.map {
            (it.toFloat() / maxComments).pow(1.5f)
        }
    }

    val currentRatio =
        if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs else 0f

    Canvas(modifier = modifier) {
        val stepX = size.width / (bucketCount - 1).coerceAtLeast(1)
        val maxBarHeight = size.height

        val wavePath = Path().apply {
            moveTo(0f, size.height)
            val points = momentumData.mapIndexed { index, value ->
                val x = index * stepX
                val y = size.height - (value * maxBarHeight).coerceAtLeast(2f)
                Offset(x, y)
            }
            lineTo(points.first().x, points.first().y)
            var currentPoint = points.first()
            for (i in 1 until points.size) {
                val nextPoint = points[i]
                val midPoint = Offset(
                    (currentPoint.x + nextPoint.x) / 2f,
                    (currentPoint.y + nextPoint.y) / 2f
                )
                if (i == 1) {
                    lineTo(midPoint.x, midPoint.y)
                } else {
                    quadraticBezierTo(currentPoint.x, currentPoint.y, midPoint.x, midPoint.y)
                }
                currentPoint = nextPoint
            }
            lineTo(points.last().x, points.last().y)
            lineTo(size.width, size.height)
            close()
        }

        drawPath(path = wavePath, color = unplayedColor)
        clipRect(right = currentRatio * size.width) {
            drawPath(path = wavePath, color = playedColor)
        }
    }
}
