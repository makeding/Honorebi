@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.components.recordedThumbnailCacheKey
import com.beeregg2001.komorebi.ui.components.recordedThumbnailModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.viewmodel.SeriesInfo
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit,
    timeFormat: String,
    allowNetworkImages: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

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

    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && program.id == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_VideoTab")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed()
        }
    }

    val context = LocalContext.current
    val imageRequest = remember(currentThumbnailUrl, allowNetworkImages) {
        val thumbnailCacheKey = program.recordedThumbnailCacheKey(currentThumbnailUrl)
        ImageRequest.Builder(context)
            .data(program.recordedThumbnailModel(currentThumbnailUrl))
            .crossfade(true)
            .memoryCacheKey(thumbnailCacheKey)
            .diskCacheKey(thumbnailCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }

    Surface(
        onClick = onClick,
        enabled = true,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.5f),
                onError = {
                    if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                        currentThumbnailUrl = fallbackUrl
                    }
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                val startFormat = try {
                    val pattern = if (timeFormat == "12H") "M/d(E) a h:mm" else "M/d(E) HH:mm"
                    OffsetDateTime.parse(program.startTime)
                        .format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
                } catch (e: Exception) {
                    program.startTime
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrentlyRecording) {
                        Box(
                            modifier = Modifier
                                .background(colors.accent, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "録画中",
                                color = if (colors.isDark) Color.Black else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = startFormat,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent.copy(alpha = if (isFocused) 1f else 0.8f),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress != null && !isCurrentlyRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(colors.accent)
                    )
                }
            }
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
    ticketManager: FocusTicketManager,
    onReturnFocusConsumed: () -> Unit,
    allowNetworkImages: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
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
    val broadcastEnd = matchedProgram?.endTime ?: historyItem.program.end_time
    val broadcastTime = runCatching {
        val formatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE)
        "${OffsetDateTime.parse(broadcastStart).format(formatter)} - ${OffsetDateTime.parse(broadcastEnd).format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }.getOrDefault("")
    val watchedPosition = historyItem.playback_position.coerceAtLeast(0.0).toLong()
    val watchedTime = "%02d:%02d".format(watchedPosition / 60, watchedPosition % 60)

    val specificRequester = remember { FocusRequester() }
    LaunchedEffect(ticketManager.currentTicket, ticketManager.issueTime) {
        if (ticketManager.currentTicket == FocusTicket.TARGET_ID && videoId == ticketManager.targetProgramId) {
            delay(100)
            specificRequester.safeRequestFocusWithRetry("Ticket_TARGET_ID_History")
            ticketManager.consume(FocusTicket.TARGET_ID)
            onReturnFocusConsumed()
        }
    }

    val context = LocalContext.current
    val imageRequest = remember(currentThumbnailUrl, allowNetworkImages) {
        val thumbnailCacheKey = matchedProgram?.recordedThumbnailCacheKey(currentThumbnailUrl)
            ?: currentThumbnailUrl
        ImageRequest.Builder(context)
            .data(matchedProgram?.recordedThumbnailModel(currentThumbnailUrl) ?: currentThumbnailUrl)
            .crossfade(true)
            .memoryCacheKey(thumbnailCacheKey)
            .diskCacheKey(thumbnailCacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .focusRequester(specificRequester)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.5f),
                contentScale = ContentScale.Crop,
                onError = {
                    if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                        currentThumbnailUrl = fallbackUrl
                    }
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                if (broadcastTime.isNotBlank()) {
                    Text(
                        text = broadcastTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = historyItem.program.title.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "前回視聴: $watchedTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(colors.accent)
                    )
                }
            }
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
    var isFocused by remember { mutableStateOf(false) }

    val fallbackUrl = series.apiThumbnailUrl ?: UrlBuilder.getThumbnailUrl(
        backendType, konomiIp, konomiPort, series.representativeVideoId.toString()
    )
    val primaryUrl = series.directThumbnailUrl ?: fallbackUrl
    var currentThumbnailUrl by remember(series.representativeVideoId, primaryUrl) {
        mutableStateOf(
            primaryUrl
        )
    }

    val context = LocalContext.current
    val imageRequest = remember(currentThumbnailUrl, allowNetworkImages) {
        ImageRequest.Builder(context)
            .data(currentThumbnailUrl)
            .crossfade(true)
            .memoryCacheKey(currentThumbnailUrl)
            .diskCacheKey(currentThumbnailUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(280.dp)
            .height(160.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.5f),
            focusedContainerColor = colors.surface,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        border = ClickableSurfaceDefaults.border(
            Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (isFocused) 0.8f else 0.4f),
                onError = {
                    if (currentThumbnailUrl == primaryUrl && primaryUrl != fallbackUrl) {
                        currentThumbnailUrl = fallbackUrl
                    }
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = 100f
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Text(
                    text = "${series.programCount}エピソード",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = series.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = Color.White,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun RecordListBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val backgroundBrush = remember(colors) {
        Brush.horizontalGradient(
            colors = listOf(
                colors.surface,
                colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.1f)
            )
        )
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isFocused) SolidColor(Color.Transparent) else backgroundBrush)
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = null,
                tint = (if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent).copy(
                    alpha = 0.1f
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 24.dp, y = 16.dp)
                    .size(100.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (isFocused) Color.Transparent else colors.accent.copy(alpha = 0.2f),
                            shape = CircleShape
                        ), contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = "録画リスト",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "すべての番組・シリーズから探す",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFocused) (if (colors.isDark) Color.Black.copy(alpha = 0.8f) else Color.White.copy(
                            alpha = 0.8f
                        )) else colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ★ 新規追加: SMBライブラリ画面へ遷移するためのボタン
@Composable
fun SmbLibraryBannerButton(
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val backgroundBrush = remember(colors) {
        Brush.horizontalGradient(
            colors = listOf(
                colors.surface,
                colors.accent.copy(alpha = if (colors.isDark) 0.2f else 0.1f)
            )
        )
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus; if (isFocused) onFocus() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.1f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent))
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isFocused) SolidColor(Color.Transparent) else backgroundBrush)
        ) {
            Icon(
                imageVector = Icons.Default.FolderShared,
                contentDescription = null,
                tint = (if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent).copy(
                    alpha = 0.1f
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 24.dp, y = 16.dp)
                    .size(100.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (isFocused) Color.Transparent else colors.accent.copy(alpha = 0.2f),
                            shape = CircleShape
                        ), contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderShared,
                        contentDescription = null,
                        tint = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        text = "ファイルライブラリ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "ネットワーク上の動画を再生",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFocused) (if (colors.isDark) Color.Black.copy(alpha = 0.8f) else Color.White.copy(
                            alpha = 0.8f
                        )) else colors.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
