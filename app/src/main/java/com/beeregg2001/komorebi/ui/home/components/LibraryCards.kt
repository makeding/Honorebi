@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.components.recordedThumbnailCacheKey
import com.beeregg2001.komorebi.ui.components.recordedThumbnailModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidthCard
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import com.beeregg2001.komorebi.ui.video.components.SeriesThumbnailCollage
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import java.time.OffsetDateTime

private val VideoCardWidth = 236.dp
private val VideoCardThumbHeight = 132.dp
private val VideoCardMetaHeight = 66.dp
private val VideoCardTotalHeight = VideoCardThumbHeight + VideoCardMetaHeight
private val CornerBadgeBg = Color(0xC7000000)

internal fun formatDurationBadge(seconds: Double): String {
    val totalSec = seconds.toLong()
    if (totalSec <= 0L) return ""
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

@Composable
private fun CornerDurationBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .background(CornerBadgeBg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.tnum.copy(
                lineHeight = 13.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            color = Color.White,
            maxLines = 1,
        )
    }
}

@Composable
private fun CardBottomProgressBar(
    progress: Float,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(accentColor),
        )
    }
}

@Composable
private fun BelowCardMetadata(
    title: String,
    subtitle: String,
    isFocused: Boolean,
    logoUrl: String = "",
    shouldCropLogo: Boolean = false,
    allowNetworkImages: Boolean = true,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(VideoCardMetaHeight)
            .padding(top = 6.dp, start = 2.dp, end = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(lineHeight = 19.sp),
            fontWeight = FontWeight.Bold,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (isFocused) colors.textPrimary else colors.textSecondary,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                if (logoUrl.isNotBlank()) {
                    val logoRequest = remember(logoUrl, allowNetworkImages) {
                        ImageRequest.Builder(context)
                            .data(logoUrl)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
                            .build()
                    }
                    AsyncImage(
                        model = logoRequest,
                        contentDescription = null,
                        contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                        modifier = Modifier
                            .width(24.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.tnum.copy(
                        lineHeight = 14.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                    color = if (isFocused) colors.textSecondary else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoRecentRecordCard(
    program: RecordedProgram,
    history: KonomiHistoryProgram?,
    konomiIp: String,
    konomiPort: String,
    backendType: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentlyRecording: Boolean = false,
    focusRequester: FocusRequester,
    timeFormat: String,
    allowNetworkImages: Boolean = true,
    getLogoUrl: suspend (String) -> String = { "" },
    shouldCropLogo: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.GRID)
    var isFocused by remember { mutableStateOf(false) }
    val channelId = program.channel?.id.orEmpty()
    val logoUrl = if (channelId.isNotBlank()) {
        com.beeregg2001.komorebi.ui.components.rememberChannelLogoUrl(channelId, getLogoUrl)
    } else ""

    val fallbackUrl = program.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType,
        konomiIp,
        konomiPort,
        program.id.toString()
    )
    val primaryUrl = program.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(program.id, primaryUrl) { mutableStateOf(primaryUrl) }

    val duration = if (program.duration > 0) program.duration else program.recordedVideo.duration
    val progress = if (history != null && duration > 0 && history.playback_position > 5.0) {
        (history.playback_position / duration).toFloat().coerceIn(0f, 1f)
    } else null
    val durationBadge = remember(duration) { formatDurationBadge(duration) }

    val startFormat = remember(program.startTime, timeFormat) {
        try {
            ProgramTimeFormatter.formatDateTime(
                OffsetDateTime.parse(program.startTime).toDeviceTime(),
                timeFormat,
                "M/d(E)",
            )
        } catch (e: Exception) {
            program.startTime
        }
    }
    val metaLine = remember(program.channel?.name, startFormat) {
        listOfNotNull(
            program.channel?.name?.takeIf { it.isNotBlank() },
            startFormat.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    val context = LocalContext.current
    val imageRequest = remember(program.recordedThumbnailCacheKey(currentThumbnailUrl), currentThumbnailUrl, allowNetworkImages) {
        val thumbnailCacheKey = program.recordedThumbnailCacheKey(currentThumbnailUrl)
        ImageRequest.Builder(context)
            .data(program.recordedThumbnailModel(currentThumbnailUrl))
            .placeholder(com.beeregg2001.komorebi.R.drawable.dark_image)
            .error(com.beeregg2001.komorebi.R.drawable.dark_image)
            .fallback(com.beeregg2001.komorebi.R.drawable.dark_image)
            .crossfade(true)
            .memoryCacheKey(thumbnailCacheKey)
            .diskCacheKey(thumbnailCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }

    val thumbShape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        enabled = true,
        modifier = modifier
            .width(VideoCardWidth)
            .height(VideoCardTotalHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VideoCardThumbHeight)
                    .clip(thumbShape)
                    .background(colors.surface1)
                    .border(
                        width = if (isFocused) TvCardFocusBorderWidthCard else 1.dp,
                        color = if (isFocused) colors.accent else colors.borderSubtle,
                        shape = thumbShape,
                    )
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                            currentThumbnailUrl = fallbackUrl
                        }
                    }
                )
                if (isCurrentlyRecording) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(colors.recording, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "REC",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                lineHeight = 13.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                            ),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
                CornerDurationBadge(
                    text = durationBadge,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 7.dp),
                )
                if (progress != null && !isCurrentlyRecording) {
                    CardBottomProgressBar(
                        progress = progress,
                        accentColor = colors.accent,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
            BelowCardMetadata(
                title = program.title,
                subtitle = metaLine,
                isFocused = isFocused,
                logoUrl = logoUrl,
                shouldCropLogo = shouldCropLogo,
                allowNetworkImages = allowNetworkImages,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoWatchHistoryCard(
    historyItem: KonomiHistoryProgram,
    matchedProgram: RecordedProgram?,
    konomiIp: String,
    konomiPort: String,
    backendType: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    timeFormat: String,
    allowNetworkImages: Boolean = true,
    getLogoUrl: suspend (String) -> String = { "" },
    shouldCropLogo: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.GRID)
    var isFocused by remember { mutableStateOf(false) }
    val channelId = matchedProgram?.channel?.id.orEmpty()
    val logoUrl = if (channelId.isNotBlank()) {
        com.beeregg2001.komorebi.ui.components.rememberChannelLogoUrl(channelId, getLogoUrl)
    } else ""
    val videoId = matchedProgram?.id ?: try {
        historyItem.program.id.toString().toInt()
    } catch (e: Exception) {
        0
    }

    val fallbackUrl = matchedProgram?.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType,
        konomiIp,
        konomiPort,
        videoId.toString()
    )
    val primaryUrl = matchedProgram?.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(videoId, primaryUrl) { mutableStateOf(primaryUrl) }

    val duration = matchedProgram?.duration ?: 0.0
    val progress = if (duration > 0) (historyItem.playback_position / duration).toFloat()
        .coerceIn(0f, 1f) else null
    val broadcastStart = matchedProgram?.startTime ?: historyItem.program.start_time
    val broadcastDate = remember(broadcastStart, timeFormat) {
        runCatching {
            ProgramTimeFormatter.formatDateTime(
                OffsetDateTime.parse(broadcastStart).toDeviceTime(),
                timeFormat,
                "M/d(E)",
            )
        }.getOrDefault("")
    }
    val watchedTime = remember(historyItem.playback_position) {
        val pos = historyItem.playback_position.coerceAtLeast(0.0)
        formatDurationBadge(pos).ifBlank { "00:00" }
    }
    val metaLine = remember(matchedProgram?.channel?.name, broadcastDate) {
        listOfNotNull(
            matchedProgram?.channel?.name?.takeIf { it.isNotBlank() },
            broadcastDate.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    val context = LocalContext.current
    val imageRequest = remember(matchedProgram?.recordedThumbnailCacheKey(currentThumbnailUrl), currentThumbnailUrl, allowNetworkImages) {
        val thumbnailCacheKey = matchedProgram?.recordedThumbnailCacheKey(currentThumbnailUrl)
            ?: currentThumbnailUrl
        ImageRequest.Builder(context)
            .data(matchedProgram?.recordedThumbnailModel(currentThumbnailUrl) ?: currentThumbnailUrl)
            .placeholder(com.beeregg2001.komorebi.R.drawable.dark_image)
            .error(com.beeregg2001.komorebi.R.drawable.dark_image)
            .fallback(com.beeregg2001.komorebi.R.drawable.dark_image)
            .crossfade(true)
            .memoryCacheKey(thumbnailCacheKey)
            .diskCacheKey(thumbnailCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }

    val thumbShape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(VideoCardWidth)
            .height(VideoCardTotalHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VideoCardThumbHeight)
                    .clip(thumbShape)
                    .background(colors.surface1)
                    .border(
                        width = if (isFocused) TvCardFocusBorderWidthCard else 1.dp,
                        color = if (isFocused) colors.accent else colors.borderSubtle,
                        shape = thumbShape,
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
                CornerDurationBadge(
                    text = watchedTime,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 7.dp),
                )
                if (progress != null) {
                    CardBottomProgressBar(
                        progress = progress,
                        accentColor = colors.accent,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
            BelowCardMetadata(
                title = historyItem.program.title,
                subtitle = metaLine,
                isFocused = isFocused,
                logoUrl = logoUrl,
                shouldCropLogo = shouldCropLogo,
                allowNetworkImages = allowNetworkImages,
            )
        }
    }
}

@Composable
fun VideoSeriesCard(
    series: SeriesInfo,
    konomiIp: String,
    konomiPort: String,
    backendType: String,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    allowNetworkImages: Boolean = true
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.GRID)
    var isFocused by remember { mutableStateOf(false) }
    val thumbShape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(VideoCardWidth)
            .height(VideoCardTotalHeight)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RectangleShape),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VideoCardThumbHeight)
                    .clip(thumbShape)
                    .background(colors.surface1)
                    .border(
                        width = if (isFocused) TvCardFocusBorderWidthCard else 1.dp,
                        color = if (isFocused) colors.accent else colors.borderSubtle,
                        shape = thumbShape,
                    )
            ) {
                SeriesThumbnailCollage(
                    series = series,
                    backendType = backendType,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    modifier = Modifier.fillMaxSize(),
                    allowNetworkImages = allowNetworkImages,
                )
            }
            BelowCardMetadata(
                title = series.displayTitle,
                subtitle = "${series.programCount} エピソード",
                isFocused = isFocused,
            )
        }
    }
}

@Composable
private fun VideoQuickEntryPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.LIST_ROW)
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvCardRadiusListRow)

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(40.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        shape = ClickableSurfaceDefaults.shape(shape),
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
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 16.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RecordListBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    VideoQuickEntryPill(
        label = "録画リスト",
        icon = Icons.Default.List,
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier,
    )
}

@Composable
fun OnAirBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    VideoQuickEntryPill(
        label = "放送中",
        icon = Icons.Default.LiveTv,
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier,
    )
}

@Composable
fun SmbLibraryBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    VideoQuickEntryPill(
        label = "ファイル",
        icon = Icons.Default.FolderShared,
        onClick = onClick,
        onFocus = onFocus,
        modifier = modifier,
    )
}
