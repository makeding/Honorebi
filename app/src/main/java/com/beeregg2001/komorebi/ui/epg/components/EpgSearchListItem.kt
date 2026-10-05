package com.beeregg2001.komorebi.ui.epg.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.common.ProgramTimeFormatter
import com.beeregg2001.komorebi.viewmodel.UiSearchResultItem
import com.beeregg2001.komorebi.data.util.EpgUtils
import com.beeregg2001.komorebi.data.util.toDeviceTime
import com.beeregg2001.komorebi.ui.components.ChannelLogoBadge
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.StatusRecordingColor
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import com.beeregg2001.komorebi.ui.theme.tvCardMarquee
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpgSearchListItem(
    resultItem: UiSearchResultItem,
    reserveItem: ReserveItem?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    timeFormat: String // ★ 追加: 12H/24H フォーマットを受け取る
) {
    val program = resultItem.program
    val channel = resultItem.channel
    val logoUrl = resultItem.logoUrl
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    val isReserved = reserveItem != null
    val isRecording = reserveItem?.isRecordingInProgress == true

    val inverseColor = if (colors.isDark) Color.Black else Color.White
    val context = LocalContext.current

    // フォーカス演出 (スケール / 枠) は共通トークンに一元化
    val focusSpec = tvCardFocus(TvCardFamily.LIST_ROW)

    val imageRequest = remember(logoUrl) {
        ImageRequest.Builder(context)
            .data(logoUrl)
            .size(180, 100)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val primaryTextColor = if (isFocused) inverseColor else colors.textPrimary
    val secondaryTextColor =
        if (isFocused) inverseColor.copy(alpha = 0.8f) else colors.textSecondary

    // ★ 修正: timeFormat の値によって DateTimeFormatter のパターンを動的に変更する
    val displayDate = remember(program.start_time, program.end_time, timeFormat) {
        try {
            ProgramTimeFormatter.formatRange(
                program.start_time,
                program.end_time,
                timeFormat,
                "M/d(E)",
            ).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    val channelTypeLabel = when (channel.type) {
        "GR" -> "地デジ"
        else -> channel.type
    }

    val majorGenre = program.genres?.firstOrNull()?.major
    val genreColor = EpgUtils.getGenreColor(majorGenre)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TvCardRadiusListRow)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = inverseColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = focusSpec.focusedBorder
        )
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // ロゴバッジは共通の ChannelLogoBadge で描画 (未取得時は Tv アイコンにフォールバック)
            ChannelLogoBadge(
                logoUrl = logoUrl,
                width = 100.dp,
                height = 56.dp,
                model = imageRequest
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 1.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (majorGenre != null) {
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .background(genreColor, RoundedCornerShape(2.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = majorGenre,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = program.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = primaryTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .tvCardMarquee(isFocused)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                ) {
                    Text(
                        text = "$displayDate | $channelTypeLabel ${channel.channel_number ?: "---"} ${channel.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isRecording) {
                        Text(
                            text = "録画中",
                            color = if (isFocused) inverseColor else StatusRecordingColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else if (isReserved) {
                        Text(
                            text = "予約済",
                            color = if (isFocused) inverseColor else colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            if (isFocused) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "詳細を見る",
                    tint = inverseColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 4.dp)
                )
            }
        }
    }
}
