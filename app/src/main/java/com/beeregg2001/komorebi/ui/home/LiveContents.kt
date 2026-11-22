@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.ImageLoader
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.data.model.UiChannelState
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.live.logoUrlFor
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleListRow
import com.beeregg2001.komorebi.ui.theme.TvCardFocusSpec
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingStart
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.EpgViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import java.time.OffsetDateTime

private const val LIVE_GRID_COLUMNS = 3
private val HeatNormalColor = Color(0xFFFB923C)
private val HeatHighColor = Color(0xFFE53935)

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LiveContent(
    modifier: Modifier = Modifier,
    channelViewModel: ChannelViewModel,
    epgViewModel: EpgViewModel,
    groupedChannels: Map<String, List<Channel>>,
    onChannelClick: (Channel?) -> Unit,
    onFocusChannelChange: (String) -> Unit,
    mirakurunIp: String, mirakurunPort: String, konomiIp: String, konomiPort: String,
    topNavFocusRequester: FocusRequester, contentFirstItemRequester: FocusRequester,
    lastFocusedChannelId: String? = null,
    isReturningFromPlayer: Boolean = false, onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {},
    onContentReady: () -> Unit = {},
    topBarFocusRequester: FocusRequester? = null,
    onScrolledChanged: (Boolean) -> Unit = {},
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val liveRows by channelViewModel.liveRows.collectAsState()
    val isLoading by channelViewModel.isLoading.collectAsState()
    val channelError by channelViewModel.channelError.collectAsState()
    val sourceErrors by channelViewModel.sourceErrors.collectAsState()
    val channelLogoUrls by channelViewModel.channelLogoUrls.collectAsState()
    val backendType by settingsViewModel.backendType.collectAsState(initial = "KONOMITV")

    val imageLoader = rememberChannelLogoImageLoader()
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 }
    }
    LaunchedEffect(isScrolled) { onScrolledChanged(isScrolled) }
    val targetChannelFocusRequester = remember { FocusRequester() }
    val activeChipFocusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors
    val wideCardFocus = tvCardFocus(TvCardFamily.WIDE_CARD)

    val shouldCropLogo = backendType == "KONOMITV"
    val showDanmakuHeat = backendType != "EDCB"
    val upEdgeRequester = topBarFocusRequester ?: topNavFocusRequester

    var selectedGenreId by remember { mutableStateOf<String?>(null) }
    var focusedChannel by remember { mutableStateOf<UiChannelState?>(null) }
    var contentEntryReady by remember { mutableStateOf(false) }
    var lastFocusedChipRequester by remember(liveRows) { mutableStateOf(activeChipFocusRequester) }

    LaunchedEffect(liveRows.isEmpty()) {
        if (liveRows.isEmpty()) {
            contentEntryReady = false
        }
    }

    LaunchedEffect(liveRows) {
        if (selectedGenreId != null && liveRows.none { it.genreId == selectedGenreId }) {
            selectedGenreId = null
        }
        channelViewModel.prefetchChannelLogoUrls(liveRows.flatMap { it.channels }.map { it.channel })
    }

    val visibleRows = remember(liveRows, selectedGenreId) {
        if (selectedGenreId == null) {
            liveRows
        } else {
            liveRows.filter { it.genreId == selectedGenreId }
        }
    }

    val firstNonEmptyRowIdx = remember(visibleRows) {
        visibleRows.indexOfFirst { it.channels.isNotEmpty() }
    }
    val hasVisibleChannels = firstNonEmptyRowIdx != -1

    val chunkRows = remember(visibleRows) {
        buildList {
            visibleRows.forEach { row ->
                row.channels.chunked(LIVE_GRID_COLUMNS).forEachIndexed { chunkIdx, chunk ->
                    add(Triple(row.genreId, chunkIdx, chunk.size))
                }
            }
        }
    }
    val lastCardRequesters = remember(chunkRows) {
        chunkRows.associate { (genreId, chunkIdx, _) -> (genreId to chunkIdx) to FocusRequester() }
    }
    val nextChunkMap = remember(chunkRows) {
        buildMap {
            for (i in 0 until chunkRows.lastIndex) {
                val cur = chunkRows[i]
                val next = chunkRows[i + 1]
                put(cur.first to cur.second, next)
            }
        }
    }

    val showGenreChips = liveRows.size > 1

    LaunchedEffect(visibleRows, liveRows) {
        val visibleChannels = visibleRows.flatMap { it.channels }
        if (visibleChannels.isNotEmpty()) {
            val currentId = focusedChannel?.channel?.id
            if (currentId == null) {
                focusedChannel = visibleChannels.firstOrNull()
            } else {
                val updatedChannel = visibleChannels.find { it.channel.id == currentId }
                // N〇K除外モードの切替やジャンル絞り込みで注視中のチャンネルが一覧から消えたときは、
                // 次の番組バーが古いチャンネルを表示し続けないよう、表示可能な先頭で更新する。
                focusedChannel = updatedChannel ?: visibleChannels.firstOrNull()
            }
        }
    }

    LaunchedEffect(isReturningFromPlayer, aiFocusReturnTick, visibleRows, liveRows, contentEntryReady) {
        if ((isReturningFromPlayer || aiFocusReturnTick > 0) &&
            liveRows.isNotEmpty() && contentEntryReady
        ) {
            val fallback = if (isReturningFromPlayer) topNavFocusRequester else contentFirstItemRequester
            fun consumeReturn() {
                if (isReturningFromPlayer) onReturnFocusConsumed() else onAiReturnConsumed()
            }
            val targetId = lastFocusedChannelId
            if (targetId != null) {
                val flatIdx = findFlatChunkIndex(visibleRows, targetId)
                if (flatIdx != -1) {
                    listState.scrollToItem(flatIdx + if (showGenreChips) 1 else 0)
                    withFrameNanos { }
                    if (targetChannelFocusRequester.safeRequestFocusWithRetry("LiveChannelTarget")) {
                        consumeReturn()
                    } else {
                        fallback.safeRequestFocusWithRetry("LiveNavFallback")
                        consumeReturn()
                    }
                } else if (selectedGenreId != null && findFlatChunkIndex(liveRows, targetId) != -1) {
                    // 対象チャンネルが現在のジャンル絞り込み外にある場合は「すべて」へ戻して再探索する
                    selectedGenreId = null
                } else {
                    if (fallback.safeRequestFocusWithRetry("LiveNavFallback")) consumeReturn()
                }
            } else {
                if (fallback.safeRequestFocusWithRetry("LiveNavFallback")) consumeReturn()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (liveRows.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(contentFirstItemRequester)
                    .focusProperties {
                        up = upEdgeRequester
                        left = topNavFocusRequester
                    }
                    .onGloballyPositioned {
                        onContentReady()
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                if (backendType == "TIF" && !isLoading) {
                    Text(channelError ?: "テレビ入力がありません。設定から入力の登録とチャンネルスキャンを行ってください。", color = colors.textPrimary)
                } else {
                    CircularProgressIndicator(color = colors.textPrimary.copy(alpha = 0.5f))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(top = if (showGenreChips) 17.dp else 68.dp, bottom = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                if (showGenreChips) {
                    item(key = "genre-strip") {
                        LiveGenreFilterStrip(
                            liveRows = liveRows,
                            selectedGenreId = selectedGenreId,
                            activeChipFocusRequester = activeChipFocusRequester,
                            upEdgeRequester = upEdgeRequester,
                            leftEdgeRequester = topNavFocusRequester,
                            downEdgeRequester = if (hasVisibleChannels) null else contentFirstItemRequester,
                            onSelectGenre = { genreId ->
                                selectedGenreId = genreId
                            },
                            onChipFocused = { requester ->
                                lastFocusedChipRequester = requester
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 316.dp)
                        )
                    }
                }

                if (!hasVisibleChannels) {
                        item(key = "empty-genre") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .padding(
                                        start = TvContentPaddingStart,
                                        end = TvContentPaddingEnd
                                    )
                                    .focusRequester(contentFirstItemRequester)
                                    .focusProperties {
                                        up = if (showGenreChips) lastFocusedChipRequester else upEdgeRequester
                                        left = topNavFocusRequester
                                        right = FocusRequester.Cancel
                                    }
                                    .onGloballyPositioned {
                                        contentEntryReady = true
                                        onContentReady()
                                    }
                                    .focusable(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "チャンネルがありません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }

                    visibleRows.forEachIndexed { rowIdx, row ->
                        item(key = "hdr-${row.genreId}") {
                            Text(
                                text = row.genreLabel,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = colors.textPrimary.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = TvContentPaddingStart,
                                    end = TvContentPaddingEnd,
                                    top = if (rowIdx == 0) 0.dp else 6.dp
                                )
                            )
                        }

                        val chunks = row.channels.chunked(LIVE_GRID_COLUMNS)
                        itemsIndexed(
                            items = chunks,
                            key = { chunkIdx, _ -> "row-${row.genreId}-$chunkIdx" }
                        ) { chunkIdx, chunk ->
                            val isFirstRow = rowIdx == firstNonEmptyRowIdx && chunkIdx == 0
                            val chunkLastRequester = lastCardRequesters[row.genreId to chunkIdx]
                            val nextChunk = nextChunkMap[row.genreId to chunkIdx]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = TvContentPaddingStart,
                                        end = TvContentPaddingEnd
                                    )
                                    .graphicsLayer(clip = false),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                chunk.forEachIndexed { colIdx, uiState ->
                                    val isFirstCard = isFirstRow && colIdx == 0
                                    val isTarget = uiState.channel.id == lastFocusedChannelId
                                    val isRightEdge = colIdx == chunk.lastIndex
                                    val cachedLogoUrl = channelLogoUrls.logoUrlFor(uiState.channel)
                                    val nextRowLastCardRequester = if (nextChunk != null && colIdx >= nextChunk.third) {
                                        lastCardRequesters[nextChunk.first to nextChunk.second]
                                    } else {
                                        null
                                    }

                                    WideLiveChannelCard(
                                        uiState = uiState,
                                        cachedLogoUrl = cachedLogoUrl,
                                        resolveLogoUrl = { channelViewModel.getChannelLogoUrl(uiState.channel) },
                                        imageLoader = imageLoader,
                                        shouldCropLogo = shouldCropLogo,
                                        showDanmakuHeat = showDanmakuHeat,
                                        timeFormat = timeFormat,
                                        focusSpec = wideCardFocus,
                                        onClick = { onChannelClick(uiState.channel) },
                                        onFocused = {
                                            focusedChannel = uiState
                                            onFocusChannelChange(uiState.channel.id)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(
                                                if (isTarget) {
                                                    Modifier.focusRequester(targetChannelFocusRequester)
                                                } else Modifier
                                            )
                                            .then(
                                                if (isRightEdge && chunkLastRequester != null) {
                                                    Modifier.focusRequester(chunkLastRequester)
                                                } else Modifier
                                            )
                                            .then(
                                                if (isFirstCard) {
                                                    Modifier
                                                        .focusRequester(contentFirstItemRequester)
                                                        .onGloballyPositioned {
                                                            contentEntryReady = true
                                                            onContentReady()
                                                        }
                                                } else Modifier
                                            )
                                            .focusProperties {
                                                if (isFirstRow) {
                                                    up = if (showGenreChips) {
                                                        lastFocusedChipRequester
                                                    } else {
                                                        upEdgeRequester
                                                    }
                                                }
                                                if (nextRowLastCardRequester != null) {
                                                    down = nextRowLastCardRequester
                                                }
                                                if (colIdx == 0) left = topNavFocusRequester
                                                if (isRightEdge) right = FocusRequester.Cancel
                                            }
                                    )
                                }

                                repeat(LIVE_GRID_COLUMNS - chunk.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    sourceErrors["IPTV"]?.takeIf { it.isNotBlank() }?.let { error ->
                        item(key = "iptv-source-error") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = TvContentPaddingStart,
                                        end = TvContentPaddingEnd,
                                        top = 8.dp,
                                        bottom = 8.dp
                                    )
                            ) {
                                Text(
                                    text = "ネットテレビを取得できませんでした: $error",
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Button(
                                    onClick = channelViewModel::fetchChannels,
                                    modifier = Modifier
                                        .padding(top = 8.dp)
                                        .focusProperties { left = topNavFocusRequester },
                                ) {
                                    Text("再試行")
                                }
                            }
                        }
                    }
                }

                LiveNextProgramBar(
                    uiState = focusedChannel,
                    timeFormat = timeFormat,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = TvContentPaddingStart,
                            end = TvContentPaddingEnd,
                            top = 4.dp,
                            bottom = 12.dp
                        )
                )
            }
        }
    }
}

private fun findFlatChunkIndex(rows: List<LiveRowState>, targetChannelId: String): Int {
    var flatIdx = 0
    for (row in rows) {
        val channelIdx = row.channels.indexOfFirst { it.channel.id == targetChannelId }
        if (channelIdx != -1) {
            return flatIdx + 1 + (channelIdx / LIVE_GRID_COLUMNS)
        }
        val chunkCount = (row.channels.size + LIVE_GRID_COLUMNS - 1) / LIVE_GRID_COLUMNS
        flatIdx += 1 + chunkCount
    }
    return -1
}

@RequiresApi(Build.VERSION_CODES.O)
private fun formatLiveTime(timeStr: String?, timeFormat: String): String {
    if (timeStr.isNullOrEmpty()) return ""
    return runCatching {
        ProgramTimeFormatter.formatTime(
            OffsetDateTime.parse(timeStr).toDeviceTime().toLocalTime(),
            timeFormat
        )
    }.getOrDefault("")
}

@Composable
private fun LiveGenreFilterStrip(
    liveRows: List<LiveRowState>,
    selectedGenreId: String?,
    activeChipFocusRequester: FocusRequester,
    upEdgeRequester: FocusRequester,
    leftEdgeRequester: FocusRequester,
    downEdgeRequester: FocusRequester?,
    onSelectGenre: (String?) -> Unit,
    onChipFocused: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier
) {
    val chips = remember(liveRows) {
        buildList {
            add(null to "すべて")
            liveRows.forEach { add(it.genreId to it.genreLabel) }
        }
    }
    val chipRequesters = remember(chips) {
        chips.associate { (genreId, _) -> genreId to FocusRequester() }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(
            start = TvContentPaddingStart,
            end = TvContentPaddingEnd,
            top = 6.dp,
            bottom = 6.dp
        ),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { clip = false }
    ) {
        itemsIndexed(
            items = chips,
            key = { _, (genreId, _) -> genreId ?: "all" }
        ) { index, (genreId, label) ->
            val isSelected = selectedGenreId == genreId
            val isLast = index == chips.lastIndex
            val chipRequester = chipRequesters.getValue(genreId)
            LiveGenreChip(
                label = label,
                isSelected = isSelected,
                onClick = { onSelectGenre(genreId) },
                onFocused = { onChipFocused(chipRequester) },
                modifier = Modifier
                    .focusRequester(chipRequester)
                    .then(
                        if (isSelected) {
                            Modifier.focusRequester(activeChipFocusRequester)
                        } else Modifier
                    )
                    .focusProperties {
                        up = upEdgeRequester
                        if (downEdgeRequester != null) down = downEdgeRequester
                        if (index == 0) left = leftEdgeRequester
                        if (isLast) right = FocusRequester.Cancel
                    }
            )
        }
    }
}

@Composable
private fun LiveGenreChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(34.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleListRow),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.accentSoft else colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = if (isSelected) colors.accent else colors.textSecondary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) colors.accent.copy(alpha = 0.45f) else colors.borderSubtle
                ),
                shape = shape
            ),
            focusedBorder = Border(
                border = BorderStroke(TvCardFocusBorderWidth, colors.accent),
                shape = shape
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                    lineHeight = 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                color = when {
                    isFocused -> colors.textPrimary
                    isSelected -> colors.accent
                    else -> colors.textSecondary
                },
                maxLines = 1
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun WideLiveChannelCard(
    uiState: UiChannelState,
    cachedLogoUrl: String,
    resolveLogoUrl: suspend () -> String,
    imageLoader: ImageLoader,
    shouldCropLogo: Boolean,
    showDanmakuHeat: Boolean,
    timeFormat: String,
    focusSpec: TvCardFocusSpec,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    var fetchedLogoUrl by remember(uiState.channel.id) { mutableStateOf("") }
    LaunchedEffect(uiState.channel.id, cachedLogoUrl) {
        if (cachedLogoUrl.isBlank()) {
            fetchedLogoUrl = resolveLogoUrl()
        }
    }
    val logoUrl = cachedLogoUrl.ifBlank { fetchedLogoUrl }

    val present = uiState.channel.programPresent
    val timeRangeText = remember(present?.startTime, present?.endTime, timeFormat) {
        if (present == null) {
            ""
        } else {
            val start = formatLiveTime(present.startTime, timeFormat)
            val end = formatLiveTime(present.endTime, timeFormat)
            if (start.isNotEmpty() || end.isNotEmpty()) "$start - $end" else ""
        }
    }
    val clampedProgress = uiState.progress.coerceIn(0f, 1f)
    val progressPct = (clampedProgress * 100f).toInt()
    val force = uiState.jikkyoForce ?: 0
    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(124.dp)
            .onFocusChanged { state ->
                val focused = state.isFocused
                isFocused = focused
                if (focused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = focusSpec.restBorder,
            focusedBorder = focusSpec.focusedBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.textPrimary.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (logoUrl.isNotBlank()) {
                        AsyncImage(
                            imageLoader = imageLoader,
                            model = logoUrl,
                            contentDescription = uiState.name,
                            contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (showDanmakuHeat && force > 0) {
                    val heatColor = if (force > 500) HeatHighColor else HeatNormalColor
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(heatColor.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = null,
                            tint = heatColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = force.toString(),
                            style = MaterialTheme.typography.labelSmall.tnum.copy(
                                lineHeight = 13.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                )
                            ),
                            color = heatColor,
                            maxLines = 1
                        )
                    }
                }
            }

            Text(
                text = if (uiState.hasProgram && uiState.programTitle.isNotBlank()) {
                    uiState.programTitle
                } else {
                    "番組情報なし"
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (uiState.hasProgram) colors.textPrimary else colors.textMuted,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (present != null && timeRangeText.isNotEmpty()) {
                        Text(
                            text = timeRangeText,
                            style = MaterialTheme.typography.labelMedium.tnum,
                            color = colors.textSecondary,
                            maxLines = 1
                        )
                        Text(
                            text = "$progressPct%",
                            style = MaterialTheme.typography.labelMedium.tnum,
                            color = if (isFocused) colors.accent else colors.textSecondary,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = " ",
                            style = MaterialTheme.typography.labelMedium.tnum,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.textPrimary.copy(alpha = 0.12f))
                ) {
                    if (present != null && uiState.hasProgram) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(clampedProgress)
                                .fillMaxHeight()
                                .background(
                                    if (isFocused) colors.accent else colors.accent.copy(alpha = 0.65f)
                                )
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun LiveNextProgramBar(
    uiState: UiChannelState?,
    timeFormat: String,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val following = uiState?.channel?.programFollowing
    val nextStartTime = remember(following?.startTime, timeFormat) {
        formatLiveTime(following?.startTime, timeFormat)
    }
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface1)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.accentSoft)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "次の番組",
                style = MaterialTheme.typography.labelSmall.copy(
                    lineHeight = 13.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
                color = colors.accent,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (uiState != null) {
            Text(
                text = uiState.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (following != null && following.title.isNotBlank()) {
                if (nextStartTime.isNotEmpty()) {
                    Text(
                        text = nextStartTime,
                        style = MaterialTheme.typography.labelMedium.tnum,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = following.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = "次の番組情報なし",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
