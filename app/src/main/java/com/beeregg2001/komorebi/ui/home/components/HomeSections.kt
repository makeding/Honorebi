@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
// ★ 変更: TvLazy系のインポートを削除し、標準のLazy系のインポートに変更
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import com.beeregg2001.komorebi.ui.home.HomeFocusRequest
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.home.FocusMemory
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleListRow
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingStart
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

/** Scroll only for an explicit command, then let its attached card request focus. */
@Composable
internal fun restoredRowRequest(
    ids: List<String>,
    rowState: LazyListState,
    memory: FocusMemory,
    sectionId: String,
): HomeFocusRequest? {
    var ready by remember { mutableStateOf<HomeFocusRequest?>(null) }
    val request = memory.request
    LaunchedEffect(request?.id, memory.preparedId, ids) {
        ready = null
        if (request?.section != sectionId || memory.preparedId != request.id || ids.isEmpty()) {
            return@LaunchedEffect
        }
        val index = ids.indexOf(request.itemId).takeIf { it >= 0 } ?: 0
        rowState.scrollToItem(index)
        withFrameNanos { }
        if (memory.request?.id == request.id) ready = request.copy(itemId = ids[index])
    }
    return ready?.takeIf { it.id == request?.id }
}

@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = colors.textSecondary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
    }
}

@Composable
fun NavigationLinkButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    isBottomRow: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(TvCardRadiusListRow)
    Box(
        modifier = Modifier
            .padding(start = TvContentPaddingStart, top = 8.dp, bottom = 8.dp)
            .graphicsLayer { clip = false }
    ) {
        Button(
            onClick = onClick,
            shape = ButtonDefaults.shape(shape),
            scale = ButtonDefaults.scale(focusedScale = TvCardFocusScaleListRow),
            colors = ButtonDefaults.colors(
                containerColor = colors.surface1,
                contentColor = colors.textSecondary,
                focusedContainerColor = colors.surface2,
                focusedContentColor = colors.textPrimary
            ),
            border = ButtonDefaults.border(
                border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
                focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = shape)
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            modifier = Modifier
                .height(40.dp)
                .graphicsLayer { clip = false }
                .focusProperties {
                    left = leftEdgeFocusRequester
                    right = FocusRequester.Cancel
                    if (isBottomRow) down = FocusRequester.Cancel
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun HomeQuickAppsStrip(
    apps: List<LauncherApp>,
    onAppClick: (LauncherApp) -> Unit,
    onOpenAllApps: () -> Unit,
    firstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    modifier: Modifier = Modifier,
    isLastSection: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val visibleApps = remember(apps) { apps.take(5) }
    val tileShape = RoundedCornerShape(TvCardRadiusListRow)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            title = "よく使うアプリ",
            icon = Icons.Default.Apps,
            modifier = Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TvContentPaddingStart, end = TvContentPaddingEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleApps.forEachIndexed { index, app ->
                HomeQuickAppTile(
                    app = app,
                    shape = tileShape,
                    onClick = { onAppClick(app) },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .then(
                            if (index == 0 && firstItemRequester != null) {
                                Modifier.focusRequester(firstItemRequester)
                            } else {
                                Modifier
                            }
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (isLastSection) down = FocusRequester.Cancel
                        },
                )
            }

            // 6th tile: "すべて" (+) -> Apps tab
            var allFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onOpenAllApps,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .then(
                        if (visibleApps.isEmpty() && firstItemRequester != null) {
                            Modifier.focusRequester(firstItemRequester)
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        if (visibleApps.isEmpty()) left = leftEdgeFocusRequester
                        right = FocusRequester.Cancel
                        if (isLastSection) down = FocusRequester.Cancel
                    }
                    .onFocusChanged { allFocused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(tileShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleListRow),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.surface1,
                    focusedContainerColor = colors.surface2,
                    contentColor = colors.textSecondary,
                    focusedContentColor = colors.textPrimary,
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = tileShape),
                    focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = tileShape),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = if (allFocused) colors.accent else colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "すべて",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (allFocused) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeQuickAppTile(
    app: LauncherApp,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val iconModel = remember(app.icon, app.artworkCacheVersion) {
        app.icon?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCacheKey("launcher-quick-icon:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .diskCacheKey("launcher-quick-icon:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleListRow),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = shape),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (iconModel != null) {
                    AsyncImage(
                        model = iconModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LastWatchedSection(
    channels: List<Channel>,
    groupedChannels: Map<String, List<Channel>>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    focusMemory: FocusMemory,
    isLastSection: Boolean = false,
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(channels.map { it.id }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "最近見たチャンネル",
            Icons.Default.History,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(channels, key = { _, it -> "ch_${it.id}" }) { index, channel ->
                val liveChannel = remember(groupedChannels, channel.id) {
                    groupedChannels.values.flatten().find { it.id == channel.id }
                }

                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == channel.id
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_LastWatched")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                LastWatchedChannelCard(
                    channel = channel,
                    liveChannel = liveChannel,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    onClick = { onChannelClick(channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = liveChannel?.programPresent?.title ?: channel.name,
                                subtitle = channel.name,
                                description = liveChannel?.programPresent?.description ?: "",
                                channelId = channel.id,
                                isThumbnail = false,
                                tag = "最近見た"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == channels.size - 1) right = FocusRequester.Cancel
                            if (isLastSection) down = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = channel.id
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HotChannelSection(
    hotChannels: List<UiChannelState>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onChannelClick: (Channel) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    focusMemory: FocusMemory,
    isLastSection: Boolean = false,
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(hotChannels.map { it.channel.id }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "今、盛り上がっているチャンネル",
            Icons.Default.TrendingUp,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(hotChannels, key = { _, it -> "hot_${it.channel.id}" }) { index, uiState ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == uiState.channel.id
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_Hot")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                HotChannelCard(
                    uiState = uiState,
                    getLogoUrl = getLogoUrl,
                    shouldCropLogo = shouldCropLogo,
                    onClick = { onChannelClick(uiState.channel) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = uiState.programTitle,
                                subtitle = uiState.name,
                                description = uiState.channel.programPresent?.description ?: "",
                                channelId = uiState.channel.id,
                                isThumbnail = false,
                                tag = "盛り上がり"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == hotChannels.size - 1) right = FocusRequester.Cancel
                            if (isLastSection) down = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = uiState.channel.id
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WatchHistorySection(
    watchHistory: List<KonomiHistoryProgram>,
    recentRecordings: List<RecordedProgram>,
    konomiIp: String, konomiPort: String,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    focusMemory: FocusMemory,
    isLastSection: Boolean = false,
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(watchHistory.map { it.program.id.toString() }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "続きから見る",
            Icons.Default.PlayCircle,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd, bottom = 4.dp)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                watchHistory,
                key = { _, it -> "hist_${it.program.id}" }) { index, history ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == history.program.id.toString()
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_History")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                val matchedProgram =
                    recentRecordings.find { it.id.toString() == history.program.id.toString() }

                WatchHistoryCard(
                    history = history,
                    matchedProgram = matchedProgram,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onHistoryClick(history) },
                    onFocus = { progressVal, thumbnailUrl ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = history.program.title,
                                subtitle = "",
                                description = history.program.description,
                                imageUrl = thumbnailUrl,
                                isThumbnail = true,
                                tag = "視聴履歴",
                                progress = progressVal
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == watchHistory.size - 1) right = FocusRequester.Cancel
                            if (isLastSection) down = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = history.program.id.toString()
                            }
                        }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UpcomingReserveSection(
    upcomingReserves: List<ReserveItem>,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onReserveClick: (ReserveItem) -> Unit,
    onNavigateToTab: (String) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    timeFormat: String,
    focusMemory: FocusMemory,
    isLastSection: Boolean = false,
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(upcomingReserves.map { it.id.toString() }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "これからの録画予約",
            Icons.Default.RadioButtonChecked,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(upcomingReserves, key = { _, it -> "res_${it.id}" }) { index, reserve ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == reserve.id.toString()
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_Reserve")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                UpcomingReserveCard(
                    reserve = reserve,
                    onClick = { onReserveClick(reserve) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = reserve.program.title,
                                subtitle = "$startFormat - ${reserve.channel.name}",
                                description = reserve.program.description ?: "",
                                channelId = reserve.channel.id,
                                isThumbnail = false,
                                tag = "録画予約"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == upcomingReserves.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = reserve.id.toString()
                            }
                        },
                    timeFormat = timeFormat
                )
            }
        }
        NavigationLinkButton(
            label = "録画予約リストを表示",
            icon = Icons.Default.List,
            onClick = { onNavigateToTab("録画予約") },
            leftEdgeFocusRequester = leftEdgeFocusRequester,
            isBottomRow = isLastSection,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GenrePickupSection(
    genrePickup: List<Pair<EpgProgram, String>>,
    pickupGenreName: String,
    pickupTimeSlot: String,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onProgramClick: (EpgProgram) -> Unit,
    onNavigateToTab: (String) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    timeFormat: String,
    focusMemory: FocusMemory,
    isLastSection: Boolean = false,
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(genrePickup.map { it.first.id }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        val timePrefix = when (pickupTimeSlot) {
            "朝" -> "今朝の"; "昼" -> "今日の"; else -> "今夜の"
        }
        SectionHeader(
            "${timePrefix}${pickupGenreName}ピックアップ",
            Icons.Default.Star,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                genrePickup,
                key = { _, it -> "pick_${it.first.id}" }) { index, (program, channelName) ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == program.id
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_Pickup")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                GenrePickupCard(
                    program = program,
                    channelName = channelName,
                    timeSlot = pickupTimeSlot,
                    onClick = { onProgramClick(program) },
                    onFocus = { startFormat ->
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = program.title,
                                subtitle = "$startFormat - $channelName",
                                description = program.description,
                                channelId = program.channel_id,
                                isThumbnail = false,
                                tag = "ピックアップ"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == genrePickup.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = program.id
                            }
                        },
                    timeFormat = timeFormat
                )
            }
        }
        NavigationLinkButton(
            label = "番組表を開く",
            icon = Icons.Default.CalendarToday,
            onClick = { onNavigateToTab("番組表") },
            leftEdgeFocusRequester = leftEdgeFocusRequester,
            isBottomRow = isLastSection,
        )
    }
}

@Composable
fun LauncherAppSection(
    apps: List<LauncherApp>,
    modifier: Modifier = Modifier,
    contentFirstItemRequester: FocusRequester? = null,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
    onAppClick: (LauncherApp) -> Unit,
    onAppManage: (LauncherApp) -> Unit,
    onUpdateHeroInfo: (HomeHeroInfo) -> Unit = {},
    homeViewModel: HomeViewModel,
    sectionId: String,
    focusMemory: FocusMemory
) {
    val rowState = rememberLazyListState()

    val restoreRequest = restoredRowRequest(apps.map { it.stableId }, rowState, focusMemory, sectionId)

    Column(modifier = Modifier.animateContentSize()) {
        SectionHeader(
            "アプリ",
            Icons.Default.Apps,
            Modifier.padding(start = TvContentPaddingStart, end = TvContentPaddingEnd)
        )
        LazyRow(
            state = rowState,
            modifier = modifier,
            contentPadding = PaddingValues(
                start = TvContentPaddingStart,
                end = TvContentPaddingEnd,
                top = 12.dp,
                bottom = 12.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(apps, key = { _, it -> "app_${it.stableId}" }) { index, app ->
                val specificRequester = remember { FocusRequester() }
                LaunchedEffect(restoreRequest?.id) {
                    if (restoreRequest?.section == sectionId &&
                        restoreRequest.itemId == app.stableId
                    ) {
                        if (specificRequester.safeRequestFocusWithRetry("HomeRestore_App")) {
                            focusMemory.consume(restoreRequest.id)
                        }
                    }
                }

                LauncherAppCard(
                    app = app,
                    onClick = { onAppClick(app) },
                    onManage = { onAppManage(app) },
                    onFocus = {
                        onUpdateHeroInfo(
                            HomeHeroInfo(
                                title = app.label,
                                subtitle = "アプリ",
                                description = app.packageName,
                                tag = "App"
                            )
                        )
                    },
                    modifier = Modifier
                        .focusRequester(specificRequester)
                        .then(
                            if (index == 0 && contentFirstItemRequester != null) Modifier.focusRequester(
                                contentFirstItemRequester
                            ) else Modifier
                        )
                        .focusProperties {
                            if (index == 0) left = leftEdgeFocusRequester
                            if (index == apps.size - 1) right = FocusRequester.Cancel
                        }
                        .onFocusChanged {
                            if (it.isFocused || it.hasFocus) {
                                homeViewModel.lastClickedSection = sectionId
                                homeViewModel.lastClickedItemId = app.stableId
                            }
                        }
                )
            }
        }
    }
}

@Composable
fun LauncherAppManagementDialog(
    app: LauncherApp,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onHide: () -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors
    val buttonShape = RoundedCornerShape(TvCardRadiusListRow)

    LaunchedEffect(app.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("LauncherAppManagement")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.overlay,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        },
        text = {
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onMoveLeft,
                    enabled = canMoveLeft,
                    modifier = Modifier.focusRequester(focusRequester),
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textPrimary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("左へ") }
                Button(
                    onClick = onMoveRight,
                    enabled = canMoveRight,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textPrimary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("右へ") }
                Button(
                    onClick = onHide,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.danger.copy(alpha = 0.22f),
                        contentColor = colors.danger,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.danger), shape = buttonShape)
                    )
                ) { Text("非表示") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onShowAll,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textSecondary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("すべて表示") }
                Button(
                    onClick = onDismiss,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textSecondary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("閉じる") }
            }
        }
    )
}
