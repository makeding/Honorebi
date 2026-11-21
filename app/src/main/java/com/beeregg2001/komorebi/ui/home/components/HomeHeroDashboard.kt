package com.beeregg2001.komorebi.ui.home.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.UiChannelState
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.StatusRecordingColor
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidthCard
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleListRow
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.tvCardMarquee
import java.time.Instant
import java.time.OffsetDateTime

data class HomeHeroInfo(
    val title: String,
    val subtitle: String,
    val description: String = "",
    val imageUrl: String? = null,
    val channelId: String? = null, // ★ 追加: チャンネルロゴを非同期で解決するためのID
    val isThumbnail: Boolean = false,
    val tag: String = "",
    val progress: Float? = null
)

private sealed interface QuickResumeTarget {
    data class Recorded(
        val history: KonomiHistoryProgram,
        val channelName: String?,
        val dateLabel: String,
        val remainingMinutes: Int,
        val resumePositionLabel: String,
        val progress: Float,
    ) : QuickResumeTarget

    data class Live(
        val channel: Channel,
        val programTitle: String,
        val subtitle: String,
        val hintLabel: String,
        val forceLabel: String? = null,
    ) : QuickResumeTarget

    data object Empty : QuickResumeTarget
}

private fun formatResumePosition(seconds: Double): String {
    val totalSec = seconds.toLong().coerceAtLeast(0L)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun QuickResumeHeroCard(
    watchHistory: List<KonomiHistoryProgram>,
    recentRecordings: List<RecordedProgram>,
    lastWatchedChannels: List<Channel>,
    hotChannels: List<UiChannelState>,
    groupedChannels: Map<String, List<Channel>>,
    timeFormat: String,
    onHistoryClick: (KonomiHistoryProgram) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onNavigateToTab: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val target = remember(
        watchHistory,
        recentRecordings,
        lastWatchedChannels,
        hotChannels,
        groupedChannels,
        timeFormat,
    ) {
        val allChannels = groupedChannels.values.flatten()
        val firstHistory = watchHistory.firstOrNull()
        val firstLastWatched = lastWatchedChannels.firstOrNull()
        val firstHot = hotChannels.firstOrNull()
        when {
            firstHistory != null -> {
                val program = firstHistory.program
                val matched = recentRecordings.find { it.id.toString() == program.id }
                val channelName = matched?.channel?.name
                    ?: allChannels.find { it.id == program.channel_id }?.name
                val (progress, remainingMin) = runCatching {
                    val start = Instant.parse(program.start_time).epochSecond
                    val end = Instant.parse(program.end_time).epochSecond
                    val total = (end - start).coerceAtLeast(1L).toDouble()
                    val prog = (firstHistory.playback_position / total).toFloat().coerceIn(0f, 1f)
                    val rem = ((total - firstHistory.playback_position).coerceAtLeast(0.0) / 60.0).toInt()
                    prog to rem
                }.getOrDefault(0f to 0)
                val dateLabel = runCatching {
                    val start = OffsetDateTime.parse(program.start_time).toDeviceTime()
                    ProgramTimeFormatter.formatDateTime(start, timeFormat, "MM/dd")
                }.getOrDefault("")
                QuickResumeTarget.Recorded(
                    history = firstHistory,
                    channelName = channelName,
                    dateLabel = dateLabel,
                    remainingMinutes = remainingMin,
                    resumePositionLabel = formatResumePosition(firstHistory.playback_position),
                    progress = progress,
                )
            }

            firstLastWatched != null -> {
                val liveChannel = allChannels.find { it.id == firstLastWatched.id }
                val programTitle = liveChannel?.programPresent?.title?.takeIf { it.isNotBlank() }
                    ?: firstLastWatched.name
                val timeRange = liveChannel?.programPresent?.let { prog ->
                    runCatching {
                        val s = OffsetDateTime.parse(prog.startTime).toDeviceTime()
                        val e = OffsetDateTime.parse(prog.endTime).toDeviceTime()
                        "${ProgramTimeFormatter.formatTime(s.toLocalTime(), timeFormat)}〜${ProgramTimeFormatter.formatTime(e.toLocalTime(), timeFormat)}"
                    }.getOrNull()
                }
                val subtitle = listOfNotNull(firstLastWatched.name, timeRange).joinToString(" · ")
                QuickResumeTarget.Live(
                    channel = firstLastWatched,
                    programTitle = programTitle,
                    subtitle = subtitle,
                    hintLabel = "前回のライブ: ${firstLastWatched.name}",
                )
            }

            firstHot != null -> {
                QuickResumeTarget.Live(
                    channel = firstHot.channel,
                    programTitle = firstHot.programTitle.ifBlank { firstHot.name },
                    subtitle = firstHot.name,
                    hintLabel = "注目のライブ: ${firstHot.name}",
                    forceLabel = "${firstHot.jikkyoForce ?: 0} コメ/分",
                )
            }

            else -> QuickResumeTarget.Empty
        }
    }

    val shape = RoundedCornerShape(14.dp)

    Surface(
        onClick = {
            when (target) {
                is QuickResumeTarget.Recorded -> onHistoryClick(target.history)
                is QuickResumeTarget.Live -> onChannelClick(target.channel)
                QuickResumeTarget.Empty -> onNavigateToTab("ライブ")
            }
        },
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { isFocused = it.isFocused },
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
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidthCard, colors.accent), shape = shape),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top row: accent pill + right status/meta
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.accentSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (target !is QuickResumeTarget.Empty) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = if (target is QuickResumeTarget.Empty) "ホーム" else "クイック再開",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        maxLines = 1,
                    )
                }

                when (target) {
                    is QuickResumeTarget.Recorded -> {
                        val rightMeta = buildString {
                            if (!target.channelName.isNullOrBlank()) {
                                append(target.channelName)
                                append(" · ")
                            }
                            append("残り ${target.remainingMinutes}分")
                        }
                        Text(
                            text = rightMeta,
                            style = MaterialTheme.typography.labelMedium.tnum,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    is QuickResumeTarget.Live -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (target.forceLabel != null) {
                                Text(
                                    text = target.forceLabel,
                                    style = MaterialTheme.typography.labelMedium.tnum,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StatusRecordingColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(StatusRecordingColor, CircleShape)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "LIVE",
                                    style = MaterialTheme.typography.labelSmall.tnum,
                                    color = StatusRecordingColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    QuickResumeTarget.Empty -> Unit
                }
            }

            // Middle: 1-line titleLarge + 1-line bodyMedium
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val mainTitle = when (target) {
                    is QuickResumeTarget.Recorded -> target.history.program.title
                    is QuickResumeTarget.Live -> target.programTitle
                    QuickResumeTarget.Empty -> "視聴履歴がありません"
                }
                val subLine = when (target) {
                    is QuickResumeTarget.Recorded -> listOfNotNull(
                        target.dateLabel.takeIf { it.isNotBlank() },
                        "${target.resumePositionLabel} から再開",
                    ).joinToString(" · ")
                    is QuickResumeTarget.Live -> target.subtitle
                    QuickResumeTarget.Empty -> "ライブまたは録画から再生を開始"
                }
                Text(
                    text = mainTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.tvCardMarquee(isFocused),
                )
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.bodyMedium.tnum,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Bottom: progress bar (if recorded) + subtle hint row
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (target is QuickResumeTarget.Recorded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.textPrimary.copy(alpha = 0.14f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(target.progress)
                                .fillMaxHeight()
                                .background(colors.accent)
                        )
                    }
                }
                val hintText = when (target) {
                    is QuickResumeTarget.Recorded -> "OK 続きから再生"
                    is QuickResumeTarget.Live -> target.hintLabel
                    QuickResumeTarget.Empty -> "OK ライブへ移動"
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFocused) colors.accent else colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun QuickShortcutsGrid(
    lastWatchedChannelName: String?,
    recentRecordingsCount: Int,
    upcomingReserves: List<ReserveItem>,
    timeFormat: String,
    heroRequester: FocusRequester,
    topBarRequester: FocusRequester,
    tile0Requester: FocusRequester,
    tile1Requester: FocusRequester,
    tile2Requester: FocusRequester,
    tile3Requester: FocusRequester,
    onNavigateToTab: (String) -> Unit,
    onShowOnAir: () -> Unit,
    onShowAllRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nextReserveBadge = remember(upcomingReserves, timeFormat) {
        upcomingReserves.firstOrNull()?.let { reserve ->
            runCatching {
                val start = OffsetDateTime.parse(reserve.program.startTime).toDeviceTime()
                "${ProgramTimeFormatter.formatTime(start.toLocalTime(), timeFormat)}〜"
            }.getOrNull()
        }
    }

    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickShortcutTile(
                title = "ライブ視聴",
                subtitle = lastWatchedChannelName ?: "放送中のチャンネル",
                icon = Icons.Default.LiveTv,
                badgeText = "LIVE",
                badgeIsRecording = true,
                onClick = { onNavigateToTab("ライブ") },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(tile0Requester)
                    .focusProperties {
                        up = topBarRequester
                        left = heroRequester
                        right = tile1Requester
                        down = tile2Requester
                    },
            )
            QuickShortcutTile(
                title = "放送中",
                subtitle = "今期アニメ・番組",
                icon = Icons.Default.Schedule,
                onClick = onShowOnAir,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(tile1Requester)
                    .focusProperties {
                        up = topBarRequester
                        left = tile0Requester
                        right = FocusRequester.Cancel
                        down = tile3Requester
                    },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickShortcutTile(
                title = "録画リスト",
                subtitle = if (recentRecordingsCount > 0) "全 ${recentRecordingsCount} 番組" else "すべての録画",
                icon = Icons.Default.VideoLibrary,
                onClick = onShowAllRecordings,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(tile2Requester)
                    .focusProperties {
                        up = tile0Requester
                        left = heroRequester
                        right = tile3Requester
                    },
            )
            QuickShortcutTile(
                title = "番組表・予約",
                subtitle = "予約 ${upcomingReserves.size}件",
                icon = Icons.Default.DateRange,
                badgeText = nextReserveBadge,
                badgeIsRecording = false,
                onClick = {
                    onNavigateToTab(if (upcomingReserves.isNotEmpty()) "録画予約" else "番組表")
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(tile3Requester)
                    .focusProperties {
                        up = tile1Requester
                        left = tile2Requester
                        right = FocusRequester.Cancel
                    },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickShortcutTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    badgeIsRecording: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvCardRadiusGrid)

    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isFocused) colors.accent else colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!badgeText.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    val badgeBg = if (badgeIsRecording) {
                        StatusRecordingColor.copy(alpha = 0.18f)
                    } else {
                        colors.accentSoft
                    }
                    val badgeFg = if (badgeIsRecording) StatusRecordingColor else colors.accent
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.tnum,
                            color = badgeFg,
                            maxLines = 1,
                        )
                    }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium.tnum,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
