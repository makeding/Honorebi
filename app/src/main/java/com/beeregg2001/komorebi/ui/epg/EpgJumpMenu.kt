package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import com.beeregg2001.komorebi.data.util.toDeviceTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Immutable
data class EpgSlotState(
    val time: OffsetDateTime,
    val isSelectable: Boolean,
    val baseColor: Color,
    val globalIndex: Int
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpgJumpMenu(
    dates: List<OffsetDateTime>,
    initialTime: OffsetDateTime, // ★ 追加: 初期フォーカス用の時間（現在見ている時間）
    timeFormat: String,          // ★ 追加: 12H/24H フォーマット
    onSelect: (OffsetDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val now = remember { OffsetDateTime.now().truncatedTo(ChronoUnit.HOURS) }
    val fullTimeSlots = remember { (0..23).toList() }

    val gridData = remember(dates, now, colors) {
        dates.mapIndexed { dIdx, date ->
            fullTimeSlots.map { hour ->
                val slotTime = date.withHour(hour).truncatedTo(ChronoUnit.HOURS)
                EpgSlotState(
                    time = slotTime,
                    isSelectable = !slotTime.isBefore(now),
                    baseColor = getTimeSlotColor(hour, colors),
                    globalIndex = (dIdx * 24) + hour
                )
            }
        }
    }

    var globalFocusedIndex by remember { mutableIntStateOf(-1) }
    val slotHeight = 13.dp
    val columnWidth = 85.dp

    val focusRequesters = remember(dates.size) {
        List(dates.size) { List(24) { FocusRequester() } }
    }

    // ★ 修正: ただ最初の枠を探すのではなく、initialTime に最も近い枠を探してフォーカスする
    LaunchedEffect(initialTime) {
        delay(100)
        var targetDIdx = 0
        var targetTIdx = 0
        var minDiff = Long.MAX_VALUE

        for (dIdx in gridData.indices) {
            for (tIdx in 0..23) {
                val slot = gridData[dIdx][tIdx]
                if (slot.isSelectable) {
                    // initialTime との分単位での差分を計算
                    val diff = Math.abs(ChronoUnit.MINUTES.between(slot.time, initialTime))
                    if (diff < minDiff) {
                        minDiff = diff
                        targetDIdx = dIdx
                        targetTIdx = tIdx
                    }
                }
            }
        }

        if (dates.isNotEmpty() && gridData.isNotEmpty() && gridData[targetDIdx][targetTIdx].isSelectable) {
            focusRequesters[targetDIdx][targetTIdx].safeRequestFocus("EpgJumpMenu_Initial")
        } else if (dates.isNotEmpty()) {
            focusRequesters[0][0].safeRequestFocus("EpgJumpMenuFallback")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) {
                        return@onKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        onDismiss()
                        return@onKeyEvent true
                    }
                }
                false
            }
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .wrapContentHeight()
                .focusGroup(),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.2f)))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "日時指定ジャンプ",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    ),
                    color = colors.textPrimary, modifier = Modifier.padding(bottom = 8.dp)
                )

                Row {
                    Column(horizontalAlignment = Alignment.End) {
                        Box(modifier = Modifier
                            .width(60.dp)
                            .height(35.dp))
                        // ★ 修正: timeFormat を渡す
                        fullTimeSlots.forEach { hour ->
                            TimeLabelCell(
                                hour,
                                slotHeight,
                                timeFormat
                            )
                        }
                    }

                    gridData.forEachIndexed { dIdx, daySlots ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HeaderCell(dates[dIdx], columnWidth)
                            daySlots.forEachIndexed { tIdx, slot ->
                                val isHighlighted =
                                    globalFocusedIndex != -1 && slot.globalIndex >= globalFocusedIndex && slot.globalIndex < globalFocusedIndex + 3
                                var isFocused by remember { mutableStateOf(false) }

                                Box(
                                    modifier = Modifier
                                        .width(columnWidth)
                                        .height(slotHeight)
                                        .focusRequester(focusRequesters[dIdx][tIdx])
                                        .focusProperties {
                                            if (tIdx == 23 && dIdx < dates.size - 1) {
                                                down = focusRequesters[dIdx + 1][0]
                                            } else if (tIdx == 23) {
                                                down = FocusRequester.Cancel
                                            }

                                            if (tIdx == 0 && dIdx > 0) {
                                                up = focusRequesters[dIdx - 1][23]
                                            } else if (tIdx == 0) {
                                                up = FocusRequester.Cancel
                                            }

                                            if (dIdx == 0) {
                                                left = FocusRequester.Cancel
                                            }
                                            if (dIdx == dates.size - 1) {
                                                right = FocusRequester.Cancel
                                            }
                                        }
                                        .onFocusChanged {
                                            isFocused = it.isFocused
                                            if (it.isFocused) {
                                                globalFocusedIndex = slot.globalIndex
                                            }
                                        }
                                        .focusable(enabled = slot.isSelectable)
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                                onSelect(slot.time); true
                                            } else false
                                        }
                                        .background(
                                            if (isHighlighted || isFocused) colors.accent else if (!slot.isSelectable) slot.baseColor.copy(
                                                alpha = 0.1f
                                            ) else slot.baseColor
                                        )
                                        .border(
                                            width = if (isFocused) 2.dp else 0.5.dp,
                                            color = if (isFocused) colors.textPrimary else if (!slot.isSelectable) Color.Transparent else colors.background.copy(
                                                0.3f
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ★ 修正: timeFormat に応じてラベルの表示を切り替える
@Composable
private fun TimeLabelCell(hour: Int, height: Dp, timeFormat: String) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .height(height)
            .width(60.dp)
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        val label = if (timeFormat == "12H") {
            when {
                hour == 0 -> "AM 0"
                hour == 12 -> "PM 0"
                hour % 3 == 0 -> "${hour % 12}"
                else -> ""
            }
        } else {
            when {
                hour % 3 == 0 -> "$hour:00"
                else -> ""
            }
        }

        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 10.sp,
                color = colors.textSecondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HeaderCell(date: OffsetDateTime, width: Dp) {
    val colors = KomorebiTheme.colors
    val localDate = date.toDeviceTime()
    val isSunday = localDate.dayOfWeek.value == 7
    val isSaturday = localDate.dayOfWeek.value == 6
    Column(
        modifier = Modifier
            .width(width)
            .height(35.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = localDate.format(DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary
        )
        Text(
            text = localDate.format(DateTimeFormatter.ofPattern("(E)", Locale.JAPANESE)),
            fontSize = 10.sp,
            color = when {
                isSunday -> Color(0xFFFF5252); isSaturday -> Color(0xFF448AFF); else -> colors.textSecondary
            }
        )
    }
}

fun getTimeSlotColor(hour: Int, colors: com.beeregg2001.komorebi.ui.theme.KomorebiColors): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)
        in 11..16 -> Color(0xFF2B422B)
        in 17..22 -> Color(0xFF2B2B42)
        else -> colors.surface
    }
}
