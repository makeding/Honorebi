package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Shared notice lane below the clock; never participates in focus navigation. */
@Composable
fun PlaybackLoadingNotice(
    message: String = "読み込み中…",
    loading: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(start = 24.dp, top = 96.dp, end = 24.dp)) {
        Row(
            Modifier.align(Alignment.TopStart).testTag("playback-loading-notice")
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
            }
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold)
        }
    }
}
