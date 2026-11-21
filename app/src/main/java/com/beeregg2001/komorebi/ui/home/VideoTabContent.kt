@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.home.components.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleListRow
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingStart
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import java.time.OffsetDateTime

private const val TAG = "VideoTabContent"

private enum class VideoBannerFocus { RECORDINGS, ON_AIR, SMB }

@RequiresApi(Build.VERSION_CODES.O)
private fun buildRecordedHeroPreview(
    program: RecordedProgram,
    history: KonomiHistoryProgram?,
    timeFormat: String,
    isNetworkAvailable: Boolean,
): VideoHeroPreview {
    val startFormat = try {
        ProgramTimeFormatter.formatDateTime(
            OffsetDateTime.parse(program.startTime).toDeviceTime(),
            timeFormat,
            "yyyy/M/d(E)",
        )
    } catch (e: Exception) {
        program.startTime
    }
    val duration = if (program.duration > 0) program.duration else program.recordedVideo.duration
    val durationText = formatDurationBadge(duration)
    val playbackPos = history?.playback_position ?: program.playbackPosition
    val progress = if (duration > 0 && playbackPos > 5.0) {
        (playbackPos / duration).toFloat().coerceIn(0f, 1f)
    } else null
    val isCurrentlyRecording = program.isRecording || program.recordedVideo.status == "Recording"
    val metaLine = listOfNotNull(
        program.channel?.name?.takeIf { it.isNotBlank() },
        startFormat.takeIf { it.isNotBlank() },
        durationText.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val resumeLabel = if (progress != null) {
        "続きから ${formatDurationBadge(playbackPos)}"
    } else null

    return VideoHeroPreview(
        title = program.title,
        metaLine = metaLine,
        description = program.description.ifBlank {
            if (isNetworkAvailable) "" else "キャッシュ済みの番組情報"
        },
        progress = progress,
        resumeLabel = resumeLabel,
        isRecording = isCurrentlyRecording,
    )
}

@RequiresApi(Build.VERSION_CODES.O)
private fun buildHistoryHeroPreview(
    historyItem: KonomiHistoryProgram,
    matchedProgram: RecordedProgram?,
    timeFormat: String,
): VideoHeroPreview {
    val startTime = matchedProgram?.startTime ?: historyItem.program.start_time
    val startFormat = runCatching {
        ProgramTimeFormatter.formatDateTime(
            OffsetDateTime.parse(startTime).toDeviceTime(),
            timeFormat,
            "yyyy/M/d(E)",
        )
    }.getOrDefault("")
    val duration = matchedProgram?.duration ?: 0.0
    val durationText = formatDurationBadge(duration)
    val progress = if (duration > 0) {
        (historyItem.playback_position / duration).toFloat().coerceIn(0f, 1f)
    } else null
    val metaLine = listOfNotNull(
        matchedProgram?.channel?.name?.takeIf { it.isNotBlank() },
        startFormat.takeIf { it.isNotBlank() },
        durationText.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val watchedText = formatDurationBadge(historyItem.playback_position.coerceAtLeast(0.0))
        .ifBlank { "00:00" }

    return VideoHeroPreview(
        title = historyItem.program.title,
        metaLine = metaLine,
        description = matchedProgram?.description?.takeIf { it.isNotBlank() }
            ?: historyItem.program.description,
        progress = progress ?: 0.01f,
        resumeLabel = "続きから $watchedText",
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoTabContent(
    konomiIp: String,
    konomiPort: String,
    tabFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    getLogoUrl: suspend (String) -> String = { "" },
    shouldCropLogo: Boolean = false,
    onProgramClick: (RecordedProgram) -> Unit,
    onShowAllRecordings: () -> Unit,
    onShowSeriesList: () -> Unit,
    openedSeriesTitle: String?,
    onOpenedSeriesTitleChange: (String?) -> Unit,
    recordViewModel: RecordViewModel = hiltViewModel(),
    homeViewModel: com.beeregg2001.komorebi.viewmodel.HomeViewModel = hiltViewModel(),
    settingViewModel: SettingsViewModel = hiltViewModel(),
    watchHistory: List<KonomiHistoryProgram> = emptyList(),
    isTopNavFocused: Boolean = false,
    isReturningFromPlayer: Boolean = false,
    lastPlayedProgramId: String? = null,
    onReturnFocusConsumed: () -> Unit = {},
    timeFormat: String = "24H",
    isNetworkAvailable: Boolean = true,
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {},
    onContentReady: () -> Unit = {},
    onShowSmbLibrary: () -> Unit = {},
    onShowOnAir: () -> Unit = {},
    topBarFocusRequester: FocusRequester? = null,
) {
    val colors = KomorebiTheme.colors

    val listState = rememberLazyListState()
    val recentRowState = rememberLazyListState()
    val historyRowState = rememberLazyListState()
    val genreRowState = rememberLazyListState()
    val seriesRowState = rememberLazyListState()
    val recordingsRequester = remember { FocusRequester() }
    val onAirRequester = contentFirstItemRequester
    val smbRequester = remember { FocusRequester() }
    val cardFocusRequesters = remember { mutableStateMapOf<VideoCardFocus, FocusRequester>() }
    var lastFocusedCard by remember { mutableStateOf(homeViewModel.lastVideoCardFocus) }
    var lastFocusedBanner by remember { mutableStateOf(VideoBannerFocus.ON_AIR) }

    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val recentRecordingsLoading by recordViewModel.isRecentRecordingsLoading.collectAsState()
    val watchHistoryReady by homeViewModel.isWatchHistoryReady.collectAsState()
    val groupedSeries by recordViewModel.groupedSeries.collectAsState()
    val availableGenres by recordViewModel.availableGenres.collectAsState()
    val selectedGenre by recordViewModel.selectedSeriesGenre.collectAsState()

    val programDetail by recordViewModel.programDetail.collectAsState()
    val backendType by settingViewModel.backendType.collectAsState()

    LaunchedEffect(recordViewModel) {
        // Recent recordings are not part of App startup readiness.  Load them on
        // first entry to the surface that actually renders them.
        recordViewModel.fetchRecentRecordings(forceRefresh = false)
    }

    var focusedProgramId by remember { mutableStateOf<Int?>(null) }
    // The normal preview stays compact.  A player return may address a program
    // outside that first page though, so keep its stable item visible long
    // enough to return focus to what the viewer actually exited.
    val playbackReturnProgramId = lastPlayedProgramId
    val recentItems = remember(recentRecordings, playbackReturnProgramId) {
        recentRecordings.take(20).let { preview ->
            recentRecordings.find { it.id.toString() == playbackReturnProgramId }
                ?.takeUnless { candidate -> candidate in preview }
                ?.let { preview + it }
                ?: preview
        }
    }
    val historyItems = remember(watchHistory, playbackReturnProgramId) {
        watchHistory.take(20).let { preview ->
            watchHistory.find { it.program.id.toString() == playbackReturnProgramId }
                ?.takeUnless { candidate -> candidate in preview }
                ?.let { preview + it }
                ?: preview
        }
    }
    val historyByProgramId = remember(watchHistory) {
        watchHistory.associateBy { it.program.id.toString() }
    }
    val recentByProgramId = remember(recentRecordings) {
        recentRecordings.associateBy { it.id.toString() }
    }
    val genreList = remember(availableGenres) { listOf<String?>(null) + availableGenres }
    val seriesPreviewItems = remember(groupedSeries, selectedGenre) {
        val source =
            if (selectedGenre == null) groupedSeries.values.asSequence().flatten()
            else groupedSeries[selectedGenre].orEmpty().asSequence()
        source.take(20).toList()
    }
    val recentProgramIds = remember(recentItems) { recentItems.map { it.id.toString() } }
    val historyProgramIds = remember(historyItems) { historyItems.map { it.program.id.toString() } }
    val recentFocus = rememberVideoRowFocus(recentProgramIds, recentRowState)
    val historyFocus = rememberVideoRowFocus(historyProgramIds, historyRowState)
    val genreFocus = rememberVideoRowFocus(genreList.map { it ?: "All" }, genreRowState)
    val seriesFocus = rememberVideoRowFocus(seriesPreviewItems.map { it.displayTitle }, seriesRowState)

    val defaultHeroPreview = remember(
        recentItems.firstOrNull(),
        historyItems.firstOrNull(),
        historyByProgramId,
        recentByProgramId,
        timeFormat,
        isNetworkAvailable,
    ) {
        val firstRecent = recentItems.firstOrNull()
        val firstHistory = historyItems.firstOrNull()
        when {
            firstRecent != null -> buildRecordedHeroPreview(
                program = firstRecent,
                history = historyByProgramId[firstRecent.id.toString()],
                timeFormat = timeFormat,
                isNetworkAvailable = isNetworkAvailable,
            )
            firstHistory != null -> buildHistoryHeroPreview(
                historyItem = firstHistory,
                matchedProgram = recentByProgramId[firstHistory.program.id.toString()],
                timeFormat = timeFormat,
            )
            else -> VideoHeroPreview(title = "録画番組がありません")
        }
    }

    var focusedHeroPreview by remember { mutableStateOf<VideoHeroPreview?>(null) }

    // N〇K除外モードの ON/OFF 切替では一覧が作り直されるため、注視していた N〇K 録画が
    // 消えてもバナー概要に残らないよう、ヒーローを初期状態へ戻して再レンダリングする。
    val isNhkExcluded = settingViewModel.nhkExclusionState.collectAsState().value.isActive
    var heroExclusionBaseline by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isNhkExcluded) {
        if (heroExclusionBaseline != null && heroExclusionBaseline != isNhkExcluded) {
            focusedProgramId = null
            focusedHeroPreview = null
        }
        heroExclusionBaseline = isNhkExcluded
    }

    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            // The tab bar can regain focus while this lazy list is showing a lower
            // row.  Returning to the first row keeps the next Down on On Air.
            listState.scrollToItem(0)
            focusedHeroPreview = null
            focusedProgramId = null
        }
    }

    LaunchedEffect(programDetail, focusedProgramId) {
        val detail = programDetail
        if (detail != null && detail.id == focusedProgramId) {
            val newDesc = detail.description.ifBlank { "番組概要がありません" }
            val current = focusedHeroPreview
            if (current != null && current.title == detail.title) {
                focusedHeroPreview = current.copy(description = newDesc)
            }
        }
    }

    suspend fun restoreVideoFocus(requestedProgramId: String?, fallbackToLastBanner: Boolean): Boolean {
        val target = VideoFocusPolicy.restoreTarget(
            requestedProgramId = requestedProgramId,
            priorTarget = lastFocusedCard,
            recentProgramIds = recentProgramIds,
            historyProgramIds = historyProgramIds,
        )
        if (target != null) {
            val rowIndex = VideoFocusPolicy.rowItemIndex(target, recentProgramIds, historyProgramIds)
            if (rowIndex >= 0) {
                // Create the stable requester before scrolling.  The lazily composed
                // card below reads this same instance once its row becomes visible.
                val requester = cardFocusRequesters.getOrPut(target) { FocusRequester() }
                listState.scrollToItem(VideoFocusPolicy.columnItemIndex(target, recentItems.isNotEmpty()))
                when (target.row) {
                    VideoFocusRow.RECENT -> recentRowState.scrollToItem(maxOf(0, rowIndex - 1))
                    VideoFocusRow.HISTORY -> historyRowState.scrollToItem(maxOf(0, rowIndex - 1))
                }
                withFrameNanos { } // Let the row attach the keyed card before asking it for focus.
                if (requester.safeRequestFocusWithRetry("VideoTabRestore_${target.row}_${target.programId}")) {
                    return true
                }
            }
        }

        val fallback = if (fallbackToLastBanner) {
            when (lastFocusedBanner) {
                VideoBannerFocus.RECORDINGS -> recordingsRequester
                VideoBannerFocus.ON_AIR -> onAirRequester
                VideoBannerFocus.SMB -> smbRequester
            }
        } else {
            onAirRequester
        }
        listState.scrollToItem(0)
        withFrameNanos { }
        return fallback.safeRequestFocusWithRetry("VideoTabRestoreFallback")
    }

    suspend fun focusPlayerReturnTarget(target: VideoCardFocus): Boolean {
        val rowIndex = VideoFocusPolicy.rowItemIndex(target, recentProgramIds, historyProgramIds)
        val requester = cardFocusRequesters.getOrPut(target) { FocusRequester() }
        listState.scrollToItem(VideoFocusPolicy.columnItemIndex(target, recentItems.isNotEmpty()))
        when (target.row) {
            VideoFocusRow.RECENT -> recentRowState.scrollToItem(maxOf(0, rowIndex - 1))
            VideoFocusRow.HISTORY -> historyRowState.scrollToItem(maxOf(0, rowIndex - 1))
        }
        withFrameNanos { }
        return requester.safeRequestFocusWithRetry("VideoTabPlayerReturn_${target.row}_${target.programId}")
    }

    suspend fun restorePlayerReturnFocus(playedProgramId: String?): Boolean {
        // The card may arrive from either the recording refresh or the local
        // history write made by player disposal. Do not consume B's return or
        // move focus to a temporary fallback until both are settled.
        if (VideoFocusPolicy.shouldWaitForPlayerReturn(
                playedProgramId = playedProgramId,
                recentProgramIds = recentProgramIds,
                historyProgramIds = historyProgramIds,
                recentLoading = recentRecordingsLoading,
                historyReady = watchHistoryReady,
            )
        ) return false

        val fallbackTarget = VideoFocusPolicy.playerReturnTarget(
            playedProgramId = playedProgramId,
            recentProgramIds = recentProgramIds,
            historyProgramIds = historyProgramIds,
        )
        if (fallbackTarget != null) return focusPlayerReturnTarget(fallbackTarget)

        listState.scrollToItem(0)
        withFrameNanos { }
        return onAirRequester.safeRequestFocusWithRetry("VideoTabPlayerReturnFallback")
    }

    // Player and AI returns are deliberately serialized here: scroll the target row,
    // then request the attached keyed card.  Neither card composition nor a second
    // effect is allowed to race this restore.
    LaunchedEffect(
        aiFocusReturnTick,
        isReturningFromPlayer,
        lastPlayedProgramId,
        recentProgramIds,
        historyProgramIds,
        recentRecordingsLoading,
        watchHistoryReady,
    ) {
        when {
            isReturningFromPlayer -> {
                if (restorePlayerReturnFocus(lastPlayedProgramId)) {
                    onReturnFocusConsumed()
                }
            }
            aiFocusReturnTick > 0 -> {
                if (restoreVideoFocus(requestedProgramId = null, fallbackToLastBanner = true)) {
                    onAiReturnConsumed()
                }
            }
        }
    }

    val upTarget = topBarFocusRequester ?: tabFocusRequester
    val upToTopBarModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            upTarget.safeRequestFocus(TAG)
            true
        } else {
            false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VideoCompactHero(
            state = focusedHeroPreview ?: defaultHeroPreview,
            modifier = Modifier.padding(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 8.dp,
                bottom = 8.dp,
            ),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Item 0: Compact Quick Entry Pills (index 0 keeps VideoFocusPolicy.columnItemIndex intact)
            item {
                Row(
                    modifier = Modifier
                        .padding(
                            start = TvContentPaddingStart,
                            top = 4.dp,
                            end = TvContentPaddingEnd,
                        )
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RecordListBannerButton(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(recordingsRequester)
                            .then(upToTopBarModifier)
                            .focusProperties {
                                left = tabFocusRequester
                                right = onAirRequester
                                up = upTarget
                            },
                        onClick = { recordViewModel.clearSearch(); onShowAllRecordings() },
                        onFocus = {
                            focusedProgramId = null
                            lastFocusedCard = null
                            lastFocusedBanner = VideoBannerFocus.RECORDINGS
                            focusedHeroPreview = VideoHeroPreview(
                                title = "録画リスト",
                                metaLine = if (recentRecordings.isNotEmpty()) "${recentRecordings.size} 番組" else "録画一覧",
                                description = "すべての録画番組を一覧・検索",
                            )
                        },
                    )

                    OnAirBannerButton(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(onAirRequester)
                            .onGloballyPositioned { onContentReady() }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    recentFocus.requestEntryFocus() || historyFocus.requestEntryFocus()
                                } else {
                                    false
                                }
                            }
                            .then(upToTopBarModifier)
                            .focusProperties {
                                left = recordingsRequester
                                right = smbRequester
                                up = upTarget
                            },
                        onClick = onShowOnAir,
                        onFocus = {
                            focusedProgramId = null
                            lastFocusedCard = null
                            lastFocusedBanner = VideoBannerFocus.ON_AIR
                            focusedHeroPreview = VideoHeroPreview(
                                title = "放送中",
                                metaLine = "放送中シリーズ",
                                description = "放送中の番組をシリーズごとに表示",
                            )
                        },
                    )

                    SmbLibraryBannerButton(
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(smbRequester)
                            .then(upToTopBarModifier)
                            .focusProperties {
                                left = onAirRequester
                                right = FocusRequester.Cancel
                                up = upTarget
                            },
                        onClick = { onShowSmbLibrary() },
                        onFocus = {
                            focusedProgramId = null
                            lastFocusedCard = null
                            lastFocusedBanner = VideoBannerFocus.SMB
                            focusedHeroPreview = VideoHeroPreview(
                                title = "ファイルライブラリ",
                                metaLine = "SMB ネットワーク",
                                description = "共有フォルダ内の動画ファイルを再生",
                            )
                        },
                    )
                }
            }

            if (recentItems.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "最近の録画",
                            icon = Icons.Default.PlayCircle,
                            modifier = Modifier.padding(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                            ),
                        )
                        LazyRow(
                            state = recentRowState,
                            modifier = recentFocus.groupModifier(),
                            contentPadding = PaddingValues(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(
                                recentItems,
                                key = { _, it -> "rec_${it.id}" },
                            ) { index, program ->
                                val focusTarget = remember(program.id) {
                                    VideoCardFocus(VideoFocusRow.RECENT, program.id.toString())
                                }
                                val focusRequester = remember(focusTarget) {
                                    cardFocusRequesters.getOrPut(focusTarget) { FocusRequester() }
                                }
                                val isCurrentlyRecording =
                                    program.isRecording || program.recordedVideo.status == "Recording"
                                val matchedHistory = historyByProgramId[program.id.toString()]
                                VideoRecentRecordCard(
                                    program = program,
                                    history = matchedHistory,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    focusRequester = focusRequester,
                                    timeFormat = timeFormat,
                                    onClick = {
                                        homeViewModel.lastVideoCardFocus = focusTarget
                                        onProgramClick(program)
                                    },
                                    onFocus = {
                                        lastFocusedCard = focusTarget
                                        focusedProgramId = program.id
                                        if (isNetworkAvailable) {
                                            recordViewModel.fetchProgramDetail(program.id)
                                        }
                                        focusedHeroPreview = buildRecordedHeroPreview(
                                            program = program,
                                            history = matchedHistory,
                                            timeFormat = timeFormat,
                                            isNetworkAvailable = isNetworkAvailable,
                                        )
                                    },
                                    isCurrentlyRecording = isCurrentlyRecording,
                                    allowNetworkImages = isNetworkAvailable,
                                    modifier = Modifier
                                        .then(recentFocus.itemModifier(program.id.toString()))
                                        .focusProperties {
                                            if (index == 0) left = tabFocusRequester
                                            if (index == recentItems.lastIndex) right =
                                                FocusRequester.Cancel
                                        },
                                    backendType = backendType,
                                )
                            }
                        }
                    }
                }
            }

            if (historyItems.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "続きから見る",
                            icon = Icons.Default.PlayCircle,
                            modifier = Modifier.padding(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                            ),
                        )
                        LazyRow(
                            state = historyRowState,
                            modifier = historyFocus.groupModifier(),
                            contentPadding = PaddingValues(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(
                                historyItems,
                                key = { _, it -> "hist_${it.program.id}" },
                            ) { index, historyItem ->
                                val historyProgramId = historyItem.program.id.toString()
                                val focusTarget = remember(historyProgramId) {
                                    VideoCardFocus(VideoFocusRow.HISTORY, historyProgramId)
                                }
                                val focusRequester = remember(focusTarget) {
                                    cardFocusRequesters.getOrPut(focusTarget) { FocusRequester() }
                                }
                                val matchedProgram =
                                    recentByProgramId[historyItem.program.id.toString()]
                                VideoWatchHistoryCard(
                                    historyItem = historyItem,
                                    matchedProgram = matchedProgram,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    focusRequester = focusRequester,
                                    timeFormat = timeFormat,
                                    onClick = {
                                        homeViewModel.lastVideoCardFocus = focusTarget
                                        val programToPlay =
                                            matchedProgram?.copy(playbackPosition = historyItem.playback_position)
                                                ?: KonomiDataMapper.toDomainModel(historyItem)
                                        onProgramClick(programToPlay)
                                    },
                                    onFocus = {
                                        lastFocusedCard = focusTarget
                                        val videoId = matchedProgram?.id ?: try {
                                            historyItem.program.id.toString().toInt()
                                        } catch (e: Exception) {
                                            0
                                        }
                                        if (videoId != 0) {
                                            focusedProgramId = videoId
                                            if (isNetworkAvailable) {
                                                recordViewModel.fetchProgramDetail(videoId)
                                            }
                                        }
                                        focusedHeroPreview = buildHistoryHeroPreview(
                                            historyItem = historyItem,
                                            matchedProgram = matchedProgram,
                                            timeFormat = timeFormat,
                                        )
                                    },
                                    modifier = Modifier
                                        .then(historyFocus.itemModifier(historyProgramId))
                                        .focusProperties {
                                            if (index == 0) left = tabFocusRequester
                                            if (index == historyItems.lastIndex) right =
                                                FocusRequester.Cancel
                                        },
                                    allowNetworkImages = isNetworkAvailable,
                                    backendType = backendType,
                                )
                            }
                        }
                    }
                }
            }

            if (groupedSeries.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(
                            title = "ジャンル別シリーズ",
                            icon = Icons.Default.VideoLibrary,
                            modifier = Modifier.padding(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                            ),
                        )
                        LazyRow(
                            state = genreRowState,
                            modifier = genreFocus.groupModifier(),
                            contentPadding = PaddingValues(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                                top = 8.dp,
                                bottom = 8.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(
                                genreList,
                                key = { _, it -> it ?: "All" },
                            ) { index, genre ->
                                val isSelected = genre == selectedGenre
                                var isFocused by remember { mutableStateOf(false) }
                                val chipShape = RoundedCornerShape(20.dp)
                                Surface(
                                    onClick = { recordViewModel.updateSeriesGenre(genre) },
                                    modifier = Modifier
                                        .then(genreFocus.itemModifier(genre ?: "All"))
                                        .height(40.dp)
                                        .focusProperties {
                                            if (index == 0) left = tabFocusRequester
                                            if (index == genreList.lastIndex) right =
                                                FocusRequester.Cancel
                                        }
                                        .onFocusChanged {
                                            isFocused = it.hasFocus
                                            if (isFocused) focusedProgramId = null
                                        },
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleListRow),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = if (isSelected) colors.accentSoft else colors.surface1,
                                        focusedContainerColor = colors.surface2,
                                        contentColor = if (isSelected) colors.accent else colors.textSecondary,
                                        focusedContentColor = colors.textPrimary,
                                    ),
                                    shape = ClickableSurfaceDefaults.shape(chipShape),
                                    border = ClickableSurfaceDefaults.border(
                                        border = Border(
                                            BorderStroke(
                                                1.dp,
                                                if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.borderSubtle,
                                            ),
                                            shape = chipShape,
                                        ),
                                        focusedBorder = Border(
                                            BorderStroke(TvCardFocusBorderWidth, colors.accent),
                                            shape = chipShape,
                                        ),
                                    ),
                                ) {
                                    Text(
                                        text = genre ?: "すべて",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = 20.dp,
                                            vertical = 9.dp,
                                        ),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            state = seriesRowState,
                            modifier = seriesFocus.groupModifier(),
                            contentPadding = PaddingValues(
                                start = TvContentPaddingStart,
                                end = TvContentPaddingEnd,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(
                                seriesPreviewItems,
                                key = { _, it -> it.displayTitle },
                            ) { index, series ->
                                VideoSeriesCard(
                                    series = series,
                                    konomiIp = konomiIp,
                                    konomiPort = konomiPort,
                                    onClick = {
                                        recordViewModel.searchRecordings(series.searchKeyword)
                                        onShowAllRecordings()
                                    },
                                    onFocus = {
                                        focusedProgramId = null
                                        focusedHeroPreview = VideoHeroPreview(
                                            title = series.displayTitle,
                                            metaLine = "シリーズ · ${series.programCount} エピソード",
                                            description = series.description.ifBlank {
                                                "「${series.displayTitle}」の録画一覧"
                                            },
                                        )
                                    },
                                    modifier = Modifier
                                        .then(seriesFocus.itemModifier(series.displayTitle))
                                        .focusProperties {
                                            if (index == 0) left = tabFocusRequester
                                            if (index == seriesPreviewItems.lastIndex) right =
                                                FocusRequester.Cancel
                                            down = FocusRequester.Cancel
                                        },
                                    allowNetworkImages = isNetworkAvailable,
                                    backendType = backendType,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
