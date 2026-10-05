@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.ChannelLogoBadge
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")

// 日本時間固定。HonomiTV と同じく曜日・強調表示の判定をブラウザー(端末)の TZ に依存させない。
private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

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

// 部分録画・未録画など注意喚起の共有トーン（HonomiTV の warning に相当）。
internal val OnAirWarningColor = Color(0xFFFFB4AB)

// カードが小さくなりすぎないよう、1曜日ぶんの列幅を固定して横スクロールさせる。
private val WEEK_COLUMN_WIDTH = 180.dp
private val WEEK_COLUMN_SPACING = 12.dp
private val WEEK_FOCUS_GUTTER = 8.dp
private val WEEK_HEADER_HEIGHT = 34.dp
private val WEEK_CARD_SPACING = 10.dp

/**
 * HonomiTV の OnAirUtils と同じ強調表示ウィンドウ。
 * 20:00〜05:00 は夜の放送枠として固定し、昼は直近 3 時間から翌朝 05:00 までを対象にする。
 */
internal fun onAirAttentionWindow(now: ZonedDateTime): Pair<ZonedDateTime, ZonedDateTime> {
    return if (now.hour >= 20 || now.hour < 5) {
        val broadcastDate = if (now.hour < 5) now.toLocalDate().minusDays(1) else now.toLocalDate()
        val zone = now.zone
        broadcastDate.atTime(20, 0).atZone(zone) to broadcastDate.plusDays(1).atTime(5, 0).atZone(zone)
    } else {
        now.minusHours(3) to now.toLocalDate().plusDays(1).atTime(5, 0).atZone(now.zone)
    }
}

internal fun isOnAirSeriesInAttentionWindow(series: OnAirSeries, now: ZonedDateTime): Boolean {
    val (start, end) = onAirAttentionWindow(now)
    val currentWeekday = (now.dayOfWeek.value + 6) % 7
    val weekStart = now.toLocalDate().minusDays(currentWeekday.toLong())
    val hour = series.broadcastTime.substringBefore(':').toIntOrNull() ?: return false
    val minute = series.broadcastTime.split(':').getOrNull(1)?.toIntOrNull() ?: 0
    val base = weekStart.plusDays(series.weekday.toLong()).atTime(hour, minute).atZone(now.zone)
    // 日曜深夜→月曜など暦日を跨ぐ枠も照合できるよう、前後週の同じ曜日・時刻も比較する。
    return listOf(-7L, 0L, 7L).any { offset ->
        val occurrence = base.plusDays(offset)
        !occurrence.isBefore(start) && !occurrence.isAfter(end)
    }
}

/** accent の上に載せる文字色。白系 accent なら黒、それ以外は白。 */
internal fun onAccentColor(accent: Color): Color = if (accent.luminance() > 0.5f) Color.Black else Color.White

@Composable
private fun rememberJapanNow(): ZonedDateTime {
    var now by remember { mutableStateOf(ZonedDateTime.now(JST)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = ZonedDateTime.now(JST)
        }
    }
    return now
}

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
) {
    val state by viewModel.uiState.collectAsState()
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cardFocus = remember { mutableMapOf<Int, FocusRequester>() }
    state.series.forEach { cardFocus.getOrPut(it.id) { FocusRequester() } }
    // 曜日ヘッダーもフォーカス可能: 一覧の左右端・上から直接曜日へジャンプできる。
    val headerFocus = remember { mutableMapOf<Int, FocusRequester>() }
    (0..6).forEach { headerFocus.getOrPut(it) { FocusRequester() } }
    val seasonFocus = remember { FocusRequester() }
    var choosingSeason by remember { mutableStateOf(false) }
    var initialFocusPlaced by remember(konomiIp, konomiPort) { mutableStateOf(isReturningFromPlayer) }
    val nowJst = rememberJapanNow()
    val currentSeason = state.selectedSeasonId == state.currentSeasonId
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
        if (index > 0) requesterAt(day, index - 1) ?: headerFocus.getValue(day) else headerFocus.getValue(day)

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

            // The card content has an 8dp focus gutter at the start and end of the
            // vertical scroll range, so include it when calculating the visible bounds.
            val cardStart = focusGutterPx + index * (cardHeightPx + cardSpacingPx)
            val visibleHeight = vScroll.viewportSize.takeIf { it > 0 } ?: cardHeightPx
            val verticalTarget = when {
                cardStart - focusInsetPx < vScroll.value -> (cardStart - focusInsetPx).coerceAtLeast(0)
                cardStart + cardHeightPx + focusInsetPx > vScroll.value + visibleHeight ->
                    cardStart + cardHeightPx + focusInsetPx - visibleHeight
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
        if (isReturningFromPlayer || expanded != null || initialFocusPlaced || pageFocused) return@LaunchedEffect
        val day = initialDay
        if (day in 0..6 && initialSeriesId != null) {
            initialFocusPlaced = true
            scrollToCard(day, 0)
            withFrameNanos { }
            if (!pageFocused) cardFocus[initialSeriesId]?.safeRequestFocus("OnAirInitialFocus")
        } else {
            if (topFocusRequester != FocusRequester.Default) topFocusRequester.safeRequestFocus("OnAirInitialFallback")
        }
    }

    LaunchedEffect(restoreSeries) {
        val id = restoreSeries ?: return@LaunchedEffect
        val day = byWeekday.indexOfFirst { list -> list.any { it.id == id } }
        if (day >= 0) {
            val index = byWeekday[day].indexOfFirst { it.id == id }
            viewModel.selectWeekday(day)
            scrollToCard(day, index)
            if (cardFocus[id]?.safeRequestFocusWithRetry("OnAirCardRestore") != true) return@LaunchedEffect
        } else {
            if (!topFocusRequester.safeRequestFocusWithRetry("OnAirCardRestoreFallback")) return@LaunchedEffect
        }
        restoreSeries = null
        if (isReturningFromPlayer) onReturnFocusConsumed()
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
            }
            else -> {
                if (initialFocusRequester.safeRequestFocusWithRetry("OnAirReturnFocus")) onReturnFocusConsumed()
            }
        }
    }

    LaunchedEffect(state.selectedSeasonId) {
        vScroll.scrollTo(0)
        hScroll.scrollTo(0)
    }

    if (choosingSeason) {
        Dialog(onDismissRequest = { choosingSeason = false }) {
            Column(Modifier.width(360.dp).heightIn(max = 400.dp)
                .background(colors.surface, RoundedCornerShape(12.dp)).padding(16.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("放送期間", color = colors.textPrimary, fontSize = 20.sp)
                state.seasons.forEach { season ->
                    OnAirAction(
                        season.seasonLabel,
                        onClick = {
                            viewModel.selectSeason(season.seasonId)
                            choosingSeason = false
                            scope.launch { seasonFocus.safeRequestFocusWithRetry("OnAirSeasonReturn") }
                        },
                        selected = season.seasonId == state.selectedSeasonId,
                        modifier = Modifier.fillMaxWidth().testTag("onair-season-${season.seasonId}"),
                    )
                }
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
                    timeFormat = timeFormat,
                    returnCell = returnCell,
                    waitingForPlayerReturn = isReturningFromPlayer,
                    onRestored = { returnCell = null; onReturnFocusConsumed() },
                    onProgram = onProgramClick,
                    onBack = ::closeDetail,
                    modifier = Modifier.fillMaxSize().testTag("onair-details"),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = WEEK_FOCUS_GUTTER),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("放送中", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (state.listStatus == OnAirLoadState.Loading && state.series.isNotEmpty()) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp).testTag("onair-refresh-progress"),
                                color = colors.accent, strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        state.selectedSeason?.let { season ->
                            OnAirAction(
                                "${season.seasonLabel}  ▾", { choosingSeason = true },
                                Modifier.focusRequester(seasonFocus).testTag("onair-season-selector")
                                    .focusProperties { up = topFocusRequester; down = headerFocus.getValue(state.selectedWeekday) },
                            )
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
                                            val daySeries = byWeekday[day]
                                            val headerTargets = DayHeaderFocusTargets(
                                                left = if (day == 0) FocusRequester.Cancel else headerFocus.getValue(day - 1),
                                                right = if (day == 6) FocusRequester.Cancel else headerFocus.getValue(day + 1),
                                                up = if (state.selectedSeason != null) seasonFocus else topFocusRequester,
                                                down = requesterAt(day, 0) ?: FocusRequester.Cancel,
                                            )
                                            DayHeader(
                                                label = weekdays[day],
                                                count = if (isLoading && state.series.isEmpty()) -1 else daySeries.size,
                                                color = weekdayColors[day % weekdayColors.size],
                                                highlighted = day == state.selectedWeekday,
                                                attention = currentSeason && daySeries.any { isOnAirSeriesInAttentionWindow(it, nowJst) },
                                                focusRequester = headerFocus.getValue(day),
                                                targets = headerTargets,
                                                onFocus = {
                                                    viewModel.selectWeekday(day)
                                                    scrollToCard(day, 0)
                                                },
                                                onClick = {
                                                    requesterAt(day, 0)?.let { requester ->
                                                        scope.launch { requester.safeRequestFocusWithRetry("OnAirHeaderConfirm") }
                                                    }
                                                },
                                                modifier = Modifier.testTag("onair-day-header-$day"),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    if (isLoading && state.series.isEmpty()) {
                                        // 静的プレースホルダー: TV で全タイルのアニメーションを走らせない。
                                        Row(Modifier.requiredWidth(weekContentWidth),
                                            horizontalArrangement = Arrangement.spacedBy(WEEK_COLUMN_SPACING)) {
                                            repeat(7) {
                                                Column(Modifier.width(WEEK_COLUMN_WIDTH),
                                                    verticalArrangement = Arrangement.spacedBy(WEEK_CARD_SPACING)) {
                                                    repeat(2) {
                                                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                                                            .background(colors.surface, RoundedCornerShape(8.dp))
                                                            .testTag("onair-loading-tile"))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Only the card rows scroll vertically; weekday labels remain pinned above.
                                            Column(Modifier.weight(1f).verticalScroll(vScroll)) {
                                            Row(
                                                Modifier.requiredWidth(weekContentWidth)
                                                    .padding(vertical = WEEK_FOCUS_GUTTER),
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
                                                                Text("放送中なし", color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                                                            }
                                                        } else {
                                                            daySeries.forEachIndexed { index, series ->
                                                                OnAirCard(
                                                                    series = series,
                                                                    ip = konomiIp, port = konomiPort, timeFormat = timeFormat,
                                                                    attention = currentSeason && isOnAirSeriesInAttentionWindow(series, nowJst),
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

/** 曜日ヘッダーのフォーカス移動先。 */
private data class DayHeaderFocusTargets(
    val left: FocusRequester,
    val right: FocusRequester,
    val up: FocusRequester,
    val down: FocusRequester,
)

@Composable
private fun DayHeader(
    label: String,
    count: Int,
    color: Color,
    highlighted: Boolean,
    attention: Boolean,
    focusRequester: FocusRequester,
    targets: DayHeaderFocusTargets,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(WEEK_COLUMN_WIDTH)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusProperties {
                left = targets.left
                right = targets.right
                up = targets.up
                down = targets.down
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color.copy(alpha = if (highlighted) 1f else 0.72f),
            contentColor = Color.White,
            focusedContainerColor = color,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (attention) Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.55f))) else Border(BorderStroke(0.dp, Color.Transparent)),
            focusedBorder = Border(BorderStroke(2.dp, Color.White)),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(WEEK_HEADER_HEIGHT).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (count < 0) "取得中" else "${count}件",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
            )
        }
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
            focusedContentColor = onAccentColor(colors.accent),
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
private fun OnAirCard(
    series: OnAirSeries,
    ip: String,
    port: String,
    timeFormat: String,
    attention: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val thumbs = remember(series.id, series.thumbnailRecordedProgramIds) { series.thumbnailRecordedProgramIds.take(1) }
    val hasImage = thumbs.isNotEmpty()

    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(16f / 10f),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            if (hasImage) {
                // 1 枚のサムネイルで全面表示。TV の小さいカードでは複数枚の重ねはノイズになる。
                CrossfadeImage(thumbnailUrl(ip, port, thumbs[0]), Modifier.fillMaxSize())
                // 下端を暗くしてタイトル・メタ情報を読みやすくする（HonomiTV の shade: 8% → 88%）。
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.08f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    )
                )
            } else {
                Text(
                    "サムネイルなし",
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 本日の夜の枠は HonomiTV 同様に上部バーと time バッジで強調する。
            if (attention) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(4.dp).background(colors.accent))
            }
            Text(
                displayBroadcastTime(series.broadcastTime, timeFormat),
                color = if (attention) onAccentColor(colors.accent) else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 4f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 9.dp)
                    .background(if (attention) colors.accent else Color.Black.copy(alpha = 0.58f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )

            // 左下: タイトル・話数 / 右下: チャンネルロゴ
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 9.dp, vertical = 8.dp)
            ) {
                Text(
                    series.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), 5f)),
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(if (series.recordedEpisodesCount > 0) "${series.recordedEpisodesCount}話" else "話数情報なし")
                            if (series.missingEpisodesCount > 0) append("・${series.missingEpisodesCount}話未録画")
                            if (series.partiallyRecordedEpisodesCount > 0) append("・${series.partiallyRecordedEpisodesCount}話部分録画")
                        },
                        color = if (series.missingEpisodesCount > 0 || series.partiallyRecordedEpisodesCount > 0) OnAirWarningColor else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 4f)),
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        series.channelIds.take(2).forEach { channel ->
                            ChannelLogoBadge(
                                logoUrl = UrlBuilder.getKonomiTvLogoUrl(ip, port, channel),
                                width = 30.dp,
                                height = 18.dp,
                                modifier = Modifier.clip(RoundedCornerShape(3.dp)),
                                crop = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun thumbnailUrl(ip: String, port: String, id: Int): String =
    UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, id.toString())

internal fun displayBroadcastTime(value: String, format: String): String = runCatching {
    ProgramTimeFormatter.formatTime(LocalTime.parse(value), format)
}.getOrDefault(value.ifBlank { "時刻未登録" })
