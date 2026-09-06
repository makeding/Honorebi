package com.beeregg2001.komorebi.ui.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DataBroadcastingColorKeySpec(
    val key: DataBroadcastingColorKey,
    val label: String,
    val color: Color,
    val contentColor: Color,
    val alignment: Alignment,
)

private val dataBroadcastingColorKeySpecs = listOf(
    DataBroadcastingColorKeySpec(DataBroadcastingColorKey.Blue, "青", Color(0xFF006EDC), Color.White, Alignment.TopCenter),
    DataBroadcastingColorKeySpec(DataBroadcastingColorKey.Red, "赤", Color(0xFFC90000), Color.White, Alignment.CenterStart),
    DataBroadcastingColorKeySpec(DataBroadcastingColorKey.Green, "緑", Color(0xFF1B8700), Color.White, Alignment.CenterEnd),
    DataBroadcastingColorKeySpec(DataBroadcastingColorKey.Yellow, "黄", Color(0xFFE3B200), Color.Black, Alignment.BottomCenter),
)

/** Shared BML color-key chooser; both live and recorded preserve the same fixed geometry. */
@Composable
fun DataBroadcastingColorSelectorOverlay(
    selectedKey: DataBroadcastingColorKey,
    onColorSelected: (DataBroadcastingColorKey) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 72.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .size(248.dp)
                .background(Color.Black.copy(alpha = 0.68f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .padding(14.dp),
        ) {
            dataBroadcastingColorKeySpecs.forEach { spec ->
                DataBroadcastingColorSelectorButton(
                    spec = spec,
                    selected = selectedKey == spec.key,
                    onClick = { onColorSelected(spec.key) },
                    modifier = Modifier.align(spec.alignment),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Light,
                )
            }
        }
    }
}

@Composable
private fun DataBroadcastingColorSelectorButton(
    spec: DataBroadcastingColorKeySpec,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(if (selected) 76.dp else 68.dp)
            .clip(CircleShape)
            .background(spec.color)
            .border(if (selected) 4.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = spec.label,
            color = spec.contentColor,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp),
            fontWeight = FontWeight.Bold,
        )
    }
}

fun isDataBroadcastingToggleKeyEvent(keyEvent: KeyEvent): Boolean =
    keyEvent.type == KeyEventType.KeyUp && when (keyEvent.nativeKeyEvent.keyCode) {
        NativeKeyEvent.KEYCODE_TV_DATA_SERVICE,
        NativeKeyEvent.KEYCODE_D -> true
        else -> false
    }
