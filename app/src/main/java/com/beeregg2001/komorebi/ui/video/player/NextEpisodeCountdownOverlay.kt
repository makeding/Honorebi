package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.delay

@Composable
internal fun NextEpisodeCountdownOverlay(program: RecordedProgram, progress: Float, onPlayNow: () -> Unit, onCancel: () -> Unit) {
    val playNowRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); playNowRequester.safeRequestFocus("VideoPlayerScreen") }
    Box(Modifier.padding(end = 48.dp, bottom = 64.dp).width(420.dp).background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp)).border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).padding(18.dp).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)) { onCancel(); true } else false
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("次のエピソードを再生します", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(program.title, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.24f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlayNow, modifier = Modifier.focusRequester(playNowRequester), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("今すぐ") }
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f), contentColor = Color.White)) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text("キャンセル") }
            }
        }
    }
}
