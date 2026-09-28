@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import kotlinx.coroutines.delay
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
    val searchButtonFocus = remember { FocusRequester() }
    val searchFieldFocus = remember { FocusRequester() }
    val cardFocus = remember { mutableMapOf<Int, FocusRequester>() }
    state.series.forEach { cardFocus.getOrPut(it.id) { FocusRequester() } }
    var pageFocused by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var returnCell by remember { mutableStateOf<String?>(null) }
    var restoreSeries by remember { mutableStateOf<Int?>(null) }
    val expanded = state.expanded?.takeIf { detail -> state.series.any { it.id == detail.seriesId } }

    // HonomiTV と同じ週間グリッド: 曜日ごとの縦リストを横に並べる。検索は全曜日に適用。
    val byWeekday = remember(state.series, state.query) {
        val filtered = state.series.filter { it.title.contains(state.query.trim(), ignoreCase = true) }
        (0..6).map { day -> filtered.filter { it.weekday == day } }
    }
    val weekRowState = rememberLazyListState()
    val columnListStates = remember { List(7) { LazyListState() } }

    val initialDay = remember(byWeekday) {
        val preferred = state.selectedWeekday
        if (byWeekday.getOrNull(preferred)?.isNotEmpty() == true) preferred
        else byWeekday.indexOfFirst { it.isNotEmpty() }
    }
    val initialSeriesId = initialDay.takeIf { it in 0..6 }?.let { byWeekday[it].firstOrNull()?.id }

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

    // 焦点移動は手動で行う（LazyRow の未コンポーズ列にも確実に飛ぶため）。
    fun moveFocus(fromDay: Int, index: Int, dayStep: Int, indexStep: Int) {
        val targetDay = if (dayStep != 0) neighborDay(fromDay, dayStep) else fromDay
        val list = byWeekday.getOrNull(targetDay).orEmpty()
        if (list.isEmpty()) return
        val targetIndex = if (indexStep != 0) (index + indexStep).coerceIn(0, list.lastIndex)
        else index.coerceIn(0, list.lastIndex)
        val targetId = list[targetIndex].id
        viewModel.selectWeekday(targetDay)
        // 既に見えている場合は即座にフォーカス（同期的に確定させる）。
        cardFocus[targetId]?.safeRequestFocus("OnAirCardMove")
        // 列が画面外の場合はスクロールしてから確実にフォーカスし直す。
        scope.launch {
            weekRowState.animateScrollToItem(targetDay)
            columnListStates[targetDay].scrollToItem(targetIndex)
            delay(80)
            cardFocus[targetId]?.safeRequestFocusWithRetry("OnAirCardMoveRetry")
        }
    }

    fun closeDetail() {
        val id = expanded?.seriesId ?: return
        viewModel.collapseSeries()
        restoreSeries = id
    }
    fun closeSearch() {
        searchOpen = false
        scope.launch { searchButtonFocus.safeRequestFocusWithRetry("OnAirCloseSearch") }
    }
    fun pageBack() {
        when {
            expanded != null -> closeDetail()
            searchOpen -> closeSearch()
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
            weekRowState.scrollToItem(day)
            columnListStates[day].scrollToItem(0)
        }
        if (initialSeriesId != null) {
            initialFocusRequester.safeRequestFocusWithRetry("OnAirInitialFocus")
        } else {
            searchButtonFocus.safeRequestFocusWithRetry("OnAirInitialFallback")
        }
    }

    LaunchedEffect(restoreSeries) {
        val id = restoreSeries ?: return@LaunchedEffect
        val day = byWeekday.indexOfFirst { list -> list.any { it.id == id } }
        if (day >= 0) {
            val index = byWeekday[day].indexOfFirst { it.id == id }
            viewModel.selectWeekday(day)
            weekRowState.scrollToItem(day)
            columnListStates[day].scrollToItem(index)
            cardFocus[id]?.safeRequestFocusWithRetry("OnAirCardRestore")
        } else {
            searchButtonFocus.safeRequestFocusWithRetry("OnAirCardRestoreFallback")
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
                    pageBack(); true
                } else false
            },
    ) {
        if (expanded != null) {
            OnAirDetail(
                series = state.series.first { it.id == expanded.seriesId },
                detail = expanded,
                state = state,
                viewModel = viewModel,
                ip = konomiIp, port = konomiPort,
                returnCell = returnCell,
                onRestored = { returnCell = null; onReturnFocusConsumed() },
                onProgram = onProgramClick,
                onBack = ::closeDetail,
                modifier = Modifier.fillMaxSize().testTag("onair-details"),
            )
            return@Column
        }

        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showHeadline) {
                Text("放送中", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(24.dp))
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    singleLine = true,
                    label = { androidx.compose.material3.Text("シリーズを検索") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent, unfocusedBorderColor = colors.textSecondary,
                        focusedLabelColor = colors.accent, unfocusedLabelColor = colors.textSecondary,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier.width(310.dp).focusRequester(searchFieldFocus).testTag("onair-search-field")
                        .focusProperties {
                            down = initialSeriesId?.let { cardFocus[it] } ?: FocusRequester.Cancel
                            up = FocusRequester.Cancel
                            left = FocusRequester.Cancel
                        },
                )
                Spacer(Modifier.width(8.dp))
                if (state.query.isNotBlank()) {
                    OnAirAction("クリア", { viewModel.updateSearchQuery("") }, Modifier.testTag("onair-search-clear"))
                    Spacer(Modifier.width(8.dp))
                }
                OnAirAction("閉じる", { closeSearch() }, Modifier.testTag("onair-search-close"))
            } else {
                OnAirAction(
                    if (state.query.isBlank()) "検索" else "検索中",
                    {
                        searchOpen = true
                        scope.launch { searchFieldFocus.safeRequestFocusWithRetry("OnAirOpenSearch") }
                    },
                    Modifier.focusRequester(searchButtonFocus).testTag("onair-search")
                        .focusProperties { up = topFocusRequester },
                    selected = state.query.isNotBlank(),
                )
            }
            Spacer(Modifier.weight(1f))
            OnAirAction("更新", viewModel::refresh, Modifier.testTag("onair-refresh"))
            Spacer(Modifier.width(12.dp))
            OnAirAction("接続設定", onSettings)
        }
        // This slot always exists: refresh/error text never shifts the weekdays or cards.
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            val failure = state.listStatus as? OnAirLoadState.Error
            Text(
                when {
                    failure != null -> "放送中の取得失敗 · ${failure.message} [${failure.code}]"
                    state.listStatus == OnAirLoadState.Loading -> if (state.series.isEmpty()) "放送中を読み込み中…" else "放送中を更新中…"
                    else -> "${state.filteredSeries.size} シリーズ · 日本時間"
                },
                color = colors.textSecondary, fontSize = 12.sp,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (failure != null && state.backendSupported) OnAirAction("再試行", viewModel::retryList)
        }

        when {
            !state.backendSupported -> OnAirEmpty(
                "このバックエンドは「放送中」に対応していません",
                "HonomiTV の接続を設定してください。 [ON_AIR_UNSUPPORTED]", "接続設定を開く", onSettings,
            )
            state.series.isEmpty() && state.listStatus is OnAirLoadState.Error -> {
                val error = state.listStatus as OnAirLoadState.Error
                OnAirEmpty("放送中を読み込めませんでした", "${error.message}\n[${error.code}]", "再試行", viewModel::retryList)
            }
            state.series.isEmpty() && (state.listStatus == OnAirLoadState.Loading || state.listStatus == OnAirLoadState.Idle) ->
                OnAirEmpty("放送中を読み込み中…")
            state.filteredSeries.isEmpty() -> OnAirEmpty(
                if (state.query.isBlank()) "放送中のシリーズはありません" else "検索に一致するシリーズがありません",
            )
            else -> LazyRow(
                state = weekRowState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("onair-grid"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(7) { day ->
                    val daySeries = byWeekday[day]
                    Column(Modifier.width(WEEK_COLUMN_WIDTH).fillParentMaxHeight()) {
                        // 曜日ヘッダーはフォーカス不可のラベル（左右移動で選択が決まる）。
                        Row(
                            Modifier.fillMaxWidth().height(34.dp)
                                .background(
                                    weekdayColors[day % weekdayColors.size].copy(
                                        alpha = if (day == state.selectedWeekday) 1f else 0.72f
                                    ),
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(weekdays[day], color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Text("${daySeries.size}件", color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (daySeries.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Text("放送中なし", color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        } else {
                            LazyColumn(
                                state = columnListStates[day],
                                modifier = Modifier.weight(1f).fillMaxWidth().focusGroup().testTag("onair-column-$day"),
                                contentPadding = PaddingValues(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(daySeries, key = { _, s -> "series:${s.id}" }) { index, series ->
                                    OnAirCard(
                                        series = series, selected = false,
                                        ip = konomiIp, port = konomiPort, timeFormat = timeFormat,
                                        modifier = Modifier
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
                                            .onPreviewKeyEvent { event ->
                                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                when (event.key) {
                                                    Key.DirectionLeft -> { moveFocus(day, index, -1, 0); true }
                                                    Key.DirectionRight -> { moveFocus(day, index, 1, 0); true }
                                                    Key.DirectionDown -> {
                                                        if (index < daySeries.lastIndex) moveFocus(day, index, 0, 1)
                                                        true
                                                    }
                                                    Key.DirectionUp -> {
                                                        if (index > 0) moveFocus(day, index, 0, -1)
                                                        else searchButtonFocus.safeRequestFocus("OnAirCardUp")
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            },
                                        onClick = { viewModel.expandSeries(series.id) },
                                    )
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
internal fun OnAirEmpty(title: String, detail: String = "", action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
