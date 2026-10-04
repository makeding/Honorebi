@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.onair

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.SeriesProgram
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.components.SeriesEpisodeChannelRow
import com.beeregg2001.komorebi.ui.video.components.SeriesEpisodeMatrix
import com.beeregg2001.komorebi.ui.video.components.buildSeriesEpisodeMatrix
import com.beeregg2001.komorebi.ui.video.components.episodeSlotsFor
import com.beeregg2001.komorebi.viewmodel.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/** HonomiTV の SeriesEpisodeList と同じ 16:9 セル幅。行は 108dp（99dp 可視 + 8dp 下余白）。 */
private val SLOT_WIDTH = 176.dp
private val ROW_HEIGHT = 108.dp
private val CHANNEL_LOGO_WIDTH = 60.dp
private val CHANNEL_NAME_WIDTH = 96.dp
private val CHANNEL_COL_WIDTH = CHANNEL_LOGO_WIDTH + CHANNEL_NAME_WIDTH

@Composable
internal fun OnAirDetail(
    series: OnAirSeries, detail: OnAirExpandedSeries, state: OnAirUiState, viewModel: OnAirViewModel,
    ip: String, port: String,
    returnCell: String?, onRestored: () -> Unit, onProgram: (RecordedProgram) -> Unit, onBack: () -> Unit, modifier: Modifier,
) {
    val colors = KomorebiTheme.colors
    val matrix = remember(detail.programs) { buildSeriesEpisodeMatrix(detail.programs) }
    val summaryFocus = remember(series.id) { FocusRequester() }
    val horizontal = rememberScrollState(state.matrixHorizontalScroll)
    val vertical = rememberScrollState(state.matrixVerticalScroll)
    val scope = rememberCoroutineScope()
    LaunchedEffect(horizontal) { snapshotFlow { horizontal.value }.collect(viewModel::saveMatrixHorizontalScroll) }
    LaunchedEffect(vertical) { snapshotFlow { vertical.value }.collect(viewModel::saveMatrixVerticalScroll) }

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
    // Preserve the chosen entry point: newest playable episode, preferring the top station.
    val latestCellKey = remember(playableKeysByRow, matrix, series.id) {
        (matrix.slots.lastIndex downTo 0).firstNotNullOfOrNull { slotIndex ->
            playableKeysByRow.firstNotNullOfOrNull { row -> row.getOrNull(slotIndex) }
        }
    }
    val latestCell = latestCellKey?.let { cellRequesters.getValue(it) }
    val defaultCell = latestCell ?: firstCell
    // 再試行チップ（エラー時だけ表示）。hero・マトリクス最上段から D-pad で到達できるようにする。
    val summaryRetryFocus = remember(series.id) { FocusRequester() }
    val programsRetryFocus = remember(series.id) { FocusRequester() }
    val upFromMatrixTop = if (detail.programsStatus is OnAirLoadState.Error) programsRetryFocus else summaryFocus
    val heroDown = when {
        detail.programsStatus is OnAirLoadState.Error -> programsRetryFocus
        else -> defaultCell ?: FocusRequester.Cancel
    }
    val heroUp = if (detail.summaryStatus is OnAirLoadState.Error) summaryRetryFocus else FocusRequester.Cancel

    // HonomiTV と同じ欠番/完全録画判定: 作品全体で未録画の話数は 1 行目にだけ「未録画」を出す。
    val missingSlotKeys = remember(matrix) {
        val recorded = matrix.rows.flatMapTo(mutableSetOf()) { row ->
            row.programs.mapIndexedNotNull { index, program -> if (program != null) matrix.slots[index].key else null }
        }
        matrix.slots.filter { it.key.startsWith("episode:") && it.key !in recorded }.map { it.key }.toSet()
    }
    val completeSlotKeys = remember(matrix) {
        matrix.slots.mapIndexed { index, slot ->
            if (slot.key.startsWith("episode:") && matrix.rows.any { it.programs.getOrNull(index)?.isPartiallyRecorded == false }) slot.key else null
        }.filterNotNull().toSet()
    }
    val partialWarningProgramIds = remember(detail.programs, completeSlotKeys) {
        detail.programs.filter { program ->
            program.isPartiallyRecorded && run {
                val keys = episodeSlotsFor(program).filter { it.key.startsWith("episode:") }
                keys.isEmpty() || keys.any { it.key !in completeSlotKeys }
            }
        }.map { it.id }.toSet()
    }

    fun requesterAt(row: Int, column: Int): FocusRequester? =
        playableKeysByRow.getOrNull(row)?.getOrNull(column)?.let { cellRequesters[it] }

    fun leftOf(row: Int, column: Int): FocusRequester {
        for (c in column - 1 downTo 0) requesterAt(row, c)?.let { return it }
        return summaryFocus
    }

    fun rightOf(row: Int, column: Int): FocusRequester? {
        for (c in column + 1 until playableKeysByRow[row].size) requesterAt(row, c)?.let { return it }
        return null
    }

    // 上下は同じ列を優先し、無ければ近い列へ。あまり離れた列へ横方向に飛ばないよう ±1 列を優先する。
    fun nearestInRow(row: Int, column: Int): FocusRequester? {
        val candidates = playableKeysByRow.getOrNull(row)?.indices?.filter { playableKeysByRow[row][it] != null } ?: return null
        if (candidates.isEmpty()) return null
        val chosen = candidates.filter { abs(it - column) <= 1 }.minByOrNull { abs(it - column) }
            ?: candidates.minWithOrNull(compareBy({ abs(it - column) }, { it }))
        return requesterAt(row, requireNotNull(chosen))
    }

    fun upOf(row: Int, column: Int): FocusRequester =
        if (row == 0) upFromMatrixTop else nearestInRow(row - 1, column) ?: summaryFocus

    fun downOf(row: Int, column: Int): FocusRequester? =
        if (row >= playableKeysByRow.lastIndex) null else nearestInRow(row + 1, column)

    // 左右移動の起点となる局（行）。横方向の回り道では更新せず、縦移動や入口での移動でのみ更新する。
    var focusedCellPosition by remember(series.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var horizontalOriginRow by remember(series.id) { mutableStateOf<Int?>(null) }
    var pendingHop by remember(series.id) { mutableStateOf<Pair<Int, Int>?>(null) }

    fun nearestRowWithProgram(originRow: Int, column: Int): Int? =
        playableKeysByRow.indices
            .filter { playableKeysByRow[it].getOrNull(column) != null }
            .minByOrNull { abs(it - originRow) }

    /**
     * 左右は時系列（話数）に沿って進む: 自局に録画がなければその話数を持つ最も近い局へ回り、
     * 以降は自局に録画があれば元の局へ戻る。両局とも無い話数は列ごとスキップする。
     */
    fun walkHorizontally(originRow: Int, startColumn: Int, step: Int): Pair<Int, Int>? {
        var column = startColumn
        while (column in 0 until matrix.slots.size) {
            if (playableKeysByRow.getOrNull(originRow)?.getOrNull(column) != null) return originRow to column
            nearestRowWithProgram(originRow, column)?.let { return it to column }
            column += step
        }
        return null
    }

    var autoFocused by remember(series.id) { mutableStateOf(false) }
    LaunchedEffect(series.id, detail.programsStatus, returnCell) {
        if (returnCell != null) {
            if (detail.programsStatus != OnAirLoadState.Ready) return@LaunchedEffect
            autoFocused = true
            (cellRequesters[returnCell] ?: defaultCell ?: summaryFocus).safeRequestFocusWithRetry("OnAirEpisodeReturn")
            onRestored()
        } else if (!autoFocused) {
            // 詳細は作品情報（hero）から始める。録画一覧へは Down、一覧へは Back。
            autoFocused = true
            summaryFocus.safeRequestFocusWithRetry("OnAirOpenDetail")
        }
    }

    // Back は常に一覧へ戻り、確認前のカードへフォーカスを復元する（OnAirScreen の closeDetail）。
    BackHandler(enabled = true) { onBack() }

    Surface(
        modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                onBack()
                true
            } else false
        },
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // タイトル行は廃止（hero 内にのみ表示）。余った高さは録画マトリクスのカードに回す。
                val heroHeight = (maxHeight * 0.28f).coerceIn(148.dp, 184.dp)
                val heroExpandedMax = (maxHeight * 0.72f)
                Column(Modifier.fillMaxSize()) {
                    DetailStatus(
                        detail.summaryStatus, "作品情報", viewModel::retrySummary, Modifier.height(24.dp),
                        chipFocusRequester = summaryRetryFocus,
                        chipDown = summaryFocus,
                    )
                    SeriesSummaryPanel(
                        series = series,
                        detail = detail,
                        expanded = state.summaryExpanded,
                        scope = scope,
                        focusRequester = summaryFocus,
                        upRequester = heroUp,
                        downRequester = heroDown,
                        onToggleExpanded = { viewModel.setSummaryExpanded(!state.summaryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (state.summaryExpanded) Modifier.heightIn(min = heroHeight, max = heroExpandedMax)
                                else Modifier.height(heroHeight)
                            )
                            .animateContentSize(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        DetailStatus(
                            detail.programsStatus, "録画", viewModel::retryPrograms, Modifier.height(24.dp),
                            chipFocusRequester = programsRetryFocus,
                            chipUp = if (detail.summaryStatus is OnAirLoadState.Error) summaryRetryFocus else summaryFocus,
                            chipDown = defaultCell ?: FocusRequester.Cancel,
                        )
                        if (matrix.rows.isEmpty()) {
                            when (detail.programsStatus) {
                                OnAirLoadState.Loading, OnAirLoadState.Idle -> Spacer(Modifier.weight(1f).fillMaxWidth())
                                is OnAirLoadState.Error -> OnAirEmpty("録画一覧を取得できませんでした", modifier = Modifier.weight(1f))
                                else -> OnAirEmpty("録画番組がありません", modifier = Modifier.weight(1f))
                            }
                        } else {
                            val logos = rememberChannelLogoImageLoader()
                            // 話数ヘッダーは縦スクロールの外に固定し、横スクロールだけに参加させる。
                            Row(
                                Modifier.fillMaxWidth().height(24.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text("放送局", Modifier.width(CHANNEL_COL_WIDTH).testTag("onair-station-header").padding(start = 2.dp),
                                    color = colors.textSecondary, fontSize = 11.sp)
                                Row(Modifier.weight(1f).horizontalScroll(horizontal)) {
                                    matrix.slots.forEach { slot ->
                                        Text(slot.label, Modifier.width(SLOT_WIDTH), color = colors.textPrimary,
                                            fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
                                    }
                                }
                            }
                            Row(
                                Modifier.weight(1f).fillMaxWidth().verticalScroll(vertical)
                                    .onPreviewKeyEvent { event ->
                                        // 左右は時系列に歩く: 欠けている話数は他局の同じ話数へ回り道し、
                                        // 再び自局に録画があれば元の局へ戻る。
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft, Key.DirectionRight -> {
                                                val position = focusedCellPosition ?: return@onPreviewKeyEvent false
                                                val step = if (event.key == Key.DirectionRight) 1 else -1
                                                val origin = horizontalOriginRow ?: position.first
                                                when (val target = walkHorizontally(origin, position.second + step, step)) {
                                                    null -> if (step < 0) {
                                                        scope.launch { summaryFocus.safeRequestFocusWithRetry("OnAirMatrixLeftEdge") }
                                                        true
                                                    } else false
                                                    else -> {
                                                        val requester = requesterAt(target.first, target.second)
                                                            ?: return@onPreviewKeyEvent false
                                                        pendingHop = target
                                                        scope.launch { requester.safeRequestFocusWithRetry("OnAirMatrixWalk") }
                                                        true
                                                    }
                                                }
                                            }
                                            else -> false
                                        }
                                    },
                            ) {
                                Column(Modifier.width(CHANNEL_COL_WIDTH).background(colors.surface)) {
                                    matrix.rows.forEach { row ->
                                        ChannelCell(row, matrix, Modifier.height(ROW_HEIGHT).testTag("onair-station-${row.channelId}"), ip, port, logos)
                                    }
                                }
                                Column(Modifier.horizontalScroll(horizontal)) {
                                    matrix.rows.forEachIndexed { rowIndex, row ->
                                        Row(Modifier.height(ROW_HEIGHT)) {
                                            row.programs.forEachIndexed { index, program ->
                                                val slotKey = matrix.slots[index].key
                                                val cellKey = program?.takeIf(::canPlayOnAirRecording)
                                                    ?.let { onAirCellKey(series.id, row.channelId, slotKey, it.id) }
                                                key(row.channelId, slotKey, program?.id) {
                                                    val cellModifier = Modifier.width(SLOT_WIDTH).fillMaxHeight()
                                                        .padding(end = 8.dp, bottom = 8.dp)
                                                    when {
                                                        cellKey != null -> {
                                                            val playableProgram = requireNotNull(program)
                                                            Surface(
                                                                onClick = {
                                                                    viewModel.saveFocusedEpisodeCell(cellKey)
                                                                    viewModel.saveFocusedSeries(series.id)
                                                                    onProgram(playableProgram)
                                                                },
                                                                modifier = cellModifier
                                                                    .focusRequester(cellRequesters.getValue(cellKey))
                                                                    .testTag("onair-episode-$cellKey")
                                                                    .onFocusChanged {
                                                                        if (it.isFocused) {
                                                                            val pending = pendingHop
                                                                            if (pending != null && pending.first == rowIndex && pending.second == index) {
                                                                                // 横方向の回り道で到達した場合も起点局は変えない。
                                                                                pendingHop = null
                                                                            } else {
                                                                                horizontalOriginRow = rowIndex
                                                                            }
                                                                            focusedCellPosition = rowIndex to index
                                                                            viewModel.saveFocusedEpisodeCell(cellKey)
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
                                                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                                                            ) {
                                                                EpisodeCellContent(
                                                                    playableProgram, ip, port,
                                                                    showPartialWarning = playableProgram.id in partialWarningProgramIds,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            }
                                                        }
                                                        else -> EpisodePlaceholder(
                                                            missing = rowIndex == 0 && slotKey in missingSlotKeys,
                                                            analyzing = program != null,
                                                            modifier = cellModifier,
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

/** 局名セル: ロゴ + 局名、右上にその局の録画カバー表示（HonomiTV の channel-name セル）。 */
@Composable
private fun ChannelCell(
    row: SeriesEpisodeChannelRow,
    matrix: SeriesEpisodeMatrix,
    modifier: Modifier,
    ip: String,
    port: String,
    logos: ImageLoader,
) {
    val colors = KomorebiTheme.colors
    val coveredEpisodes = matrix.slots.countIndexed { index, slot ->
        slot.key.startsWith("episode:") && row.programs.getOrNull(index) != null
    }
    Column(modifier.padding(end = 8.dp, bottom = 8.dp).background(colors.surface)
        .padding(horizontal = 6.dp, vertical = 4.dp)) {
        // ロゴ・カバー数・局名は独立したスロットに置き、長い局名とも重ねない。
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            row.channelId?.let { channel ->
                AsyncImage(
                    UrlBuilder.getKonomiTvLogoUrl(ip, port, channel), null, imageLoader = logos,
                    modifier = Modifier.size(42.dp, 26.dp).background(Color.White, RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(if (coveredEpisodes > 0) "${coveredEpisodes}話録画" else "${row.programs.count { it != null }}件",
                color = colors.textPrimary, fontSize = 10.sp, maxLines = 1,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 4.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(row.channelName, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
    }
}

private inline fun <T> List<T>.countIndexed(predicate: (Int, T) -> Boolean): Int {
    var count = 0
    forEachIndexed { index, item -> if (predicate(index, item)) count++ }
    return count
}

/**
 * 録画のないスロット。作品全体で未録画の話数（1 行目のみ）は注意色、
 * ある局だけの欠けは低コントラスト、解析中はその旨を出す（HonomiTV の placeholder）。
 */
@Composable
private fun EpisodePlaceholder(missing: Boolean, analyzing: Boolean, modifier: Modifier) {
    val colors = KomorebiTheme.colors
    val borderColor = if (missing) OnAirWarningColor.copy(alpha = 0.3f) else colors.textPrimary.copy(alpha = 0.18f)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (missing) OnAirWarningColor.copy(alpha = 0.07f) else colors.textPrimary.copy(alpha = 0.05f))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            missing -> Text("未録画", color = OnAirWarningColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            analyzing -> Text("解析中・再生準備中", color = colors.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 作品情報パネル。HonomiTV の bangumi ブロックと同じく、
 * ポスター + 邦題 / 中文題 + あらすじ（日本語・中国語）を横並びで表示する。
 * 操作は OK（展開 / 収納）のみ。全文表示中で長文のときだけ上下スクロールにフォールバックする。
 */
@Composable
private fun SeriesSummaryPanel(
    series: OnAirSeries,
    detail: OnAirExpandedSeries,
    expanded: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()
    val summary = detail.summary
    val summaryReady = detail.summaryStatus == OnAirLoadState.Ready
    val jpTitle = summary?.bangumiSubjectName?.takeIf(String::isNotBlank) ?: series.title
    val cnTitle = summary?.bangumiSubjectNameCn?.takeIf(String::isNotBlank)
    val jpSummary = summary?.bangumiSubjectSummary?.takeIf(String::isNotBlank)
        ?: summary?.description?.takeIf(String::isNotBlank)
    val poster = summary?.bangumiSubjectImageUrl?.takeIf(String::isNotBlank)

    Surface(
        onClick = onToggleExpanded,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester).testTag("onair-summary")
            .focusProperties {
                up = upRequester
                left = FocusRequester.Cancel
                right = downRequester
                down = downRequester
            }
            .onPreviewKeyEvent { event ->
                // 収納時は OK 展開のみで、Down はそのままマトリクス（局選択）へ進む。
                // 全文表示中で内容があふれたときだけ上下でスクロールする。
                if (!expanded || event.type != KeyEventType.KeyDown) false
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
        Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 108.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(77.dp).height(108.dp).clip(RoundedCornerShape(5.dp))
                        .background(colors.textPrimary.copy(alpha = .06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (summaryReady && poster == null) {
                        Text("ポスター\nなし", color = colors.textSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    poster?.let { AsyncImage(it, "作品ポスター", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    // HonomiTV と同じく収納時は 1 行で省略、展開時だけ 2 行まで見せる。
                    Text(
                        jpTitle, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (cnTitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(cnTitle, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    (jpSummary ?: if (summaryReady) "作品紹介はありません" else null)?.let { summaryText ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            summaryText,
                            color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 中文あらすじは展開時のみ（収納時は高さを確保して Down をマトリクスへ通す）。
                    if (expanded) {
                        cnSummary(summary, jpSummary)?.let { cnText ->
                            Spacer(Modifier.height(3.dp))
                            Text(
                                cnText,
                                color = colors.textSecondary.copy(alpha = 0.82f), fontSize = 12.sp, lineHeight = 16.sp,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** HonomiTV と同じ【簡介原文】分割: 中文パートだけを別段落で出す。 */
private fun cnSummary(summary: SeriesProgram?, jpSummary: String?): String? {
    val bangumiSummary = summary?.bangumiSubjectSummary?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val parts = bangumiSummary.split(Regex("""(?:【|\[)(?:简|簡)介原文(?:】|\])"""), limit = 2)
    val chinese = parts.getOrNull(0)?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return if (chinese != jpSummary?.trim()) chinese else null
}

@Composable
private fun DetailStatus(
    status: OnAirLoadState,
    label: String,
    retry: () -> Unit,
    modifier: Modifier,
    chipFocusRequester: FocusRequester? = null,
    chipUp: FocusRequester = FocusRequester.Cancel,
    chipDown: FocusRequester = FocusRequester.Cancel,
) {
    val error = status as? OnAirLoadState.Error
    if (error == null) {
        // Ready / Loading は静かに保ち、予約済みジオメトリだけを確保する。
        Spacer(modifier)
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${label}: ${error.message} [${error.code}]",
            Modifier.weight(1f), color = KomorebiTheme.colors.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Surface(
            onClick = retry,
            modifier = Modifier.height(24.dp)
                .then(if (chipFocusRequester != null) Modifier.focusRequester(chipFocusRequester) else Modifier)
                .focusProperties {
                    up = chipUp
                    down = chipDown
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = KomorebiTheme.colors.surface,
                contentColor = KomorebiTheme.colors.textPrimary,
                focusedContainerColor = KomorebiTheme.colors.accent,
                focusedContentColor = onAccentColor(KomorebiTheme.colors.accent),
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, KomorebiTheme.colors.textPrimary))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Text("再試行", Modifier.padding(horizontal = 8.dp), fontSize = 10.sp)
        }
    }
}

internal fun canPlayOnAirRecording(program: RecordedProgram): Boolean =
    program.recordedVideo.hasKeyFrames != false || program.isRecording || program.recordedVideo.status == "Recording"

internal fun onAirCellKey(seriesId: Int, channelId: String?, slot: String, recordingId: Int) =
    "$seriesId:${channelId ?: "unknown"}:$slot:$recordingId"
