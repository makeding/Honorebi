@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.media.tv.TvContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TifDebugChannel(
    val rowId: Long,
    val name: String,
    val number: String,
    val onid: String,
    val tsid: String,
    val sid: String,
    val logoUri: String,
    val hasLogo: Boolean,
    val epgCount: Int,
)

@Composable
internal fun TifChannelDebugDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val closeFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val result by produceState<Result<List<TifDebugChannel>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val epgCounts = mutableMapOf<Long, Int>()
                runCatching {
                    context.contentResolver.query(
                        TvContract.Programs.CONTENT_URI,
                        arrayOf(TvContract.Programs.COLUMN_CHANNEL_ID),
                        null,
                        null,
                        null,
                    )?.use { progCursor ->
                        while (progCursor.moveToNext()) {
                            if (!progCursor.isNull(0)) {
                                val chId = progCursor.getLong(0)
                                epgCounts[chId] = (epgCounts[chId] ?: 0) + 1
                            }
                        }
                    }
                }

                val columns = arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
                    TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
                    TvContract.Channels.COLUMN_SERVICE_ID,
                )
                context.contentResolver.query(TvContract.Channels.CONTENT_URI, columns, null, null, null)!!.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val rowId = if (cursor.isNull(0)) -1L else cursor.getLong(0)
                            fun field(index: Int) = if (cursor.isNull(index)) "NULL" else cursor.getString(index)
                            val logoUri = if (rowId >= 0L) TvContract.buildChannelLogoUri(rowId) else null
                            val hasLogo = if (logoUri != null) {
                                runCatching {
                                    context.contentResolver.openAssetFileDescriptor(logoUri, "r")?.use { afd ->
                                        afd.length != 0L
                                    } ?: false
                                }.getOrDefault(false)
                            } else false
                            add(
                                TifDebugChannel(
                                    rowId = rowId,
                                    name = field(1),
                                    number = field(2),
                                    onid = field(3),
                                    tsid = field(4),
                                    sid = field(5),
                                    logoUri = logoUri?.toString() ?: "NULL",
                                    hasLogo = hasLogo,
                                    epgCount = epgCounts[rowId] ?: 0,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    val channels = result?.getOrNull().orEmpty()
    val totalEpg = remember(channels) { channels.sumOf { it.epgCount } }
    val logoAvailableCount = remember(channels) { channels.count { it.hasLogo } }

    LaunchedEffect(channels.isNotEmpty()) {
        withFrameNanos { }
        if (channels.isNotEmpty()) {
            firstRowFocus.safeRequestFocusWithRetry("TifDebugFirstRow")
        } else {
            closeFocus.safeRequestFocusWithRetry("TifDebugClose")
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface1)
                .border(1.dp, colors.textPrimary.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "TV入力 チャンネル診断",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                if (result?.isSuccess == true) {
                    DebugSummaryPill("全 ${channels.size} ch")
                    DebugSummaryPill("EPG 合計 ${totalEpg} 件")
                    DebugSummaryPill("ロゴ ${logoAvailableCount} / ${channels.size}")
                }
                Spacer(Modifier.weight(1f))
                DialogActionButton(
                    role = DialogActionRole.Secondary,
                    onClick = onDismiss,
                    modifier = Modifier
                        .focusRequester(closeFocus)
                        .focusProperties {
                            down = if (channels.isNotEmpty()) firstRowFocus else FocusRequester.Cancel
                        },
                ) {
                    Text("閉じる", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(
                color = colors.textPrimary.copy(alpha = 0.12f),
                modifier = Modifier.padding(vertical = 10.dp),
            )

            when {
                result == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("読み込み中…", color = colors.textSecondary)
                }

                result!!.isFailure -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("取得できません: ${result!!.exceptionOrNull()?.message}", color = colors.danger)
                }

                channels.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("チャンネルがありません", color = colors.textSecondary)
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        itemsIndexed(channels, key = { idx, ch -> "${ch.rowId}_$idx" }) { index, channel ->
                            TifDebugChannelRow(
                                channel = channel,
                                modifier = Modifier
                                    .then(if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier)
                                    .focusProperties {
                                        if (index == 0) up = closeFocus
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugSummaryPill(text: String) {
    val colors = KomorebiTheme.colors
    Text(
        text = text,
        color = colors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(colors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TifDebugChannelRow(
    channel: TifDebugChannel,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.65f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface2,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.07f))),
            focusedBorder = Border(BorderStroke(1.5.dp, colors.accent)),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp, 28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.10f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.hasLogo) {
                    AsyncImage(
                        model = channel.logoUri,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                    )
                } else {
                    Text(
                        "NO LOGO",
                        color = colors.textSecondary.copy(alpha = 0.6f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1.35f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = channel.number,
                        color = colors.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = channel.name,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                if (channel.hasLogo) Color(0xFF4CAF50) else colors.textSecondary.copy(alpha = 0.4f),
                                CircleShape,
                            )
                    )
                    Text(
                        text = channel.logoUri,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = "ONID:${channel.onid}  TSID:${channel.tsid}  SID:${channel.sid}",
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "EPG ${channel.epgCount}件",
                color = if (channel.epgCount > 0) colors.textPrimary else colors.textSecondary.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(
                        if (channel.epgCount > 0) colors.accent.copy(alpha = 0.22f)
                        else colors.textPrimary.copy(alpha = 0.06f),
                        RoundedCornerShape(5.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
