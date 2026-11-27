package com.beeregg2001.komorebi.ui.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class VirtualKeyVisual(
    val background: Color,
    val content: Color,
    val minWidthDp: Int = 34,
    val italic: Boolean = false,
)

private fun visualFor(key: DataBroadcastingVirtualKey): VirtualKeyVisual = when (key) {
    DataBroadcastingVirtualKey.Blue -> VirtualKeyVisual(Color(0xFF1E88E5), Color.White, minWidthDp = 38)
    DataBroadcastingVirtualKey.Red -> VirtualKeyVisual(Color(0xFFD32F2F), Color.White, minWidthDp = 38)
    DataBroadcastingVirtualKey.Green -> VirtualKeyVisual(Color(0xFF2E7D32), Color.White, minWidthDp = 38)
    DataBroadcastingVirtualKey.Yellow -> VirtualKeyVisual(Color(0xFFFBC02D), Color(0xFF1A1A1A), minWidthDp = 38)
    DataBroadcastingVirtualKey.Data -> VirtualKeyVisual(Color(0xFF263238), Color.White, minWidthDp = 38, italic = true)
    DataBroadcastingVirtualKey.Back -> VirtualKeyVisual(Color(0xFF263238), Color.White, minWidthDp = 38)
    DataBroadcastingVirtualKey.Close -> VirtualKeyVisual(Color(0xFF1C232B), Color.White, minWidthDp = 64)
    else -> VirtualKeyVisual(Color(0xFF1A222D), Color.White, minWidthDp = 32)
}

/**
 * Classic single-row bottom virtual remote bar for remotes lacking dedicated
 * color, data, and numeric keys. Shared by both live and recorded players.
 */
@Composable
fun DataBroadcastingVirtualRemoteBarOverlay(
    selectedKey: DataBroadcastingVirtualKey,
    onKeyClick: (DataBroadcastingVirtualKey) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("data-broadcasting-virtual-remote-bar"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.0f),
                            Color.Black.copy(alpha = 0.78f),
                            Color.Black.copy(alpha = 0.92f),
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "◀ ▶",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(12.dp))

            DataBroadcastingVirtualKey.ALL.forEachIndexed { index, key ->
                if (index == 4 || index == 6 || index == 16) {
                    Spacer(Modifier.width(10.dp))
                } else if (index > 0) {
                    Spacer(Modifier.width(6.dp))
                }
                VirtualRemoteKeyChip(
                    key = key,
                    selected = selectedKey == key,
                    onClick = {
                        if (key == DataBroadcastingVirtualKey.Close) {
                            onDismiss()
                        } else {
                            onKeyClick(key)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VirtualRemoteKeyChip(
    key: DataBroadcastingVirtualKey,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val visual = visualFor(key)
    val shape = RoundedCornerShape(6.dp)
    val borderColor = if (selected) Color.White else Color.White.copy(alpha = 0.35f)
    val borderWidth = if (selected) 2.5.dp else 1.dp

    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(min = visual.minWidthDp.dp)
            .clip(shape)
            .background(if (selected) visual.background else visual.background.copy(alpha = 0.85f))
            .border(borderWidth, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag("virtual-remote-key-${key.name}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            color = visual.content,
            fontSize = if (key == DataBroadcastingVirtualKey.Data) 18.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = if (visual.italic) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

fun isDataBroadcastingToggleKeyEvent(keyEvent: KeyEvent): Boolean =
    keyEvent.type == KeyEventType.KeyUp && when (keyEvent.nativeKeyEvent.keyCode) {
        NativeKeyEvent.KEYCODE_TV_DATA_SERVICE,
        NativeKeyEvent.KEYCODE_D -> true
        else -> false
    }
