package com.beeregg2001.komorebi.ui.screensaver

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import com.beeregg2001.komorebi.ui.main.rememberNetworkConnectionStatus
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ScreensaverContent(
    imageUris: List<String>,
    intervalSeconds: Int,
    transition: String,
    nextRecording: String?,
    modifier: Modifier = Modifier,
) {
    var currentImage by remember(imageUris) { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val network by rememberNetworkConnectionStatus()
    val context = LocalContext.current
    val deviceName = remember(context) {
        val configured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else null
        configured?.takeIf(String::isNotBlank) ?: Build.MODEL
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000)
        }
    }
    LaunchedEffect(imageUris, intervalSeconds) {
        if (imageUris.size > 1) {
            while (true) {
                delay(intervalSeconds.coerceAtLeast(1) * 1_000L)
                currentImage = (currentImage + 1) % imageUris.size
            }
        }
    }

    val transitionSpec = when (transition) {
        "SLIDE" -> slideInHorizontally { it / 8 } togetherWith slideOutHorizontally { -it / 8 }
        "FADE" -> fadeIn() togetherWith fadeOut()
        else -> EnterTransition.None togetherWith ExitTransition.None
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (imageUris.isNotEmpty()) {
            AnimatedContent(
                targetState = currentImage,
                transitionSpec = { transitionSpec },
                label = "ScreensaverImage",
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                AsyncImage(
                    model = imageUris.getOrNull(index),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))

        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                now.format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd")),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            StatusLine(Icons.Default.NetworkCheck, when (network.transport) {
                NetworkTransport.WIFI -> "Wi-Fi"
                NetworkTransport.ETHERNET -> "Ethernet"
                NetworkTransport.OTHER -> "ネットワーク接続中"
                NetworkTransport.DISCONNECTED -> "ネットワーク未接続"
            })
            StatusLine(Icons.Default.Dns, deviceName)
            StatusLine(Icons.Default.Radio, nextRecording ?: "次の録画予約はありません")
            if (imageUris.isEmpty()) {
                Text("設定からスクリーンセーバー画像を追加してください", color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}
