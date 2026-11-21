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

@Composable
fun CompactHomeHeroInfo(
    state: HomeHeroInfo,
    allowNetworkImages: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            if (state.tag.isNotBlank()) {
                Text(
                    text = state.tag,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.subtitle.isNotBlank()) {
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.description.isNotBlank()) {
                Text(
                    text = state.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        state.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            val imageRequest = remember(imageUrl, allowNetworkImages) {
                ImageRequest.Builder(context)
                    .data(imageUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(if (allowNetworkImages) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .build()
            }
            Spacer(Modifier.width(16.dp))
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .alpha(0.72f)
            )
        }
    }
}

@Composable
fun HomeHeroDashboard(
    state: HomeHeroInfo, // ★ 修正: 呼び出し元の引数名に合わせて info -> state に変更
    getLogoUrl: suspend (String) -> String, // ★ 追加: コールバックを受け取る
    shouldCropLogo: Boolean,                // ★ 追加: クロップフラグを受け取る
    modifier: Modifier = Modifier           // ★ 追加: 呼び出し元からの modifier を受け取る
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current

    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label = "HomeHeroTransition",
        modifier = modifier.fillMaxSize() // ★ 修正: modifier を適用
    ) { targetState -> // ★ アニメーション中の状態 (targetState) に対して処理を行う

        // ★ 追加: アニメーション状態の targetState に対して非同期でURLを解決する
        var resolvedImageUrl by remember(targetState.imageUrl, targetState.channelId, targetState.isThumbnail) {
            mutableStateOf(targetState.imageUrl)
        }

        LaunchedEffect(targetState.channelId, targetState.imageUrl, targetState.isThumbnail) {
            if (!targetState.isThumbnail && targetState.channelId != null) {
                resolvedImageUrl = getLogoUrl(targetState.channelId)
            } else {
                resolvedImageUrl = targetState.imageUrl
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {

            if (targetState.tag == "Welcome") {
                val welcomeImageRes =
                    if (colors.isDark) R.drawable.dark_image else R.drawable.light_image
                val welcomeImageRequest = remember(welcomeImageRes) {
                    ImageRequest.Builder(context)
                        .data(welcomeImageRes)
                        // The source art is larger than a 1080p TV frame.  Decode only the
                        // displayed size so entering Home does not allocate its full bitmap.
                        .size(1920, 1080)
                        .scale(Scale.FILL)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                }

                AsyncImage(
                    model = welcomeImageRequest,
                    contentDescription = "Welcome Background",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.8f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (targetState.isThumbnail && resolvedImageUrl != null) {
                AsyncImage(
                    model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.4f)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    colors.background,
                                    colors.background.copy(alpha = 0.6f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            } else if (!targetState.isThumbnail && resolvedImageUrl != null) {
                AsyncImage(
                    model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                    contentDescription = null,
                    // ★ 修正: 背景の透かしロゴもフラグに従ってスケールを調整
                    contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.35f)
                        .aspectRatio(16f / 9f)
                        .alpha(0.12f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.7f),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (targetState.tag != "Welcome" && !targetState.isThumbnail && resolvedImageUrl != null) {
                        Box(
                            modifier = Modifier
                                .size(72.dp, 40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = resolvedImageUrl, // ★ 修正: 解決済みのURLを使用
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize(),
                                // ★ 修正: 前景のロゴをフラグに従ってスケール調整
                                contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = targetState.tag,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = targetState.title,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1500,
                        spacing = MarqueeSpacing(48.dp)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = targetState.subtitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.heightIn(min = 72.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (targetState.description.isNotEmpty()) {
                        Text(
                            text = targetState.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                            maxLines = if (targetState.progress != null) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 24.sp
                        )
                        if (targetState.progress != null) Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (targetState.progress != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(280.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(colors.textSecondary.copy(0.2f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(targetState.progress)
                                        .fillMaxHeight()
                                        .background(colors.accent)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "続きから再生",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

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
