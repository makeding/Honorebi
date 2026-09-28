@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.buildSeriesEpisodeMatrix
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun OnAirDetail(
    series: OnAirSeries, detail: OnAirExpandedSeries, state: OnAirUiState, viewModel: OnAirViewModel,
    ip: String, port: String,
    returnCell: String?, onRestored: () -> Unit, onProgram: (RecordedProgram) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    val colors = KomorebiTheme.colors
    val matrix = remember(detail.programs) { buildSeriesEpisodeMatrix(detail.programs) }
    val backFocus = remember(series.id) { FocusRequester() }
    val summaryFocus = remember(series.id) { FocusRequester() }
    val horizontal = rememberScrollState(state.matrixHorizontalScroll)
    val vertical = rememberScrollState(state.matrixVerticalScroll)
    val summaryScroll = rememberScrollState(state.summaryScroll)
    val scope = rememberCoroutineScope()
    LaunchedEffect(horizontal) { snapshotFlow { horizontal.value }.collect(viewModel::saveMatrixHorizontalScroll) }
    LaunchedEffect(vertical) { snapshotFlow { vertical.value }.collect(viewModel::saveMatrixVerticalScroll) }
    LaunchedEffect(summaryScroll) { snapshotFlow { summaryScroll.value }.collect(viewModel::saveSummaryScroll) }

    // 作品情報パネルの表示状態。マトリクスへ移動すると畳み、戻るで再表示する。
    var summaryVisible by remember(series.id) { mutableStateOf(true) }
    var restoreSummaryFocus by remember(series.id) { mutableStateOf(false) }

    // Deterministic D-pad navigation: resolve every neighbour from the playable cells only,
    // so non-focusable slots (未録画 / 解析中) can never trap focus.
    val playableKeysByRow = remember(matrix, series.id) {
        matrix.rows.map { row ->
            row.programs.mapIndexed { index, program ->
                program?.takeIf(::canPlayOnAirRecording)
                    ?.let { onAirCellKey(series.id, row.channelId, matrix.slots[index].key, it.id) }
            }
        }
    }
    val cellRequesters = remember(series.id) { mutableMapOf<String, FocusRequester>() }
    playableKeysByRow.forEach { row -> row.filterNotNull().forEach { cellRequesters.getOrPut(it) { FocusRequester() } } }
    val firstCellKey = playableKeysByRow.firstNotNullOfOrNull { row -> row.firstOrNull { it != null } }
    val firstCell = firstCellKey?.let { cellRequesters.getValue(it) }
    // 最新話（最も新しいスロット）に録画がある最も上の放送局セル。
    val latestCellKey = remember(playableKeysByRow, matrix, series.id) {
        (matrix.slots.lastIndex downTo 0).firstNotNullOfOrNull { slotIndex ->
            playableKeysByRow.firstNotNullOfOrNull { row -> row.getOrNull(slotIndex) }
        }
    }
    val latestCell = latestCellKey?.let { cellRequesters.getValue(it) }
    val defaultCell = latestCell ?: firstCell

    fun requesterAt(row: Int, column: Int): FocusRequester? =
        playableKeysByRow.getOrNull(row)?.getOrNull(column)?.let { cellRequesters[it] }

    fun fallbackAbove(): FocusRequester =
        if (summaryVisible) summaryFocus else backFocus

    fun leftOf(row: Int, column: Int): FocusRequester {
        for (c in column - 1 downTo 0) requesterAt(row, c)?.let { return it }
        return fallbackAbove()
    }

    fun rightOf(row: Int, column: Int): FocusRequester? {
        for (c in column + 1 until playableKeysByRow[row].size) requesterAt(row, c)?.let { return it }
        return null
    }

    fun nearestInRow(row: Int, column: Int): FocusRequester? {
        val candidates = playableKeysByRow.getOrNull(row)?.indices?.filter { playableKeysByRow[row][it] != null } ?: return null
        val nearest = candidates.minWithOrNull(compareBy({ abs(it - column) }, { it })) ?: return null
        return requesterAt(row, nearest)
    }

    fun upOf(row: Int, column: Int): FocusRequester =
        if (row == 0) fallbackAbove() else (nearestInRow(row - 1, column) ?: fallbackAbove())

    fun downOf(row: Int, column: Int): FocusRequester? =
        if (row >= playableKeysByRow.lastIndex) null else nearestInRow(row + 1, column)

    var autoFocused by remember(series.id) { mutableStateOf(false) }
    LaunchedEffect(series.id, detail.programsStatus, returnCell) {
        if (detail.programsStatus == OnAirLoadState.Loading) return@LaunchedEffect
        if (returnCell != null) {
            autoFocused = true
            (cellRequesters[returnCell] ?: defaultCell ?: summaryFocus).safeRequestFocusWithRetry("OnAirEpisodeReturn")
            onRestored()
        } else if (!autoFocused) {
            autoFocused = true
            (defaultCell ?: summaryFocus).safeRequestFocusWithRetry("OnAirOpenDetail")
        }
    }

    LaunchedEffect(summaryVisible, restoreSummaryFocus) {
        if (summaryVisible && restoreSummaryFocus) {
            summaryFocus.safeRequestFocusWithRetry("OnAirSummaryRestore")
            restoreSummaryFocus = false
        }
    }

    val handleBack = {
        if (!summaryVisible) {
            summaryVisible = true
            restoreSummaryFocus = true
        } else {
            onBack()
        }
    }
    BackHandler(enabled = true) { handleBack() }

    Surface(modifier, colors = SurfaceDefaults.colors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                OnAirAction("戻る", handleBack,
                    Modifier.focusRequester(backFocus)
                        .focusProperties {
                            up = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel
                            down = if (summaryVisible) summaryFocus else (defaultCell ?: FocusRequester.Cancel)
                        }
                        .testTag("onair-detail-back"))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(detail.summary?.bangumiSubjectName?.takeIf(String::isNotBlank) ?: series.title,
                        color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${displayBroadcastTime(series.broadcastTime, "24H")} · ${series.recordedEpisodesCount}話録画 · 決定で再生 / 戻るで一覧",
                        color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (summaryVisible) {
                    Column(Modifier.width(360.dp).fillMaxHeight()) {
                        DetailStatus(detail.summaryStatus, "作品情報", viewModel::retrySummary, Modifier.height(40.dp))
                        SeriesSummaryPanel(
                            series = series,
                            detail = detail,
                            expanded = state.summaryExpanded,
                            scrollState = summaryScroll,
                            scope = scope,
                            focusRequester = summaryFocus,
                            downRequester = defaultCell ?: FocusRequester.Cancel,
                            onToggleExpanded = { viewModel.setSummaryExpanded(!state.summaryExpanded) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DetailStatus(detail.programsStatus, "録画", viewModel::retryPrograms, Modifier.height(40.dp))
                    if (matrix.rows.isEmpty()) {
                        OnAirEmpty(when (detail.programsStatus) {
                            OnAirLoadState.Loading, OnAirLoadState.Idle -> "全ての録画を読み込み中…"
                            is OnAirLoadState.Error -> "録画一覧を取得できませんでした"
                            else -> "録画番組がありません"
                        })
                    } else {
                        val logos = rememberChannelLogoImageLoader()
                        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical)) {
                            Column(Modifier.width(112.dp)) {
                                Text("放送局", Modifier.height(28.dp), color = colors.textSecondary, fontSize = 12.sp)
                                matrix.rows.forEach { row ->
                                    Column(Modifier.height(84.dp).padding(end = 8.dp, top = 4.dp)) {
                                        row.channelId?.let { channel ->
                                            AsyncImage(UrlBuilder.getKonomiTvLogoUrl(ip, port, channel), null, imageLoader = logos,
                                                modifier = Modifier.size(48.dp, 24.dp).background(Color.White), contentScale = ContentScale.Fit)
                                        }
                                        Text(row.channelName, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            Column(Modifier.horizontalScroll(horizontal)) {
                                Row(Modifier.height(28.dp)) {
                                    matrix.slots.forEach { slot -> Text(slot.label, Modifier.width(154.dp), color = colors.textSecondary, fontSize = 12.sp) }
                                }
                                matrix.rows.forEachIndexed { rowIndex, row ->
                                    Row(Modifier.height(84.dp)) {
                                        row.programs.forEachIndexed { index, program ->
                                            val cellKey = program?.takeIf(::canPlayOnAirRecording)
                                                ?.let { onAirCellKey(series.id, row.channelId, matrix.slots[index].key, it.id) }
                                            key(row.channelId, matrix.slots[index].key, program?.id) {
                                                if (cellKey == null || program == null) {
                                                    Box(Modifier.width(154.dp).fillMaxHeight().padding(end = 8.dp, bottom = 8.dp)
                                                        .background(colors.textPrimary.copy(alpha = .05f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                                        Text(if (program == null) "未録画" else "解析中・再生準備中", color = colors.textSecondary, fontSize = 12.sp)
                                                    }
                                                } else {
                                                    Surface(
                                                        onClick = {
                                                            viewModel.saveFocusedEpisodeCell(cellKey)
                                                            viewModel.saveFocusedSeries(series.id)
                                                            onProgram(program)
                                                        },
                                                        modifier = Modifier.width(154.dp).fillMaxHeight().padding(end = 8.dp, bottom = 8.dp)
                                                            .focusRequester(cellRequesters.getValue(cellKey))
                                                            .testTag("onair-episode-$cellKey")
                                                            .onFocusChanged {
                                                                if (it.isFocused) {
                                                                    viewModel.saveFocusedEpisodeCell(cellKey)
                                                                    // 既定セルから動いたら作品情報を畳む（戻るで再表示）。
                                                                    if (summaryVisible && cellKey != latestCellKey) summaryVisible = false
                                                                }
                                                            }
                                                            .focusProperties {
                                                                left = leftOf(rowIndex, index)
                                                                right = rightOf(rowIndex, index) ?: FocusRequester.Cancel
                                                                up = upOf(rowIndex, index)
                                                                down = downOf(rowIndex, index) ?: FocusRequester.Cancel
                                                            },
                                                        colors = ClickableSurfaceDefaults.colors(
                                                            containerColor = colors.surface, contentColor = Color.White,
                                                            focusedContainerColor = colors.surface, focusedContentColor = Color.White),
                                                        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.accent))),
                                                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                                                    ) {
                                                        Box(Modifier.fillMaxSize().background(Color(0xFF191919))) {
                                                            AsyncImage(
                                                                UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString()),
                                                                null,
                                                                Modifier.fillMaxSize(),
                                                                contentScale = ContentScale.Crop,
                                                            )
                                                            Box(Modifier.matchParentSize().background(
                                                                Brush.verticalGradient(
                                                                    0f to Color.Transparent,
                                                                    0.45f to Color.Transparent,
                                                                    1f to Color.Black.copy(alpha = 0.86f),
                                                                )
                                                            ))
                                                            if (program.isPartiallyRecorded) {
                                                                Text("⚠ 一部のみ録画",
                                                                    color = Color(0xFFFFB4AB), fontSize = 9.sp, maxLines = 1,
                                                                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                                                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                                                                        .padding(horizontal = 4.dp, vertical = 1.dp))
                                                            }
                                                            Text(
                                                                program.subtitle?.takeIf(String::isNotBlank) ?: program.title,
                                                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.align(Alignment.BottomStart)
                                                                    .fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp),
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
                    }
                }
            }
        }
    }
}

/**
 * 作品情報パネル。HonomiTV の bangumi ブロックと同じく、
 * ポスター + 邦題 / 中文題 + あらすじ（日本語・中国語）を横並びで表示する。
 */
@Composable
private fun SeriesSummaryPanel(
    series: OnAirSeries,
    detail: OnAirExpandedSeries,
    expanded: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    scope: kotlinx.coroutines.CoroutineScope,
    focusRequester: FocusRequester,
    downRequester: FocusRequester,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val summary = detail.summary
    val jpTitle = summary?.bangumiSubjectName?.takeIf(String::isNotBlank) ?: series.title
    val cnTitle = summary?.bangumiSubjectNameCn?.takeIf(String::isNotBlank)
    val jpSummary = summary?.bangumiSubjectSummary?.takeIf(String::isNotBlank)
        ?: summary?.description?.takeIf(String::isNotBlank)
    val poster = summary?.bangumiSubjectImageUrl?.takeIf(String::isNotBlank)

    Surface(
        onClick = onToggleExpanded,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester).testTag("onair-summary")
            .focusProperties {
                up = FocusRequester.Cancel
                right = downRequester
                down = downRequester
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when {
                    event.key == Key.DirectionDown && scrollState.value < scrollState.maxValue -> {
                        scope.launch { scrollState.scrollTo((scrollState.value + 120).coerceAtMost(scrollState.maxValue)) }; true
                    }
                    event.key == Key.DirectionUp && scrollState.value > 0 -> {
                        scope.launch { scrollState.scrollTo((scrollState.value - 120).coerceAtLeast(0)) }; true
                    }
                    else -> false
                }
            },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.accent))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.width(112.dp).height(158.dp).clip(RoundedCornerShape(6.dp))
                        .background(colors.textPrimary.copy(alpha = .06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ポスターなし", color = colors.textSecondary, fontSize = 11.sp)
                    poster?.let { AsyncImage(it, "作品ポスター", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f)) {
                    Text(jpTitle, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cnTitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(cnTitle, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        jpSummary ?: "作品紹介はありません",
                        color = colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "決定で${if (expanded) "紹介を短く表示" else "紹介の全文を読む"} · 上下でスクロール · 戻るで一覧",
                color = colors.accent, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun DetailStatus(status: OnAirLoadState, label: String, retry: () -> Unit, modifier: Modifier) {
    val error = status as? OnAirLoadState.Error
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(when (status) {
            is OnAirLoadState.Error -> "$label: ${status.message} [${status.code}]"
            OnAirLoadState.Loading, OnAirLoadState.Idle -> "$label を読み込み中…"
            else -> label
        }, Modifier.weight(1f), color = KomorebiTheme.colors.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (error != null) OnAirAction("再試行", retry)
    }
}

internal fun canPlayOnAirRecording(program: RecordedProgram): Boolean =
    program.recordedVideo.hasKeyFrames != false || program.isRecording || program.recordedVideo.status == "Recording"

internal fun onAirCellKey(seriesId: Int, channelId: String?, slot: String, recordingId: Int) =
    "$seriesId:${channelId ?: "unknown"}:$slot:$recordingId"
