package com.beeregg2001.komorebi.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.StatusRecordingColor
import com.beeregg2001.komorebi.ui.theme.StatusWarningColor
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import com.beeregg2001.komorebi.ui.theme.tvCardMarquee
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveCard(
    item: ReserveItem,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String = "24H",
    getLogoUrl: suspend (String) -> String = { "" } // ★追加: ViewModel等の非同期取得用
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    // フォーカス演出 (スケール / 枠) は共通トークンに一元化
    val focusSpec = tvCardFocus(TvCardFamily.LIST_ROW)

    val program = item.program
    val settings = item.recordSettings

    // --- 配色定義 (テーマベース) ---
    val contentColor =
        if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
    val subTextColor =
        if (isFocused) (if (colors.isDark) Color.DarkGray else Color.LightGray) else colors.textSecondary

    // バッジ類
    val badgeBgColor =
        if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
    val badgeTextColor =
        if (isFocused) (if (colors.isDark) Color.White else Color.Black) else colors.background

    // ステータスカラー定義
    val recordingRed = StatusRecordingColor
    val errorRed = Color(0xFFC62828)
    val warningYellow = StatusWarningColor
    val normalGreen = contentColor

    // --- ステータス表示ロジック ---
    val isRecording = item.isRecordingInProgress

    val (statusText, statusColor, statusIcon) = when {
        isRecording -> Triple("録画中", recordingRed, null)
        item.recordingAvailability == "Full" -> Triple("録画可能", normalGreen, Icons.Default.Check)
        item.recordingAvailability == "Partial" -> Triple(
            "一部のみ録画",
            warningYellow,
            Icons.Default.Warning
        )

        item.recordingAvailability == "None" || item.recordingAvailability.equals(
            "unavailable",
            ignoreCase = true
        ) ->
            Triple("録画重複", errorRed, Icons.Default.Warning)

        else -> Triple(item.recordingAvailability, warningYellow, Icons.Default.Warning)
    }

    val timeInfo = remember(program.startTime, program.endTime, timeFormat) {
        try {
            val start = OffsetDateTime.parse(program.startTime).atZoneSameInstant(ZoneId.systemDefault())
            val end = OffsetDateTime.parse(program.endTime).atZoneSameInstant(ZoneId.systemDefault())
            val durationMin = ChronoUnit.MINUTES.between(start, end)
            "${ProgramTimeFormatter.formatRange(start, end, timeFormat, "yyyy/MM/dd (E)", " ~ ")} (${durationMin}分)"
        } catch (e: Exception) {
            program.startTime
        }
    }

    val fileSizeInfo = remember(item.estimatedRecordingFileSize) {
        val gb = item.estimatedRecordingFileSize.toDouble() / (1024 * 1024 * 1024)
        String.format("約 %.1fGB", gb)
    }

    // ★修正: EDCBとKonomiTVの両方に対応した非同期ロゴ取得
    val displayId = item.channel.displayChannelId ?: item.channel.id
    var logoUrl by remember(item.channel.id) { mutableStateOf("") }

    LaunchedEffect(item.channel.id) {
        val fetchedUrl = getLogoUrl(displayId)
        logoUrl = fetchedUrl.ifEmpty {
            // 取得できなかった場合はKonomiTVのURLビルダーにフォールバック
            UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, displayId)
        }
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TvCardRadiusListRow)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = focusSpec.focusedBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(badgeBgColor, CircleShape)
                    )
                    Text(
                        text = settings.priority.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "優先度",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = subTextColor
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .tvCardMarquee(isFocused)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // ロゴバッジは共通の ChannelLogoBadge で描画 (未取得時は Tv アイコンにフォールバック)
                    ChannelLogoBadge(
                        logoUrl = logoUrl,
                        width = 48.dp,
                        height = 27.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${item.channel.channelNumber} ${item.channel.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = program.description ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = subTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (isFocused && statusColor == normalGreen) (if (colors.isDark) Color.Black else Color.White) else statusColor,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(statusColor, CircleShape)
                            )
                        } else if (statusIcon != null) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = timeInfo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        null,
                        tint = subTextColor,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = fileSizeInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = subTextColor
                    )
                }
            }
        }
    }
}
