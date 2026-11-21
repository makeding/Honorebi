@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.home.components.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingStart
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.RecordViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeContents(
    lastWatchedChannels: List<Channel>,
    watchHistory: List<KonomiHistoryProgram>,
    hotChannels: List<UiChannelState>,
    upcomingReserves: List<ReserveItem>,
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    groupedChannels: Map<String, List<Channel>>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onChannelClick: (Channel) -> Unit,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onReserveClick: (ReserveItem) -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (String) -> Unit,
    konomiIp: String, konomiPort: String,
    mirakurunIp: String, mirakurunPort: String,
    tabFocusRequester: FocusRequester,
    externalFocusRequester: FocusRequester,
    onUiReady: () -> Unit = {},
    modifier: Modifier = Modifier,
    lastFocusedChannelId: String? = null,
    lastFocusedProgramId: String? = null,
    isTopNavFocused: Boolean = false,
    homeViewModel: HomeViewModel,
    recordViewModel: RecordViewModel = hiltViewModel(),
    timeFormat: String,
    focusMemory: FocusMemory,
    tabIndex: Int = 0,
    topBarFocusRequester: FocusRequester? = null,
    onShowAllRecordings: () -> Unit = {},
    onShowOnAir: () -> Unit = {},
    hideAppsTab: Boolean = false,
    onScrolledChanged: (Boolean) -> Unit = {},
) {
    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 24 }
    }
    LaunchedEffect(isScrolled) { onScrolledChanged(isScrolled) }
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val launcherApps by homeViewModel.launcherApps.collectAsState()
    val colors = KomorebiTheme.colors

    val quickApps = remember(launcherApps) {
        launcherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }.take(5)
    }
    val showQuickApps = !hideAppsTab && quickApps.isNotEmpty()
    val topBarTarget = topBarFocusRequester ?: tabFocusRequester

    val tile0Requester = remember { FocusRequester() }
    val tile1Requester = remember { FocusRequester() }
    val tile2Requester = remember { FocusRequester() }
    val tile3Requester = remember { FocusRequester() }
    val quickAppsFirstRequester = remember { FocusRequester() }

    val isFirstItemRendered =
        remember { derivedStateOf { lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isFirstItemRendered.value) {
        if (isFirstItemRendered.value) onUiReady()
    }

    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            lazyListState.scrollToItem(0)
        }
    }

    fun clearLastClickedSection() {
        homeViewModel.lastClickedSection = null
        homeViewModel.lastClickedItemId = null
    }

    val availableSections =
        remember(watchHistory, lastWatchedChannels, hotChannels, genrePickup, upcomingReserves) {
            buildList {
                if (watchHistory.isNotEmpty()) add("history")
                if (lastWatchedChannels.isNotEmpty()) add("lastWatched")
                if (hotChannels.isNotEmpty()) add("hot")
                if (genrePickup.isNotEmpty()) add("pickup")
                if (upcomingReserves.isNotEmpty()) add("upcoming")
            }
        }
    val lastSectionId = availableSections.lastOrNull()

    val headerCount = 1 + (if (showQuickApps) 1 else 0)

    LaunchedEffect(focusMemory.requestId, availableSections, headerCount) {
        val request = focusMemory.request ?: return@LaunchedEffect
        if (request.tab != tabIndex) return@LaunchedEffect
        val section = request.section ?: return@LaunchedEffect
        val index = availableSections.indexOf(section)
        if (index < 0) {
            lazyListState.scrollToItem(0)
            androidx.compose.runtime.withFrameNanos { }
            if (externalFocusRequester.safeRequestFocusWithRetry("HomeRestore_MissingSection")) {
                focusMemory.consume(request.id)
            }
        } else {
            lazyListState.scrollToItem(headerCount + index)
            androidx.compose.runtime.withFrameNanos { }
            focusMemory.prepare(request.id)
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = modifier
            .fillMaxSize()
            .simpleListVerticalScrollbar(
                state = lazyListState,
                color = colors.textPrimary,
            ),
        contentPadding = PaddingValues(top = 76.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Zone 1: Quick Resume Hero (58%) + 2x2 Quick Shortcuts Grid (42%)
        item(key = "dashboard_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .padding(start = TvContentPaddingStart, end = TvContentPaddingEnd),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                QuickResumeHeroCard(
                    watchHistory = watchHistory,
                    recentRecordings = recentRecordings,
                    lastWatchedChannels = lastWatchedChannels,
                    hotChannels = hotChannels,
                    groupedChannels = groupedChannels,
                    timeFormat = timeFormat,
                    onHistoryClick = {
                        clearLastClickedSection()
                        onHistoryClick(it)
                    },
                    onChannelClick = {
                        clearLastClickedSection()
                        onChannelClick(it)
                    },
                    onNavigateToTab = {
                        clearLastClickedSection()
                        onNavigateToTab(it)
                    },
                    modifier = Modifier
                        .weight(0.58f)
                        .focusRequester(externalFocusRequester)
                        .focusProperties {
                            left = tabFocusRequester
                            right = tile0Requester
                            up = topBarTarget
                            down = when {
                                showQuickApps -> quickAppsFirstRequester
                                availableSections.isEmpty() -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        },
                )

                QuickShortcutsGrid(
                    lastWatchedChannelName = lastWatchedChannels.firstOrNull()?.name,
                    recentRecordingsCount = recentRecordings.size,
                    upcomingReserves = upcomingReserves,
                    timeFormat = timeFormat,
                    heroRequester = externalFocusRequester,
                    topBarRequester = topBarTarget,
                    tile0Requester = tile0Requester,
                    tile1Requester = tile1Requester,
                    tile2Requester = tile2Requester,
                    tile3Requester = tile3Requester,
                    onNavigateToTab = {
                        clearLastClickedSection()
                        onNavigateToTab(it)
                    },
                    onShowOnAir = {
                        clearLastClickedSection()
                        onShowOnAir()
                    },
                    onShowAllRecordings = {
                        clearLastClickedSection()
                        onShowAllRecordings()
                    },
                    modifier = Modifier.weight(0.42f),
                    isBottomRow = !showQuickApps && availableSections.isEmpty(),
                )
            }
        }

        // Zone 2: Quick Apps Strip (hidden if hideAppsTab or no apps)
        if (showQuickApps) {
            item(key = "dashboard_apps") {
                HomeQuickAppsStrip(
                    apps = quickApps,
                    onAppClick = {
                        clearLastClickedSection()
                        homeViewModel.launchApp(it)
                    },
                    onOpenAllApps = {
                        clearLastClickedSection()
                        onNavigateToTab("アプリ")
                    },
                    firstItemRequester = quickAppsFirstRequester,
                    leftEdgeFocusRequester = tabFocusRequester,
                    isLastSection = availableSections.isEmpty(),
                )
            }
        }

        // Zone 3+: Scrollable content rows
        if (watchHistory.isNotEmpty()) {
            item(key = "section_history") {
                WatchHistorySection(
                    watchHistory = watchHistory,
                    recentRecordings = recentRecordings,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    leftEdgeFocusRequester = tabFocusRequester,
                    onHistoryClick = onHistoryClick,
                    homeViewModel = homeViewModel,
                    sectionId = "history",
                    focusMemory = focusMemory,
                    isLastSection = lastSectionId == "history",
                )
            }
        }

        if (lastWatchedChannels.isNotEmpty()) {
            item(key = "section_last_watched") {
                LastWatchedSection(
                    channels = lastWatchedChannels,
                    groupedChannels = groupedChannels,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    leftEdgeFocusRequester = tabFocusRequester,
                    onChannelClick = onChannelClick,
                    homeViewModel = homeViewModel,
                    sectionId = "lastWatched",
                    focusMemory = focusMemory,
                    isLastSection = lastSectionId == "lastWatched",
                )
            }
        }

        if (hotChannels.isNotEmpty()) {
            item(key = "section_hot") {
                HotChannelSection(
                    hotChannels = hotChannels,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    leftEdgeFocusRequester = tabFocusRequester,
                    onChannelClick = onChannelClick,
                    homeViewModel = homeViewModel,
                    sectionId = "hot",
                    focusMemory = focusMemory,
                    isLastSection = lastSectionId == "hot",
                )
            }
        }

        if (genrePickup.isNotEmpty()) {
            item(key = "section_pickup") {
                GenrePickupSection(
                    genrePickup = genrePickup,
                    pickupGenreName = pickupGenreName,
                    pickupTimeSlot = pickupTimeSlot,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    leftEdgeFocusRequester = tabFocusRequester,
                    onProgramClick = onProgramClick,
                    onNavigateToTab = onNavigateToTab,
                    homeViewModel = homeViewModel,
                    sectionId = "pickup",
                    timeFormat = timeFormat,
                    focusMemory = focusMemory,
                    isLastSection = lastSectionId == "pickup",
                )
            }
        }

        if (upcomingReserves.isNotEmpty()) {
            item(key = "section_upcoming") {
                UpcomingReserveSection(
                    upcomingReserves = upcomingReserves,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    leftEdgeFocusRequester = tabFocusRequester,
                    onReserveClick = onReserveClick,
                    onNavigateToTab = onNavigateToTab,
                    homeViewModel = homeViewModel,
                    sectionId = "upcoming",
                    timeFormat = timeFormat,
                    focusMemory = focusMemory,
                    isLastSection = lastSectionId == "upcoming",
                )
            }
        }
    }
}

private fun Modifier.simpleListVerticalScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 4.dp,
    paddingEnd: Dp = 10.dp,
    verticalInset: Dp = 16.dp,
): Modifier = drawWithContent {
    drawContent()
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (totalItems <= 1 || visibleItems == 0 || visibleItems >= totalItems) return@drawWithContent

    val firstVisible = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
    val trackTop = verticalInset.toPx()
    val trackHeight = (size.height - trackTop * 2f).coerceAtLeast(1f)
    val thumbHeightRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.15f, 0.8f)
    val thumbHeight = trackHeight * thumbHeightRatio
    val thumbOffsetRatio =
        (firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1).toFloat())
            .coerceIn(0f, 1f)
    val thumbY = trackTop + (trackHeight - thumbHeight) * thumbOffsetRatio
    val x = size.width - width.toPx() - paddingEnd.toPx()
    val radius = CornerRadius(width.toPx() / 2f, width.toPx() / 2f)

    drawRoundRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(x, trackTop),
        size = Size(width.toPx(), trackHeight),
        cornerRadius = radius,
    )
    drawRoundRect(
        color = color.copy(alpha = 0.42f),
        topLeft = Offset(x, thumbY),
        size = Size(width.toPx(), thumbHeight),
        cornerRadius = radius,
    )
}
