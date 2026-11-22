@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoUrl
import com.beeregg2001.komorebi.ui.components.recordedThumbnailCacheKey
import com.beeregg2001.komorebi.ui.components.recordedThumbnailModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.StatusRecordingColor
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidthCard
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleGrid
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleWideCard
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.tvCardMarquee
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime

@Composable
fun LastWatchedChannelCard(
    channel: Channel,
    liveChannel: Channel?,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val channelLogoImageLoader = rememberChannelLogoImageLoader()
    val colors = KomorebiTheme.colors
    val typeLabels =
        mapOf("GR" to "地デジ", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "スカパー", "IPTV" to "ネットテレビ")

    val logoUrl = rememberChannelLogoUrl(channel.id, getLogoUrl)

    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(220.dp)
            .height(96.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleWideCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidthCard, colors.accent), shape = shape)
        )
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    imageLoader = channelLogoImageLoader,
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                val programTitle = liveChannel?.programPresent?.title
                if (!programTitle.isNullOrEmpty()) {
                    Text(
                        text = programTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                    )
                    if (com.beeregg2001.komorebi.ui.player.liveChannelNumberLabel(channel) != null) {
                        Text(
                            text = "${typeLabels[channel.type] ?: channel.type} ${channel.channelNumber}",
                            style = MaterialTheme.typography.labelSmall.tnum,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HotChannelCard(
    uiState: UiChannelState,
    getLogoUrl: suspend (String) -> String,
    shouldCropLogo: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val channelLogoImageLoader = rememberChannelLogoImageLoader()
    val colors = KomorebiTheme.colors

    val logoUrl = rememberChannelLogoUrl(uiState.channel.id, getLogoUrl)
    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .height(106.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus()
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleWideCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidthCard, colors.accent), shape = shape)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.textPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    imageLoader = channelLogoImageLoader,
                    model = logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(StatusRecordingColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${uiState.jikkyoForce ?: 0} コメ/分",
                        color = if (isFocused) StatusRecordingColor else StatusRecordingColor.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall.tnum,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = uiState.programTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun WatchHistoryCard(
    history: KonomiHistoryProgram,
    matchedProgram: RecordedProgram?,
    konomiIp: String,
    konomiPort: String,
    getLogoUrl: suspend (String) -> String = { "" },
    shouldCropLogo: Boolean = false,
    settingViewModel: SettingsViewModel = hiltViewModel(),
    onClick: () -> Unit,
    onFocus: (progress: Float, thumbnailUrl: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val program = history.program
    val backendType by settingViewModel.backendType.collectAsState()
    val channelId = matchedProgram?.channel?.id.orEmpty()
    val logoUrl = if (channelId.isNotBlank()) rememberChannelLogoUrl(channelId, getLogoUrl) else ""

    val fallbackUrl = matchedProgram?.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType,
        konomiIp,
        konomiPort,
        program.id.toString()
    )
    val primaryUrl = matchedProgram?.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(program.id, primaryUrl) { mutableStateOf(primaryUrl) }

    val (progress, remainingMinutes) = remember(history, matchedProgram) {
        val total = matchedProgram?.duration?.takeIf { it > 0.0 }
            ?: matchedProgram?.recordedVideo?.duration?.takeIf { it > 0.0 }
            ?: runCatching {
                val start = OffsetDateTime.parse(program.start_time).toEpochSecond()
                val end = OffsetDateTime.parse(program.end_time).toEpochSecond()
                (end - start).toDouble()
            }.getOrDefault(0.0)
        if (total > 0.0) {
            val prog = (history.playback_position / total).toFloat().coerceIn(0f, 1f)
            val rem = ((total - history.playback_position).coerceAtLeast(0.0) / 60.0).toInt()
            prog to rem
        } else {
            0f to 0
        }
    }

    LaunchedEffect(currentThumbnailUrl, isFocused) {
        if (isFocused) onFocus(progress, currentThumbnailUrl)
    }

    val context = LocalContext.current
    val imageRequest = remember(matchedProgram?.recordedThumbnailCacheKey(currentThumbnailUrl), currentThumbnailUrl) {
        val thumbnailCacheKey = matchedProgram?.recordedThumbnailCacheKey(currentThumbnailUrl)
            ?: currentThumbnailUrl
        ImageRequest.Builder(context)
            .data(matchedProgram?.recordedThumbnailModel(currentThumbnailUrl) ?: currentThumbnailUrl)
            .crossfade(false)
            .memoryCacheKey(thumbnailCacheKey)
            .diskCacheKey(thumbnailCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val thumbShape = RoundedCornerShape(TvCardRadiusGrid)

    // Below-the-Card 16:9: 216dp wide, 122dp pure 16:9 thumb + 3dp progress bar + 44dp title/meta below
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(216.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(progress, currentThumbnailUrl)
            },
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleGrid),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(122.dp)
                    .clip(thumbShape)
                    .background(colors.surface1)
                    .border(
                        width = if (isFocused) TvCardFocusBorderWidthCard else 1.dp,
                        color = if (isFocused) colors.accent else colors.borderSubtle,
                        shape = thumbShape
                    )
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                            currentThumbnailUrl = fallbackUrl
                        }
                    }
                )

                if (remainingMinutes > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.78f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "残り ${remainingMinutes}分",
                            style = MaterialTheme.typography.labelSmall.tnum,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(colors.accent)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(top = 6.dp, start = 2.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.tvCardMarquee(isFocused)
                )
                val metaText = matchedProgram?.channel?.name.orEmpty()
                if (metaText.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp),
                    ) {
                        if (logoUrl.isNotBlank()) {
                            AsyncImage(
                                model = logoUrl,
                                contentDescription = null,
                                contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            )
                        }
                        Text(
                            text = metaText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                lineHeight = 14.sp,
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                ),
                            ),
                            color = if (isFocused) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun UpcomingReserveCard(
    reserve: ReserveItem,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(reserve.program.startTime).toDeviceTime()

    val startFormat = remember(start, timeFormat) {
        ProgramTimeFormatter.formatDateTime(start, timeFormat, "MM/dd")
    }

    val displayTime = remember(start, timeFormat) {
        ProgramTimeFormatter.formatTime(start.toLocalTime(), timeFormat)
    }

    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(240.dp)
            .height(112.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleWideCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidthCard, colors.accent), shape = shape)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.labelSmall.tnum,
                    color = colors.textSecondary
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isFocused) colors.accentSoft else colors.textPrimary.copy(alpha = 0.06f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "P${reserve.recordSettings.priority}",
                        style = MaterialTheme.typography.labelSmall.tnum,
                        color = if (isFocused) colors.accent else colors.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                reserve.program.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f),
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                reserve.channel.name,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GenrePickupCard(
    program: EpgProgram,
    channelName: String,
    timeSlot: String,
    onClick: () -> Unit,
    onFocus: (startFormat: String) -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val start = OffsetDateTime.parse(program.start_time).toDeviceTime()

    val startFormat = remember(start, timeFormat) {
        ProgramTimeFormatter.formatDateTime(start, timeFormat, "MM/dd")
    }

    val timeColor = when (timeSlot) {
        "朝" -> if (colors.isDark) Color(0xFFFFCC80) else Color(0xFFE65100)
        "昼" -> if (colors.isDark) Color(0xFF81D4FA) else Color(0xFF0277BD)
        else -> if (colors.isDark) Color(0xFFB39DDB) else Color(0xFF311B92)
    }

    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(260.dp)
            .height(112.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocus(startFormat)
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleWideCard),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidthCard, colors.accent), shape = shape)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$startFormat - $channelName",
                style = MaterialTheme.typography.labelSmall.tnum,
                color = if (isFocused) colors.accent else timeColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.9f),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LauncherAppCard(
    app: LauncherApp,
    onClick: () -> Unit,
    onManage: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 220.dp,
    cardHeight: Dp = 124.dp,
    bannerWidth: Dp = 168.dp,
    bannerHeight: Dp = 64.dp,
    iconSize: Dp = 56.dp,
    showBorder: Boolean = true,
    fullBleedBanner: Boolean = false,
    showLabel: Boolean = true,
    manualConfirmHandling: Boolean = false,
    isEditing: Boolean = false,
    onMoveLeft: () -> Unit = {},
    onMoveRight: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    onHide: () -> Unit = {},
    onOpenActions: () -> Unit = {},
    onDoneEditing: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    var consumedLongPress by remember(app.stableId) { mutableStateOf(false) }
    var confirmPressed by remember(app.stableId) { mutableStateOf(false) }
    var longPressJob by remember(app.stableId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val iconModel = remember(app.icon, app.artworkCacheVersion) {
        app.icon?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCacheKey("launcher-icon:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .diskCacheKey("launcher-icon:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    val bannerModel = remember(app.banner, app.artworkCacheVersion) {
        app.banner?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCacheKey("launcher-banner:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .diskCacheKey("launcher-banner:${app.stableId}:${app.artworkCacheVersion}:$uri")
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    val shape = RoundedCornerShape(TvCardRadiusGrid)
    val containerColor = when {
        isEditing -> colors.accentSoft
        isFocused -> colors.surface2
        else -> colors.surface1
    }

    DisposableEffect(app.stableId) {
        onDispose { longPressJob?.cancel() }
    }

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .clip(shape)
            .background(containerColor, shape)
            .drawWithContent {
                drawContent()
                val ringColor = when {
                    isEditing -> colors.accent
                    isFocused -> colors.accent
                    showBorder -> colors.borderSubtle
                    else -> Color.Transparent
                }
                if (ringColor != Color.Transparent) {
                    val stroke = if (isFocused || isEditing) TvCardFocusBorderWidthCard.toPx() else 1.dp.toPx()
                    drawRoundRect(
                        color = ringColor,
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke,
                            size.height - stroke
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(TvCardRadiusGrid.toPx()),
                        style = Stroke(width = stroke)
                    )
                }
            }
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isConfirmKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER

                when {
                    isEditing &&
                        event.type == KeyEventType.KeyDown &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onMoveLeft()
                        true
                    }

                    isEditing &&
                        event.type == KeyEventType.KeyDown &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onMoveRight()
                        true
                    }

                    isEditing &&
                        event.type == KeyEventType.KeyDown &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        onMoveUp()
                        true
                    }

                    isEditing &&
                        event.type == KeyEventType.KeyDown &&
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        onMoveDown()
                        true
                    }

                    isEditing &&
                        event.type == KeyEventType.KeyUp &&
                        keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                        onOpenActions()
                        true
                    }

                    // 編集中の Back は、KeyUp でアクションメニューを開く一組の操作として
                    // カードが最後まで所有する。KeyDown を親へ漏らすと Root の BackHandler が
                    // 先にアプリタブへフォーカスを戻し、その後の KeyUp だけでメニューが開く。
                    isEditing &&
                        event.type == KeyEventType.KeyDown &&
                        keyCode == android.view.KeyEvent.KEYCODE_BACK -> true

                    isEditing &&
                        event.type == KeyEventType.KeyUp &&
                        keyCode == android.view.KeyEvent.KEYCODE_BACK -> {
                        longPressJob?.cancel()
                        confirmPressed = false
                        consumedLongPress = false
                        onOpenActions()
                        true
                    }

                    isConfirmKey &&
                        event.type == KeyEventType.KeyDown &&
                        event.nativeKeyEvent.repeatCount == 0 -> {
                        confirmPressed = true
                        consumedLongPress = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            kotlinx.coroutines.delay(350)
                            if (confirmPressed) {
                                consumedLongPress = true
                                onManage()
                            }
                        }
                        true
                    }

                    isConfirmKey &&
                        event.type == KeyEventType.KeyDown -> true

                    isConfirmKey &&
                        event.type == KeyEventType.KeyUp -> {
                        longPressJob?.cancel()
                        val wasLongPress = consumedLongPress
                        confirmPressed = false
                        consumedLongPress = false
                        if (!wasLongPress) {
                            if (isEditing) onDoneEditing() else onClick()
                        }
                        true
                    }

                    event.type == KeyEventType.KeyUp &&
                        keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                        onManage()
                        true
                    }

                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) {
                    longPressJob?.cancel()
                    confirmPressed = false
                    consumedLongPress = false
                }
                if (it.isFocused) onFocus()
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (fullBleedBanner && app.banner != null) Modifier
                    else Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (fullBleedBanner && app.banner != null) {
                AsyncImage(
                    model = bannerModel,
                    contentDescription = app.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (showLabel) Modifier.height(bannerHeight) else Modifier.fillMaxHeight())
                        .clip(RoundedCornerShape(TvCardRadiusGrid)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(if (app.banner != null) bannerWidth else iconSize)
                        .then(
                            if (!showLabel) Modifier.fillMaxHeight()
                            else Modifier.height(if (app.banner != null) bannerHeight else iconSize)
                        )
                        .clip(RoundedCornerShape(if (app.banner != null) 8.dp else 12.dp))
                        .background(colors.textPrimary.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = bannerModel ?: iconModel,
                        contentDescription = app.label,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (app.banner != null) 0.dp else 4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            if (showLabel) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = app.label,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFocused) colors.textPrimary else colors.textPrimary.copy(alpha = 0.82f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
