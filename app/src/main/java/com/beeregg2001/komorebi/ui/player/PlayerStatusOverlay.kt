package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.LocalLateNightWatchEnabled
import com.beeregg2001.komorebi.common.lateNightWatchMessage
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val wallClock12HourFormat = DateTimeFormatter.ofPattern("a h:mm", Locale.JAPANESE)
private val wallClock24HourFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPANESE)
private val wallClockMonthDaySpaceFormat = DateTimeFormatter.ofPattern("M/d ", Locale.JAPANESE)

fun formatPlayerWallClock(now: ZonedDateTime, timeFormat: String): String =
    now.format(if (timeFormat == "12H") wallClock12HourFormat else wallClock24HourFormat)

fun formatPlayerEndTime(now: ZonedDateTime, end: ZonedDateTime, timeFormat: String): String {
    val day = when (end.toLocalDate()) {
        now.toLocalDate() -> ""
        now.toLocalDate().plusDays(1) -> "翌日 "
        else -> end.format(wallClockMonthDaySpaceFormat)
    }
    return "$day${formatPlayerWallClock(end, timeFormat)} 終了予定"
}

/** Composed only while visible; each read resolves the current system time zone. */
@Composable
fun PlayerWallClock(
    timeFormat: String,
    modifier: Modifier = Modifier,
    now: () -> ZonedDateTime = { ZonedDateTime.now() },
    secondaryText: (ZonedDateTime) -> String = { "" },
    secondaryModifier: Modifier = Modifier,
    showCurrentTime: Boolean = true,
) {
    val currentNow by rememberUpdatedState(now)
    val currentSecondaryText by rememberUpdatedState(secondaryText)
    val lateNightWatchEnabled = LocalLateNightWatchEnabled.current
    fun read(): Pair<String, String> {
        val current = currentNow()
        val secondary = currentSecondaryText(current).ifBlank {
            if (lateNightWatchEnabled) lateNightWatchMessage(current).orEmpty() else ""
        }
        return formatPlayerWallClock(current, timeFormat) to secondary
    }
    var display by remember(timeFormat, secondaryText, lateNightWatchEnabled) { mutableStateOf(read()) }
    LaunchedEffect(timeFormat, secondaryText, lateNightWatchEnabled) {
        while (true) {
            val latest = read()
            if (latest != display) display = latest
            delay(1_000)
        }
    }
    Column(
        modifier.size(256.dp, 56.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        if (showCurrentTime) {
            Text(display.first, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.height(34.dp),
                style = TextStyle(fontFeatureSettings = "tnum",
                    shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 4f)))
        } else {
            Spacer(Modifier.height(34.dp))
        }
        Text(display.second, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
            maxLines = 1, modifier = secondaryModifier.height(22.dp),
            style = TextStyle(fontFeatureSettings = "tnum",
                shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(1f, 2f), 4f)))
    }
}

/** The channel name and logo share one card across live and recorded playback. */
@Composable
fun PlayerChannelStatus(
    channelName: String,
    logoUrl: String,
    shouldCropLogo: Boolean,
    modifier: Modifier = Modifier,
    nameModifier: Modifier = Modifier,
) {
    Row(
        modifier.height(48.dp).widthIn(max = 380.dp)
            .background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            channelName.ifBlank { "チャンネル情報なし" },
            modifier = nameModifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        PlayerChannelLogo(logoUrl, channelName, shouldCropLogo, Modifier.size(56.dp, 32.dp))
    }
}

/** Full-screen black overlay for audio-only playback; any key or click wakes the screen. */
@Composable
fun PlayerScreenOffOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val isFirstTime = remember(context) {
        val prefs = context.getSharedPreferences("komorebi_player_hints", android.content.Context.MODE_PRIVATE)
        val seen = prefs.getBoolean("screen_off_hint_seen", false)
        if (!seen) {
            prefs.edit().putBoolean("screen_off_hint_seen", true).apply()
        }
        !seen
    }
    var showHint by remember { mutableStateOf(true) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(context) {
        var ctx: android.content.Context? = context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) {
            ctx = ctx.baseContext
        }
        val window = (ctx as? android.app.Activity)?.window
        val originalCallback = window?.callback
        if (window != null && originalCallback != null) {
            window.callback = object : android.view.Window.Callback by originalCallback {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        currentOnDismiss()
                    }
                    return true
                }
            }
        }
        onDispose {
            if (window != null && originalCallback != null && window.callback !== originalCallback) {
                window.callback = originalCallback
            }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        delay(if (isFirstTime) 6000L else 3000L)
        showHint = false
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    onDismiss()
                }
                true
            }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = showHint,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 48.dp),
            ) {
                Text(
                    "画面オフ中（いずれかのキーで復帰）",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                )
                if (isFirstTime) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "※システムの制限により画面を完全に消灯できないため、テレビ側の設定に「スマートバックライト調整」や「自動調光」などがある場合は有効にすることをおすすめします。",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}
