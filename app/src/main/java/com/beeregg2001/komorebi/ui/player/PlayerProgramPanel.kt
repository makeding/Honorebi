package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.ui.components.rememberChannelLogoImageLoader

/** Same fixed logo slot for live status, recorded status and program details. */
@Composable
fun PlayerChannelLogo(
    logoUrl: String,
    channelName: String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier
) {
    var loaded by remember(logoUrl) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(2.dp)).background(Color.White), Alignment.Center) {
        if (!loaded) {
            Text(channelName.ifBlank { "チャンネル情報なし" }, color = Color.Black,
                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(2.dp).testTag("channel-logo-fallback"))
        }
        AsyncImage(
            imageLoader = rememberChannelLogoImageLoader(), model = logoUrl,
            contentDescription = channelName,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (shouldCropLogo) ContentScale.Crop else ContentScale.Fit,
            onSuccess = { loaded = true }, onError = { loaded = false }
        )
    }
}

/** Fixed slots across loading, missing EPG, errors and long content; body owns scrolling. */
@Composable
fun PlayerProgramPanel(
    channelName: String,
    logoUrl: String,
    shouldCropLogo: Boolean,
    title: String,
    description: String?,
    detail: Map<String, String>?,
    metadata: List<Pair<String, String>>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    channelAdornment: @Composable RowScope.() -> Unit = {},
    feedback: @Composable RowScope.() -> Unit = {
        Text("番組情報  ·  上下キーでスクロール  ·  戻るで閉じる", color = Color.White.copy(0.7f))
    }
) {
    var titleOverflows by remember(title) { mutableStateOf(false) }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().height(440.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f))))
            .padding(horizontal = 64.dp, vertical = 24.dp).testTag("program-panel")) {
            Row(Modifier.fillMaxWidth().height(45.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerChannelLogo(logoUrl, channelName, shouldCropLogo, Modifier.size(80.dp, 45.dp))
                Spacer(Modifier.width(24.dp))
                Text(channelName.ifBlank { "チャンネル情報なし" }, color = Color.White.copy(0.8f),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                channelAdornment()
            }
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, lineHeight = 34.sp, overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleOverflows = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth().height(84.dp).padding(vertical = 8.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).testTag("program-body")) {
                // Full title remains accessible even when it exceeds the fixed header.
                if (titleOverflows) Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                metadata.forEach { (label, value) ->
                    Text("$label: $value", color = Color.White.copy(0.75f),
                        modifier = Modifier.padding(top = 4.dp))
                }
                Text(description?.takeIf { it.isNotBlank() } ?: "番組の紹介は保存されていません。",
                    color = Color.White.copy(0.9f), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 16.dp))
                if (detail.isNullOrEmpty()) {
                    Text("詳細な番組情報は保存されていません。", color = Color.White.copy(0.7f))
                } else {
                    detail.forEach { (heading, body) ->
                        Text("◆ $heading", color = Color.White.copy(0.6f), fontWeight = FontWeight.Bold)
                        Text(body, color = Color.White.copy(0.85f),
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 14.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(64.dp).testTag("program-feedback"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp), content = feedback)
        }
    }
}
