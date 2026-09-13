@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
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
) {
    val state by viewModel.uiState.collectAsState()
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState(state.gridFirstVisibleIndex, state.gridFirstVisibleOffset)
    val dayFocus = remember { List(7) { FocusRequester() } }
    val searchFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val cardFocus = remember { mutableMapOf<Int, FocusRequester>() }
    state.series.forEach { cardFocus.getOrPut(it.id) { FocusRequester() } }
    var pageFocused by remember { mutableStateOf(false) }
    var returnCell by remember { mutableStateOf<String?>(null) }
    var restoreSeries by remember { mutableStateOf<Int?>(null) }
    val visible = state.visibleSeries
    val expanded = state.expanded?.takeIf { detail -> visible.any { it.id == detail.seriesId } }

    fun closeDetail() {
        val id = expanded?.seriesId ?: return
        viewModel.collapseSeries()
        restoreSeries = id
    }
    fun pageBack() {
        if (expanded != null) closeDetail() else onBack()
    }

    DisposableEffect(viewModel) {
        viewModel.onEnterPage()
        onDispose { viewModel.onLeavePage() }
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> viewModel.saveGridScroll(index, offset) }
    }
    LaunchedEffect(restoreSeries) {
        val id = restoreSeries ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.id == id }
        if (index >= 0) {
            grid.scrollToItem(index)
            cardFocus[id]?.safeRequestFocusWithRetry("OnAirCardRestore")
        } else dayFocus[state.selectedWeekday].safeRequestFocusWithRetry("OnAirDayRestore")
        restoreSeries = null
    }
    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            val target = viewModel.consumeReturnFocus()
            if (expanded != null && target.episodeCellKey != null) {
                returnCell = target.episodeCellKey
                val index = visible.indexOfFirst { it.id == expanded.seriesId }
                grid.scrollToItem(minOf((index / 4 + 1) * 4, visible.size))
            } else if (target.seriesId != null) {
                restoreSeries = target.seriesId
                onReturnFocusConsumed()
            } else {
                dayFocus[state.selectedWeekday].safeRequestFocusWithRetry("OnAirReturnDay")
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
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("放送中", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
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
                modifier = Modifier.width(310.dp).focusRequester(searchFocus).testTag("onair-search")
                    .focusProperties { down = dayFocus[state.selectedWeekday] },
            )
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
        Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            weekdays.forEachIndexed { day, label ->
                OnAirAction(
                    "$label ${state.weekdayCounts[day] ?: 0}",
                    {
                        viewModel.selectWeekday(day)
                        scope.launch { grid.scrollToItem(0) }
                    },
                    Modifier.weight(1f).focusRequester(dayFocus[day])
                        .then(if (day == state.selectedWeekday) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        .testTag("onair-day-$day")
                        .focusProperties {
                            up = searchFocus
                            down = visible.firstOrNull()?.let { cardFocus[it.id] } ?: FocusRequester.Default
                            left = if (day == 0) FocusRequester.Cancel else dayFocus[day - 1]
                            right = if (day == 6) FocusRequester.Cancel else dayFocus[day + 1]
                        },
                    selected = day == state.selectedWeekday,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val detailHeight = maxHeight
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
                visible.isEmpty() -> OnAirEmpty(
                    if (state.query.isBlank()) "この曜日に放送中のシリーズはありません" else "この曜日には検索に一致するシリーズがありません",
                    if (state.query.isBlank()) "別の曜日を選択してください。" else "各曜日の件数を確認するか、検索語を変更してください。",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4), state = grid,
                    contentPadding = PaddingValues(top = 5.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().testTag("onair-grid"),
                ) {
                    visible.chunked(4).forEachIndexed { rowIndex, row ->
                        row.forEach { series ->
                            item(key = "series:${series.id}") {
                                OnAirCard(series, series.id == expanded?.seriesId, konomiIp, konomiPort, timeFormat,
                                    modifier = Modifier.focusRequester(cardFocus.getValue(series.id))
                                        .testTag("onair-series-${series.id}")
                                        .onFocusChanged { if (it.isFocused) viewModel.saveFocusedSeries(series.id) }
                                        .focusProperties { if (rowIndex == 0) up = dayFocus[state.selectedWeekday] }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && expanded?.seriesId == series.id) {
                                                scope.launch {
                                                    grid.scrollToItem(minOf((rowIndex + 1) * 4, visible.size))
                                                    detailFocus.safeRequestFocusWithRetry("OnAirEnterDetail")
                                                }; true
                                            } else false
                                        },
                                    onClick = { if (expanded?.seriesId == series.id) closeDetail() else viewModel.expandSeries(series.id) },
                                )
                            }
                        }
                        row.firstOrNull { it.id == expanded?.seriesId }?.let { selected ->
                            item(key = "detail:${selected.id}", span = { GridItemSpan(maxLineSpan) }) {
                                OnAirDetail(
                                    selected, expanded!!, state, viewModel, konomiIp, konomiPort,
                                    detailFocus, cardFocus.getValue(selected.id), returnCell,
                                    onRestored = { returnCell = null; onReturnFocusConsumed() },
                                    onProgram = onProgramClick,
                                    modifier = Modifier.fillMaxWidth().height(detailHeight).testTag("onair-details"),
                                )
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
    Surface(
        onClick = onClick, modifier = modifier.height(180.dp),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface, contentColor = colors.textPrimary),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) colors.accent else Color.Transparent)),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
        ),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(90.dp).background(colors.textPrimary.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
                Text("サムネイルなし", color = colors.textSecondary, fontSize = 11.sp)
                series.thumbnailRecordedProgramIds.firstOrNull()?.let { id ->
                    AsyncImage(UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, id.toString()), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    series.channelIds.forEach { channel ->
                        AsyncImage(UrlBuilder.getKonomiTvLogoUrl(ip, port, channel), channel, imageLoader = logos,
                            modifier = Modifier.size(26.dp, 18.dp).background(Color.White, RoundedCornerShape(3.dp)), contentScale = ContentScale.Fit)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(series.title, fontSize = 13.sp, maxLines = 2, minLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
                Text("${displayBroadcastTime(series.broadcastTime, timeFormat)} · ${series.recordedEpisodesCount}話録画", fontSize = 11.sp)
                Text("未録画 ${series.missingEpisodesCount} · 部分録画 ${series.partiallyRecordedEpisodesCount}",
                    color = if (series.missingEpisodesCount + series.partiallyRecordedEpisodesCount > 0) colors.accent else colors.textSecondary,
                    fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

internal fun displayBroadcastTime(value: String, format: String): String = runCatching {
    LocalTime.parse(value).format(DateTimeFormatter.ofPattern(if (format == "12H") "a h:mm" else "HH:mm", Locale.JAPANESE))
}.getOrDefault(value.ifBlank { "時刻未登録" })
