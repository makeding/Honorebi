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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.NetworkCheck
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
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

internal fun screensaverDate(now: LocalDateTime): String =
    now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.JAPANESE))

// Draw in the full surface so the feather cannot be clipped to text or row bounds.
@Composable
internal fun ScreensaverReadabilityOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().testTag("screensaver-readability")) { drawScreensaverReadability() }
}

internal fun DrawScope.drawScreensaverReadability() {
    fun corner(start: Offset, opacity: Float) {
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Black.copy(alpha = opacity),
                0.35f to Color.Black.copy(alpha = opacity * 0.5f),
                0.7f to Color.Black.copy(alpha = opacity * 0.12f),
                1f to Color.Transparent,
                start = start,
                end = center,
            ),
        )
    }
    corner(Offset(0f, size.height), 0.52f)
    corner(Offset(size.width, 0f), 0.46f)
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ScreensaverContent(
    imageUris: List<String>,
    intervalSeconds: Int,
    transition: String,
    upcomingRecordings: UpcomingRecordingsState? = null,
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
        ScreensaverReadabilityOverlay()
        Column(
            modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 460.dp).padding(64.dp).testTag("screensaver-clock"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                now.format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.displayLarge.copy(shadow = null),
            )
            Text(
                screensaverDate(now),
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleLarge.copy(shadow = null),
            )
            if (imageUris.isEmpty()) {
                Text("設定からスクリーンセーバー画像を追加してください", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium.copy(shadow = null))
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(48.dp).testTag("screensaver-status"),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreensaverNetworkIcon(network.transport)
            StatusLine(Icons.Default.Cast, deviceName)
        }
        if (upcomingRecordings != null) {
            UpcomingRecordingsPanel(
                state = upcomingRecordings,
                modifier = Modifier.align(Alignment.BottomEnd).padding(64.dp),
            )
        }
    }
}

@Composable
internal fun ScreensaverNetworkIcon(transport: NetworkTransport, modifier: Modifier = Modifier) {
    val (icon, description) = when (transport) {
        NetworkTransport.WIFI -> Icons.Default.Wifi to "Wi-Fi"
        NetworkTransport.ETHERNET -> Icons.Default.SettingsEthernet to "有線LAN"
        NetworkTransport.OTHER -> Icons.Default.NetworkCheck to "ネットワーク接続中"
        NetworkTransport.DISCONNECTED -> Icons.Default.WifiOff to "ネットワーク未接続"
    }
    Icon(
        icon,
        contentDescription = description,
        tint = Color.White.copy(alpha = 0.85f),
        modifier = modifier.size(24.dp).testTag("screensaver-network"),
    )
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.titleMedium.copy(shadow = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 210.dp))
    }
}
