@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.SeriesProgram
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.buildSeriesEpisodeMatrix
import com.beeregg2001.komorebi.viewmodel.OnAirExpandedSeries
import com.beeregg2001.komorebi.viewmodel.OnAirLoadState
import com.beeregg2001.komorebi.viewmodel.OnAirViewModel
import java.time.LocalTime
import kotlinx.coroutines.flow.drop

private val weekdayLabels = listOf("月", "火", "水", "木", "金", "土", "日")

/** A self-contained TV surface; selection and restored focus identity live in [OnAirViewModel]. */
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
    val gridState = rememberLazyGridState()
    val weekdayRequesters = remember { List(7) { FocusRequester() } }
    val firstCardRequester = remember { FocusRequester() }
    val expanded = state.expanded
    var focusedSeriesId by remember { mutableStateOf<Int?>(null) }
    var returnSeriesId by remember { mutableStateOf<Int?>(null) }
    var returnEpisodeKey by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel) { viewModel.onEnterPage(); onDispose { viewModel.onLeavePage() } }
    LaunchedEffect(Unit) {
        gridState.scrollToItem(state.gridFirstVisibleIndex, state.gridFirstVisibleOffset)
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }.drop(1)
            .collect { (index, offset) -> viewModel.saveGridScroll(index, offset) }
    }

    LaunchedEffect(isReturningFromPlayer, expanded?.seriesId) {
        if (isReturningFromPlayer) {
            val target = viewModel.consumeReturnFocus()
            returnSeriesId = target.seriesId
            returnEpisodeKey = target.episodeCellKey
            if (target.seriesId != null) {
                gridState.scrollToItem((state.visibleSeries.indexOfFirst { it.id == target.seriesId }).coerceAtLeast(0))
            }
            if (target.seriesId == null) firstCardRequester.safeRequestFocusWithRetry("OnAirPlayerReturn")
            onReturnFocusConsumed()
        }
    }
    BackHandler(enabled = expanded != null) { viewModel.collapseSeries() }
    BackHandler(enabled = expanded == null) { onBack() }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 18.dp)) {
        OnAirToolbar(
            query = state.query,
            onQuery = viewModel::updateSearchQuery,
            onRefresh = viewModel::refresh,
            onSettings = onSettings,
        )
        Spacer(Modifier.height(14.dp))
        WeekdayStrip(
            selected = state.selectedWeekday,
            counts = state.weekdayCounts,
            requesters = weekdayRequesters,
            onSelect = viewModel::selectWeekday,
            firstCardRequester = firstCardRequester,
            initialFocusRequester = initialFocusRequester,
        )
        Spacer(Modifier.height(14.dp))

        when {
            !state.backendSupported -> OnAirMessage("このバックエンドは放送中に対応していません", "接続設定を開く", onSettings)
            state.listStatus is OnAirLoadState.Error -> OnAirMessage(
                "放送中を読み込めませんでした", "再試行", viewModel::retryList,
                (state.listStatus as OnAirLoadState.Error).let { "${it.message} (${it.code})" },
            )
            state.listStatus is OnAirLoadState.Loading && state.series.isEmpty() -> LoadingSlot()
            state.visibleSeries.isEmpty() -> OnAirMessage(
                if (state.query.isBlank()) "この曜日に放送中のシリーズはありません" else "検索に一致するシリーズはありません",
                null, null,
            )
            else -> OnAirGrid(
                series = state.visibleSeries,
                expanded = expanded,
                gridState = gridState,
                firstCardRequester = firstCardRequester,
                weekdayRequester = weekdayRequesters[state.selectedWeekday],
                focusedSeriesId = focusedSeriesId,
                onFocused = { focusedSeriesId = it; viewModel.saveFocusedSeries(it) },
                returnSeriesId = returnSeriesId,
                onReturnSeriesFocused = { returnSeriesId = null },
                onSeries = viewModel::expandSeries,
                onCollapse = viewModel::collapseSeries,
                onProgram = onProgramClick,
                onRetrySummary = viewModel::retrySummary,
                onRetryPrograms = viewModel::retryPrograms,
                onSaveMatrixScroll = viewModel::saveMatrixHorizontalScroll,
                onFocusedEpisode = viewModel::saveFocusedEpisodeCell,
                matrixInitialScroll = state.matrixHorizontalScroll,
                returnEpisodeKey = returnEpisodeKey,
                onReturnEpisodeFocused = { returnEpisodeKey = null },
                onBack = onBack,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                timeFormat = timeFormat,
            )
        }
    }
}

@Composable private fun OnAirToolbar(query: String, onQuery: (String) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val colors = KomorebiTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("放送中", color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            label = { androidx.compose.material3.Text("シリーズを検索") }, modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.weight(1f))
        Surface(onClick = onRefresh, modifier = Modifier.size(52.dp), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp))) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Refresh, "更新") }
        }
        Spacer(Modifier.width(12.dp))
        Surface(onClick = onSettings, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp))) {
            Text("接続設定", Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        }
    }
}

@Composable private fun WeekdayStrip(selected: Int, counts: Map<Int, Int>, requesters: List<FocusRequester>, onSelect: (Int) -> Unit, firstCardRequester: FocusRequester, initialFocusRequester: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup()) {
        weekdayLabels.forEachIndexed { index, label ->
            Surface(
                onClick = { onSelect(index) }, modifier = Modifier.focusRequester(requesters[index]).then(if (index == selected) Modifier.focusRequester(initialFocusRequester) else Modifier).focusProperties {
                    down = firstCardRequester
                    if (index == 0) left = FocusRequester.Cancel
                    if (index == weekdayLabels.lastIndex) right = FocusRequester.Cancel
                },
                colors = ClickableSurfaceDefaults.colors(containerColor = if (selected == index) KomorebiTheme.colors.accent else KomorebiTheme.colors.surface),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(9.dp)),
            ) { Text("$label ${counts[index] ?: 0}", Modifier.padding(horizontal = 18.dp, vertical = 11.dp)) }
        }
    }
}

@Composable private fun OnAirGrid(
    series: List<OnAirSeries>, expanded: OnAirExpandedSeries?, gridState: LazyGridState,
    firstCardRequester: FocusRequester, weekdayRequester: FocusRequester, focusedSeriesId: Int?, onFocused: (Int) -> Unit,
    returnSeriesId: Int?, onReturnSeriesFocused: () -> Unit,
    onSeries: (Int) -> Unit, onCollapse: () -> Unit, onProgram: (RecordedProgram) -> Unit,
    onRetrySummary: () -> Unit, onRetryPrograms: () -> Unit, onBack: () -> Unit,
    onSaveMatrixScroll: (Int) -> Unit, onFocusedEpisode: (String?) -> Unit,
    matrixInitialScroll: Int, returnEpisodeKey: String?, onReturnEpisodeFocused: () -> Unit,
    konomiIp: String, konomiPort: String, timeFormat: String,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4), state = gridState, contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize().focusGroup(),
    ) {
        series.chunked(4).forEach { row ->
            row.forEachIndexed { index, item ->
                item(key = "onair:${item.id}") {
                    val cardRequester = remember { FocusRequester() }
                    LaunchedEffect(returnSeriesId) { if (returnSeriesId == item.id) { cardRequester.safeRequestFocusWithRetry("OnAirSeriesReturn"); onReturnSeriesFocused() } }
                    OnAirCard(item, item.id == expanded?.seriesId, Modifier.focusRequester(cardRequester).then(if (series.indexOf(item) == 0) Modifier.focusRequester(firstCardRequester) else Modifier)
                        .focusProperties { if (series.indexOf(item) < 4) up = weekdayRequester; if (index == 0) left = FocusRequester.Cancel }
                        .onFocusChanged { if (it.isFocused) onFocused(item.id) }
                        .onKeyEvent { event -> if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) { if (expanded != null) onCollapse() else onBack(); true } else false },
                        konomiIp, konomiPort, timeFormat, onSeries)
                }
            }
            row.firstOrNull { it.id == expanded?.seriesId }?.let { selected ->
                item(key = "onair-detail:${selected.id}", span = { GridItemSpan(maxLineSpan) }) {
                    OnAirDetail(selected, expanded!!, konomiIp, konomiPort, onProgram, onRetrySummary, onRetryPrograms, onSaveMatrixScroll, onFocusedEpisode, matrixInitialScroll, returnEpisodeKey, onReturnEpisodeFocused)
                }
            }
        }
    }
}

@Composable private fun OnAirCard(series: OnAirSeries, selected: Boolean, modifier: Modifier, ip: String, port: String, timeFormat: String, onClick: (Int) -> Unit) {
    val colors = KomorebiTheme.colors
    Surface(onClick = { onClick(series.id) }, modifier = modifier.height(172.dp), colors = ClickableSurfaceDefaults.colors(containerColor = colors.surface), border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.accent)))) {
        Box(Modifier.fillMaxSize()) {
            val imageId = series.thumbnailRecordedProgramIds.firstOrNull()
            if (imageId == null) Box(Modifier.fillMaxSize().background(colors.textPrimary.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Text("サムネイルなし", color = colors.textSecondary) }
            else AsyncImage(ImageRequest.Builder(LocalContext.current).data(UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, imageId.toString())).crossfade(false).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(64.dp).background(Color.Black.copy(alpha = .72f)).padding(8.dp)) {
                Text(series.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text("${displayBroadcastTime(series.broadcastTime, timeFormat)}  ·  ${series.recordedEpisodesCount}録画", fontSize = 12.sp)
                Text(if (series.missingEpisodesCount > 0 || series.partiallyRecordedEpisodesCount > 0) buildString { if (series.missingEpisodesCount > 0) append("欠${series.missingEpisodesCount}"); if (series.partiallyRecordedEpisodesCount > 0) append(" 部分${series.partiallyRecordedEpisodesCount}") } else "録画状態に問題はありません", color = if (series.missingEpisodesCount > 0 || series.partiallyRecordedEpisodesCount > 0) Color(0xFFFF8A80) else colors.textSecondary, fontSize = 12.sp, maxLines = 1)
            }
            if (selected) Text("詳細を表示中", Modifier.align(Alignment.TopEnd).padding(8.dp).background(colors.accent, RoundedCornerShape(5.dp)).padding(5.dp), fontSize = 11.sp)
        }
    }
}

@Composable private fun OnAirDetail(series: OnAirSeries, expanded: OnAirExpandedSeries, ip: String, port: String, onProgram: (RecordedProgram) -> Unit, retrySummary: () -> Unit, retryPrograms: () -> Unit, onSaveMatrixScroll: (Int) -> Unit, onFocusedEpisode: (String?) -> Unit, matrixInitialScroll: Int, returnEpisodeKey: String?, onReturnEpisodeFocused: () -> Unit) {
    val colors = KomorebiTheme.colors
    Surface(Modifier.fillMaxWidth().height(300.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = .16f)))) {
        Row(Modifier.padding(18.dp)) {
            SummaryPane(series, expanded.summary, expanded.summaryStatus, retrySummary, Modifier.width(340.dp).fillMaxSize())
            Spacer(Modifier.width(20.dp))
            EpisodeMatrixPane(expanded, ip, port, onProgram, retryPrograms, onSaveMatrixScroll, onFocusedEpisode, matrixInitialScroll, returnEpisodeKey, onReturnEpisodeFocused, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@Composable private fun SummaryPane(series: OnAirSeries, summary: SeriesProgram?, status: OnAirLoadState, retry: () -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Column(modifier.verticalScroll(scroll)) {
        val poster = summary?.bangumiSubjectImageUrl
        if (poster.isNullOrBlank()) Box(Modifier.fillMaxWidth().height(96.dp).background(KomorebiTheme.colors.textPrimary.copy(alpha = .06f)), contentAlignment = Alignment.Center) { Text("ポスターなし", color = KomorebiTheme.colors.textSecondary) }
        else AsyncImage(ImageRequest.Builder(LocalContext.current).data(poster).crossfade(false).build(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(8.dp))
        Text(summary?.bangumiSubjectName ?: series.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        summary?.bangumiSubjectNameCn?.takeIf { it.isNotBlank() }?.let { Text(it, color = KomorebiTheme.colors.textSecondary) }
        Spacer(Modifier.height(12.dp))
        when (status) {
            is OnAirLoadState.Loading -> LoadingSlot()
            is OnAirLoadState.Error -> OnAirMessage("作品情報を読み込めませんでした", "再試行", retry, "${status.message} (${status.code})")
            else -> Text(summary?.bangumiSubjectSummary?.takeIf { it.isNotBlank() } ?: summary?.description?.takeIf { it.isNotBlank() } ?: "作品紹介はありません", color = KomorebiTheme.colors.textSecondary)
        }
    }
}

@Composable private fun EpisodeMatrixPane(expanded: OnAirExpandedSeries, ip: String, port: String, onProgram: (RecordedProgram) -> Unit, retry: () -> Unit, onSaveMatrixScroll: (Int) -> Unit, onFocusedEpisode: (String?) -> Unit, matrixInitialScroll: Int, returnEpisodeKey: String?, onReturnEpisodeFocused: () -> Unit, modifier: Modifier) {
    when (val status = expanded.programsStatus) {
        is OnAirLoadState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is OnAirLoadState.Error -> Box(modifier, contentAlignment = Alignment.Center) { OnAirMessage("録画を読み込めませんでした", "再試行", retry, "${status.message} (${status.code})") }
        else -> {
            val matrix = remember(expanded.programs) { buildSeriesEpisodeMatrix(expanded.programs) }
            if (matrix.slots.isEmpty()) Box(modifier, contentAlignment = Alignment.Center) { Text("録画番組がありません") }
            else {
                val horizontal = rememberScrollState()
                LaunchedEffect(matrixInitialScroll) { horizontal.scrollTo(matrixInitialScroll) }
                LaunchedEffect(horizontal) { snapshotFlow { horizontal.value }.drop(1).collect(onSaveMatrixScroll) }
                Column(modifier.horizontalScroll(horizontal)) {
                    Row { Text("放送局", Modifier.width(130.dp)); matrix.slots.forEach { Text(it.label, Modifier.width(150.dp), maxLines = 1) } }
                    matrix.rows.forEach { row -> Row(Modifier.height(92.dp)) {
                        Text(row.channelName, Modifier.width(130.dp).padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        row.programs.forEachIndexed { index, program ->
                            if (program == null) Box(Modifier.width(150.dp).fillMaxSize().padding(5.dp).clip(RoundedCornerShape(6.dp)).background(KomorebiTheme.colors.textPrimary.copy(alpha = .05f)), contentAlignment = Alignment.Center) { Text("未録画", color = KomorebiTheme.colors.textSecondary, fontSize = 12.sp) }
                            else {
                                val playable = program.recordedVideo.hasKeyFrames != false || program.isRecording || program.recordedVideo.status.equals("Recording", true)
                                if (playable) {
                                    val key = "${row.channelId}:${matrix.slots[index].key}:${program.id}"
                                    val requester = remember { FocusRequester() }
                                    LaunchedEffect(returnEpisodeKey) { if (returnEpisodeKey == key) { requester.safeRequestFocusWithRetry("OnAirEpisodeReturn"); onReturnEpisodeFocused() } }
                                    Surface(onClick = { onProgram(program) }, modifier = Modifier.width(150.dp).fillMaxSize().padding(5.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocusedEpisode(key) }.focusProperties { if (index == 0) left = FocusRequester.Cancel }) { Text(program.subtitle ?: program.title, Modifier.padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                                }
                                else Box(Modifier.width(150.dp).fillMaxSize().padding(5.dp).clip(RoundedCornerShape(6.dp)).background(KomorebiTheme.colors.textPrimary.copy(alpha = .05f)), contentAlignment = Alignment.Center) { Text("解析中・再生準備中", color = KomorebiTheme.colors.textSecondary, fontSize = 12.sp) }
                            }
                        }
                    } }
                }
            }
        }
    }
}

@Composable private fun LoadingSlot() = Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
@Composable private fun OnAirMessage(title: String, action: String?, onAction: (() -> Unit)?, detail: String? = null) = Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(title); detail?.let { Text(it, color = KomorebiTheme.colors.textSecondary, fontSize = 13.sp) }; if (action != null && onAction != null) Surface(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(action, Modifier.padding(12.dp)) } }
private fun displayBroadcastTime(value: String, timeFormat: String): String = runCatching { LocalTime.parse(value).let { if (timeFormat == "12H") it.format(java.time.format.DateTimeFormatter.ofPattern("a h:mm")) else it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) } }.getOrDefault(value.ifBlank { "時刻未登録" })
