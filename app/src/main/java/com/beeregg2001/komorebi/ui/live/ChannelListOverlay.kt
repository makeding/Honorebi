@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.RecordedCard
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@Composable
fun ChannelListOverlay(
    groupedChannels: Map<String, List<Channel>>,
    recentChannels: List<Channel>,
    recentRecordings: List<RecordedProgram>,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    currentChannelId: String,
    onChannelSelect: (Channel) -> Unit,
    onRecordingSelect: (RecordedProgram) -> Unit,
    logoUrls: Map<String, String>,
    shouldCropLogo: Boolean,
    focusRequester: FocusRequester
) {
    val colors = KomorebiTheme.colors
    val channelTypeOrder = listOf("GR", "BS", "CS", "BS4K", "SKY")
    val channelSections = remember(groupedChannels) {
        channelTypeOrder.mapNotNull { type ->
            groupedChannels[type]?.takeIf { it.isNotEmpty() }?.let { type to it }
        }
    }
    val sectionKeys = remember(recentChannels, recentRecordings, channelSections) {
        buildList {
            if (recentChannels.isNotEmpty()) add("recent-channels")
            if (recentRecordings.isNotEmpty()) add("recent-recordings")
            addAll(channelSections.map { it.first })
        }
    }
    val firstSection = sectionKeys.firstOrNull()
    val secondSection = sectionKeys.getOrNull(1)
    val columnState = rememberLazyListState()
    val secondSectionFocusRequester = remember { FocusRequester() }
    var isTwoRowsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(firstSection, currentChannelId) {
        if (firstSection == null) return@LaunchedEffect
        isTwoRowsVisible = false
        columnState.scrollToItem(0)
        focusRequester.safeRequestFocusWithRetry(
            tag = "ChannelBrowserFocus",
            maxRetries = 8,
            delayMillis = 40
        )
    }

    LaunchedEffect(isTwoRowsVisible, secondSection) {
        if (isTwoRowsVisible && secondSection != null) {
            delay(60)
            secondSectionFocusRequester.safeRequestFocusWithRetry(
                tag = "ChannelBrowserSecondRowFocus",
                maxRetries = 5,
                delayMillis = 40
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        LazyColumn(
            state = columnState,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isTwoRowsVisible) 336.dp else 180.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.78f),
                            colors.background.copy(alpha = 0.96f),
                            colors.background
                        ),
                        startY = 0f,
                        endY = 700f
                    )
                )
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        !isTwoRowsVisible &&
                        secondSection != null
                    ) {
                        isTwoRowsVisible = true
                        true
                    } else {
                        false
                    }
                },
            contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (recentChannels.isNotEmpty()) {
                item(key = "recent-channels") {
                    ChannelSectionRow(
                        title = "最近見たチャンネル",
                        channels = recentChannels,
                        currentChannelId = currentChannelId,
                        logoUrls = logoUrls,
                        shouldCropLogo = shouldCropLogo,
                        onChannelSelect = onChannelSelect,
                        initialFocusRequester = when ("recent-channels") {
                            firstSection -> focusRequester
                            secondSection -> secondSectionFocusRequester
                            else -> null
                        }
                    )
                }
            }

            if (recentRecordings.isNotEmpty()) {
                item(key = "recent-recordings") {
                    RecordingSectionRow(
                        programs = recentRecordings,
                        backendType = backendType,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onRecordingSelect = onRecordingSelect,
                        initialFocusRequester = when ("recent-recordings") {
                            firstSection -> focusRequester
                            secondSection -> secondSectionFocusRequester
                            else -> null
                        }
                    )
                }
            }

            items(channelSections, key = { it.first }) { (type, channels) ->
                val label = when (type) {
                    "GR" -> "地デジ"
                    "SKY" -> "スカパー"
                    else -> type
                }
                ChannelSectionRow(
                    title = label,
                    channels = channels,
                    currentChannelId = currentChannelId,
                    logoUrls = logoUrls,
                    shouldCropLogo = shouldCropLogo,
                    onChannelSelect = onChannelSelect,
                    initialFocusRequester = when (type) {
                        firstSection -> focusRequester
                        secondSection -> secondSectionFocusRequester
                        else -> null
                    }
                )
            }
        }
    }
}

@Composable
private fun ChannelSectionRow(
    title: String,
    channels: List<Channel>,
    currentChannelId: String,
    logoUrls: Map<String, String>,
    shouldCropLogo: Boolean,
    onChannelSelect: (Channel) -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val listState = rememberLazyListState()
    val focusIndex = remember(channels, currentChannelId) {
        channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    }

    LaunchedEffect(focusIndex) {
        if (focusIndex > 0) listState.scrollToItem(focusIndex)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title)
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                ChannelCardItem(
                    channel = channel,
                    isSelected = channel.id == currentChannelId,
                    logoUrl = logoUrls.logoUrlFor(channel),
                    shouldCropLogo = shouldCropLogo,
                    onClick = { onChannelSelect(channel) },
                    modifier = Modifier.then(
                        if (initialFocusRequester != null && index == focusIndex) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun RecordingSectionRow(
    programs: List<RecordedProgram>,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    onRecordingSelect: (RecordedProgram) -> Unit,
    initialFocusRequester: FocusRequester?
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("最新の録画")
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(114.dp)
        ) {
            itemsIndexed(programs, key = { _, program -> program.id }) { index, program ->
                RecordedCard(
                    program = program,
                    backendType = backendType,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    onClick = { onRecordingSelect(program) },
                    modifier = Modifier.then(
                        if (initialFocusRequester != null && index == 0) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    ),
                    isScrolling = { listState.isScrollInProgress }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    val colors = KomorebiTheme.colors
    Text(
        text = title,
        modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary
    )
}

@Composable
fun ChannelCardItem(
    channel: Channel,
    isSelected: Boolean,
    logoUrl: String,
    shouldCropLogo: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val imageLoader = rememberChannelLogoImageLoader()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val imageRequest = remember(context, logoUrl) {
        ImageRequest.Builder(context)
            .data(logoUrl)
            .crossfade(false)
            .build()
    }

    val backgroundColor = if (isFocused) {
        colors.textPrimary
    } else if (isSelected) {
        colors.surface
    } else {
        colors.surface.copy(alpha = 0.6f)
    }
    val contentColor =
        if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary

    val borderWidth = if (isFocused) 3.dp else 0.dp
    val borderColor = if (isFocused) colors.accent else Color.Transparent

    Box(
        modifier = modifier
            .width(220.dp)
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp, 27.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isFocused) Color.LightGray else Color.White),
                colors = SurfaceDefaults.colors(containerColor = Color.Transparent)
            ) {
                AsyncImage(
                    imageLoader = imageLoader,
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = channel.programPresent?.title ?: "放送情報なし",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(8.dp)
                    .background(colors.accent, RoundedCornerShape(50))
            )
        }
    }
}

fun Map<String, String>.logoUrlFor(channel: Channel): String =
    this[channel.id].takeUnless { it.isNullOrBlank() }
        ?: this[channel.displayChannelId].orEmpty()
