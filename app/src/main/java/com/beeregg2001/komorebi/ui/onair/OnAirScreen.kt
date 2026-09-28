@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")

// HonomiTV の放送中グリッドに合わせた曜日ごとのカラー。
private val weekdayColors = listOf(
    Color(0xFFE76F51), // 月
    Color(0xFFE9A23B), // 火
    Color(0xFF84A83F), // 水
    Color(0xFF3AA889), // 木
    Color(0xFF438AC7), // 金
    Color(0xFF646FC1), // 土
    Color(0xFFFF69B4), // 日
)

// カードが小さくなりすぎないよう、1曜日ぶんの列幅を固定して横スクロールさせる。
private val WEEK_COLUMN_WIDTH = 180.dp
private val WEEK_COLUMN_SPACING = 12.dp
private val WEEK_FOCUS_GUTTER = 8.dp
private val WEEK_HEADER_HEIGHT = 34.dp
private val WEEK_CARD_SPACING = 10.dp

@Composable
fun OnAirScreen(
    konomiIp: String,
    konomiPort: String,
    timeFormat: String,
    onProgramClick: (RecordedProgram) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    initialFocusRequester: FocusRequester,
    isReturningFromPlayer: Boolean,
    onReturnFocusConsumed: () -> Unit,
    viewModel: OnAirViewModel = hiltViewModel(),
    topFocusRequester: FocusRequester = FocusRequester.Default,
    showHeadline: Boolean = true,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cardFocus = remember { mutableMapOf<Int, FocusRequester>() }
    state.series.forEach { cardFocus.getOrPut(it.id) { FocusRequester() } }
    var pageFocused by remember { mutableStateOf(false) }
    var returnCell by remember { mutableStateOf<String?>(null) }
    var restoreSeries by remember { mutableStateOf<Int?>(null) }
    val expanded = state.expanded?.takeIf { detail -> state.series.any { it.id == detail.seriesId } }

    // HonomiTV と同じ週間グリッド: 曜日ごとの縦リストを横に並べ、全体をまとめて縦横スクロールする。
    val byWeekday = remember(state.series) {
        (0..6).map { day -> state.series.filter { it.weekday == day } }
    }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val initialDay = remember(byWeekday) {
        val preferred = state.selectedWeekday
        if (byWeekday.getOrNull(preferred)?.isNotEmpty() == true) preferred
        else byWeekday.indexOfFirst { it.isNotEmpty() }
    }
    val initialSeriesId = initialDay.takeIf { it in 0..6 }?.let { byWeekday[it].firstOrNull()?.id }

    val columnWidthPx = with(density) { WEEK_COLUMN_WIDTH.roundToPx() }
    val columnSpacingPx = with(density) { WEEK_COLUMN_SPACING.roundToPx() }
    val focusGutterPx = with(density) { WEEK_FOCUS_GUTTER.roundToPx() }
    val focusInsetPx = with(density) { 8.dp.roundToPx() }
    // Keep the fixed header and horizontally scrolling card row on the exact same measured width.
    val weekContentWidth = WEEK_COLUMN_WIDTH * weekdays.size + WEEK_COLUMN_SPACING * (weekdays.size - 1)
    val cardHeightPx = (columnWidthPx * 10f / 16f).toInt()
    val cardSpacingPx = with(density) { WEEK_CARD_SPACING.roundToPx() }

    fun requesterAt(day: Int, index: Int): FocusRequester? =
        byWeekday.getOrNull(day)?.getOrNull(index)?.let { cardFocus[it.id] }

    fun neighborDay(day: Int, step: Int): Int {
        var d = day + step
        while (d in 0..6) {
            if (byWeekday[d].isNotEmpty()) return d
            d += step
        }
        return day
    }

    fun cardLeft(day: Int, index: Int): FocusRequester =
        if (day == 0) FocusRequester.Cancel
        else requesterAt(neighborDay(day, -1), index.coerceIn(0, (byWeekday[neighborDay(day, -1)].size - 1).coerceAtLeast(0)))
            ?: FocusRequester.Cancel

    fun cardRight(day: Int, index: Int): FocusRequester =
        if (day == 6) FocusRequester.Cancel
        else requesterAt(neighborDay(day, 1), index.coerceIn(0, (byWeekday[neighborDay(day, 1)].size - 1).coerceAtLeast(0)))
            ?: FocusRequester.Cancel

    fun cardUp(day: Int, index: Int): FocusRequester =
        if (index > 0) requesterAt(day, index - 1) ?: topFocusRequester else topFocusRequester

    fun cardDown(day: Int, index: Int): FocusRequester =
        if (index < byWeekday[day].lastIndex) requesterAt(day, index + 1) ?: FocusRequester.Cancel
        else FocusRequester.Cancel

    // Keep the focused card visible while preserving the current viewport whenever it already fits.
    fun scrollToCard(day: Int, index: Int) {
        scope.launch {
            val columnStart = focusGutterPx + day * (columnWidthPx + columnSpacingPx)
            val visibleWidth = hScroll.viewportSize.takeIf { it > 0 } ?: columnWidthPx
            val horizontalTarget = when {
                columnStart - focusInsetPx < hScroll.value -> columnStart - focusInsetPx
                columnStart + columnWidthPx + focusInsetPx > hScroll.value + visibleWidth ->
                    columnStart + columnWidthPx + focusInsetPx - visibleWidth
                else -> hScroll.value
            }.coerceIn(0, hScroll.maxValue)

            val cardStart = index * (cardHeightPx + cardSpacingPx)
            val visibleHeight = vScroll.viewportSize.takeIf { it > 0 } ?: cardHeightPx
            val verticalTarget = when {
                cardStart < vScroll.value -> cardStart
                cardStart + cardHeightPx > vScroll.value + visibleHeight ->
                    cardStart + cardHeightPx - visibleHeight
                else -> vScroll.value
            }.coerceIn(0, vScroll.maxValue)

            if (horizontalTarget != hScroll.value) hScroll.animateScrollTo(horizontalTarget)
            if (verticalTarget != vScroll.value) vScroll.animateScrollTo(verticalTarget)
        }
    }

    // フォーカスがカードへ移るたびに、その列・行が見える位置へスクロールする。
    LaunchedEffect(state.focusedSeriesId) {
        val id = state.focusedSeriesId ?: return@LaunchedEffect
        val day = byWeekday.indexOfFirst { list -> list.any { it.id == id } }
        if (day < 0) return@LaunchedEffect
        val index = byWeekday[day].indexOfFirst { it.id == id }
        scrollToCard(day, index)
    }

    fun closeDetail() {
        val id = expanded?.seriesId ?: return
        viewModel.collapseSeries()
        restoreSeries = id
    }
    fun pageBack() {
        when {
            expanded != null -> closeDetail()
            else -> onBack()
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onEnterPage()
        onDispose { viewModel.onLeavePage() }
    }

    LaunchedEffect(initialSeriesId, isReturningFromPlayer) {
        if (isReturningFromPlayer) return@LaunchedEffect
        val day = initialDay
        if (day in 0..6 && initialSeriesId != null) {
            scrollToCard(day, 0)
            initialFocusRequester.safeRequestFocusWithRetry("OnAirInitialFocus")
        } else {
            topFocusRequester.safeRequestFocusWithRetry("OnAirInitialFallback")
        }
    }

    LaunchedEffect(restoreSeries) {
        val id = restoreSeries ?: return@LaunchedEffect
        val day = byWeekday.indexOfFirst { list -> list.any { it.id == id } }
        if (day >= 0) {
            val index = byWeekday[day].indexOfFirst { it.id == id }
            viewModel.selectWeekday(day)
            scrollToCard(day, index)
            cardFocus[id]?.safeRequestFocusWithRetry("OnAirCardRestore")
        } else {
            topFocusRequester.safeRequestFocusWithRetry("OnAirCardRestoreFallback")
        }
        restoreSeries = null
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (!isReturningFromPlayer) return@LaunchedEffect
        val target = viewModel.consumeReturnFocus()
        when {
            expanded != null -> if (target.episodeCellKey != null) {
                returnCell = target.episodeCellKey
            } else {
                onReturnFocusConsumed()
            }
            target.seriesId != null -> {
                restoreSeries = target.seriesId
                onReturnFocusConsumed()
            }
            else -> {
                initialFocusRequester.safeRequestFocusWithRetry("OnAirReturnFocus")
                onReturnFocusConsumed()
            }
        }
    }

    BackHandler(enabled = pageFocused) { pageBack() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 10.dp)
            .onFocusChanged { pageFocused = it.hasFocus }.focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    if (expanded != null) false else { pageBack(); true }
                } else false
            },
    ) {
        AnimatedContent(
            targetState = expanded,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentKey = { it?.seriesId ?: -1 },
            transitionSpec = {
                val direction = if (targetState == null) -1 else 1
                (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { direction * it / 24 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -direction * it / 24 })
            },
            label = "onair-list-detail",
        ) { detailContent ->
            if (detailContent != null) {
                OnAirDetail(
                    series = state.series.first { it.id == detailContent.seriesId },
                    detail = detailContent,
                    state = state,
                    viewModel = viewModel,
                    ip = konomiIp, port = konomiPort,
                    returnCell = returnCell,
                    onRestored = { returnCell = null; onReturnFocusConsumed() },
                    onProgram = onProgramClick,
                    onBack = ::closeDetail,
                    modifier = Modifier.fillMaxSize().testTag("onair-details"),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (showHeadline) {
                            Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                                Text("放送中", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    val isLoading = state.listStatus == OnAirLoadState.Loading || state.listStatus == OnAirLoadState.Idle
                    when {
                        !state.backendSupported -> OnAirEmpty(
                            "このバックエンドは「放送中」に対応していません",
                            "HonomiTV の接続を設定してください。 [ON_AIR_UNSUPPORTED]", "接続設定を開く", onSettings,
                            modifier = Modifier.weight(1f),
                        )
                        state.series.isEmpty() && state.listStatus is OnAirLoadState.Error -> {
                            val error = state.listStatus as OnAirLoadState.Error
                            OnAirEmpty(
                                "放送中を読み込めませんでした", "${error.message}\n[${error.code}]", "再試行", viewModel::retryList,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val viewportWidth = maxWidth
                            // Header and cards live under one horizontal scroll state, so their x positions cannot drift.
                            Column(
                                Modifier.fillMaxSize()
                                    .horizontalScroll(hScroll)
                                    .testTag("onair-grid"),
                            ) {
                                Column(Modifier.fillMaxHeight().padding(horizontal = WEEK_FOCUS_GUTTER)) {
                                    Row(
                                        Modifier.requiredWidth(weekContentWidth).height(WEEK_HEADER_HEIGHT),
                                        horizontalArrangement = Arrangement.spacedBy(WEEK_COLUMN_SPACING),
                                    ) {
                                        (0..6).forEach { day ->
                                            DayHeader(
                                                label = weekdays[day],
                                                count = byWeekday[day].size,
                                                color = weekdayColors[day % weekdayColors.size],
                                                highlighted = day == state.selectedWeekday,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (isLoading && state.series.isEmpty()) {
                                        // Keep the card area quiet until real content arrives.
                                        Spacer(Modifier.width(viewportWidth).weight(1f))
                                    } else {
                                        // Only the card rows scroll vertically; weekday labels remain pinned above.
                                        Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                                            Row(
                                                Modifier.requiredWidth(weekContentWidth),
                                                horizontalArrangement = Arrangement.spacedBy(WEEK_COLUMN_SPACING),
                                            ) {
                                                (0..6).forEach { day ->
                                                    val daySeries = byWeekday[day]
                                                    Column(Modifier.width(WEEK_COLUMN_WIDTH)) {
                                                        if (daySeries.isEmpty()) {
                                                            Box(
                                                                Modifier.fillMaxWidth().height(80.dp),
                                                                contentAlignment = Alignment.TopCenter,
                                                            ) {
                                                                Text("放送中なし", color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                                                            }
                                                        } else {
                                                            daySeries.forEachIndexed { index, series ->
                                                                OnAirCard(
                                                                    series = series, selected = false,
                                                                    ip = konomiIp, port = konomiPort, timeFormat = timeFormat,
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .focusRequester(cardFocus.getValue(series.id))
                                                                        .then(
                                                                            if (series.id == initialSeriesId) Modifier.focusRequester(initialFocusRequester)
                                                                            else Modifier
                                                                        )
                                                                        .testTag("onair-series-${series.id}")
                                                                        .onFocusChanged {
                                                                            if (it.isFocused) {
                                                                                viewModel.saveFocusedSeries(series.id)
                                                                                if (state.selectedWeekday != day) viewModel.selectWeekday(day)
                                                                            }
                                                                        }
                                                                        .focusProperties {
                                                                            left = cardLeft(day, index)
                                                                            right = cardRight(day, index)
                                                                            up = cardUp(day, index)
                                                                            down = cardDown(day, index)
                                                                        },
                                                                    onClick = { viewModel.expandSeries(series.id) },
                                                                )
                                                                if (index != daySeries.lastIndex) Spacer(Modifier.height(WEEK_CARD_SPACING))
                    }
                }
            }
        }
    }
}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, count: Int, color: Color, highlighted: Boolean) {
    Row(
        Modifier
            .width(WEEK_COLUMN_WIDTH)
            .fillMaxHeight()
            .background(color.copy(alpha = if (highlighted) 1f else 0.72f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text("${count}件", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
    }
}

@Composable
internal fun OnAirAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 40.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.accent.copy(alpha = .3f) else colors.surface,
            contentColor = colors.textPrimary, focusedContainerColor = colors.accent,
            focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.textPrimary))),
    ) { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Text(text, fontSize = 13.sp) } }
}

@Composable
internal fun OnAirEmpty(
    title: String,
    detail: String = "",
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = KomorebiTheme.colors.textPrimary)
        if (detail.isNotBlank()) Text(detail, color = KomorebiTheme.colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 10.dp))
        if (action != null) OnAirAction(action, onAction)
    }
}

@Composable
private fun OnAirCard(series: OnAirSeries, selected: Boolean, ip: String, port: String, timeFormat: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = KomorebiTheme.colors
    val logos = rememberChannelLogoImageLoader()
    val thumbId = series.thumbnailRecordedProgramIds.firstOrNull()

    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(16f / 10f),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            contentColor = Color.White,
            focusedContainerColor = colors.surface,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) colors.accent else Color.Transparent)),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent)),
        ),
    ) {
        // HonomiTV と同じく、全画面サムネイルの上に情報を重ねる（画像の上・説明の下にしない）。
        Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
            if (thumbId != null) {
                AsyncImage(
                    UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, thumbId.toString()),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "サムネイルなし",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 下端を暗くしてタイトル・メタ情報を読みやすくする（TVでも視認できる濃さ）。
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.68f to Color.Black.copy(alpha = 0.45f),
                        1f to Color.Black.copy(alpha = 0.95f),
                    )
                )
            )

            // 右上: 放送時刻
            Text(
                displayBroadcastTime(series.broadcastTime, timeFormat),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 4f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )

            // 左下: タイトル・話数 / 右下: チャンネルロゴ
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 7.dp, vertical = 6.dp)
            ) {
                Text(
                    series.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.95f), Offset(0f, 1f), 5f)),
                )
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(if (series.recordedEpisodesCount > 0) "${series.recordedEpisodesCount}話" else "話数情報なし")
                            if (series.missingEpisodesCount > 0) append("・${series.missingEpisodesCount}話未録画")
                        },
                        color = if (series.missingEpisodesCount > 0) colors.accent else Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 4f)),
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        series.channelIds.take(2).forEach { channel ->
                            AsyncImage(
                                UrlBuilder.getKonomiTvLogoUrl(ip, port, channel),
                                channel,
                                imageLoader = logos,
                                modifier = Modifier.size(26.dp, 15.dp).background(Color.White, RoundedCornerShape(3.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun displayBroadcastTime(value: String, format: String): String = runCatching {
    LocalTime.parse(value).format(DateTimeFormatter.ofPattern(if (format == "12H") "a h:mm" else "HH:mm", Locale.JAPANESE))
}.getOrDefault(value.ifBlank { "時刻未登録" })
