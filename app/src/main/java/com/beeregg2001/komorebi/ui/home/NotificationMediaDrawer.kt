@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.notification.ERROR_CODE_NOTIFICATION_ACCESS_REQUIRED
import com.beeregg2001.komorebi.notification.ERROR_CODE_NOTIFICATION_SETTINGS_UNAVAILABLE
import com.beeregg2001.komorebi.notification.ExternalMediaSessionState
import com.beeregg2001.komorebi.notification.InternalNowPlayingState
import com.beeregg2001.komorebi.notification.MediaTransportAction
import com.beeregg2001.komorebi.notification.NotificationAccessStatus
import com.beeregg2001.komorebi.notification.NotificationCenterUiState
import com.beeregg2001.komorebi.notification.NotificationSettingsOpenResult
import com.beeregg2001.komorebi.notification.SystemNotificationItem
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class NotificationDrawerOpenReason {
    BELL,
    MEDIA_PILL,
}

internal const val NOTIFICATION_DRAWER_TEST_TAG = "notification-media-drawer"
internal const val NOTIFICATION_DRAWER_LIST_TEST_TAG = "notification-drawer-list"
internal const val PERMISSION_CARD_TEST_TAG = "notification-permission-card"
internal const val PERMISSION_SETTINGS_BUTTON_TEST_TAG = "notification-permission-settings-button"
internal const val INTERNAL_NOW_PLAYING_CARD_TEST_TAG = "internal-now-playing-card"
internal const val EMPTY_NOTIFICATIONS_TEST_TAG = "notification-empty-state"
internal const val FOOTER_CLEAR_ALL_TEST_TAG = "notification-footer-clear-all"
internal const val FOOTER_CLOSE_TEST_TAG = "notification-footer-close"

internal fun externalMediaPlayPauseTestTag(sessionKey: String): String = "media-play-pause-$sessionKey"
internal fun notificationRowCardTestTag(key: String): String = "notification-card-$key"
internal fun notificationRowDismissTestTag(key: String): String = "notification-dismiss-$key"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun NotificationMediaDrawer(
    uiState: NotificationCenterUiState,
    internalNowPlaying: InternalNowPlayingState?,
    openReason: NotificationDrawerOpenReason,
    timeFormat: String,
    onReturnToInternalPlayer: () -> Unit,
    onSendMediaAction: (ExternalMediaSessionState, MediaTransportAction) -> Unit,
    onOpenMediaApp: (ExternalMediaSessionState) -> Unit,
    onOpenNotification: (SystemNotificationItem) -> Unit,
    onDismissNotification: (String) -> Unit,
    onClearAllNotifications: () -> Unit,
    onOpenNotificationSettings: () -> NotificationSettingsOpenResult,
    onRefreshAccessStatus: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    var settingsOpenResult by remember { mutableStateOf<NotificationSettingsOpenResult?>(null) }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    val permissionSettingsFocus = remember { FocusRequester() }
    val internalNowPlayingFocus = remember { FocusRequester() }
    val firstMediaPrimaryFocus = remember { FocusRequester() }
    val clearAllFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }

    val notificationCardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val notificationDismissRequesters = remember { mutableMapOf<String, FocusRequester>() }

    // Prune stale focus requesters when notifications are dismissed
    val activeNotificationKeys = remember(uiState.notifications) {
        uiState.notifications.map { it.key }.toSet()
    }
    LaunchedEffect(activeNotificationKeys) {
        notificationCardRequesters.keys.retainAll(activeNotificationKeys)
        notificationDismissRequesters.keys.retainAll(activeNotificationKeys)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefreshAccessStatus()
    }

    val hasPlayingExternalMedia = remember(uiState.externalMediaSessions) {
        uiState.externalMediaSessions.any { it.isPlaying }
    }
    LaunchedEffect(hasPlayingExternalMedia) {
        if (hasPlayingExternalMedia) {
            while (true) {
                nowElapsedMs = SystemClock.elapsedRealtime()
                delay(1_000L)
            }
        } else {
            nowElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    // Initial focus assignment on drawer open
    LaunchedEffect(Unit) {
        if (!hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            delay(60)
            val firstNotificationRequester = uiState.notifications.firstOrNull()?.let { item ->
                notificationCardRequesters.getOrPut(item.key) { FocusRequester() }
            }
            val preferred = when {
                openReason == NotificationDrawerOpenReason.MEDIA_PILL && uiState.externalMediaSessions.isNotEmpty() ->
                    firstMediaPrimaryFocus
                openReason == NotificationDrawerOpenReason.MEDIA_PILL && internalNowPlaying != null ->
                    internalNowPlayingFocus
                !uiState.isAccessGranted ->
                    permissionSettingsFocus
                internalNowPlaying != null ->
                    internalNowPlayingFocus
                uiState.externalMediaSessions.isNotEmpty() ->
                    firstMediaPrimaryFocus
                firstNotificationRequester != null ->
                    firstNotificationRequester
                else ->
                    closeFocus
            }
            if (!preferred.safeRequestFocusWithRetry("NotificationMediaDrawer_Entry")) {
                closeFocus.safeRequestFocusWithRetry("NotificationMediaDrawer_CloseFallback")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(360.dp)
                    .fillMaxHeight()
                    .background(colors.surface1)
                    .testTag(NOTIFICATION_DRAWER_TEST_TAG)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                    .focusGroup(),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.borderSubtle),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "通知・メディア",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }

                        // Scrollable content
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag(NOTIFICATION_DRAWER_LIST_TEST_TAG),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // 1. Permission missing card
                            if (!uiState.isAccessGranted) {
                                item(key = "permission_card") {
                                    val settingsUnavailable =
                                        uiState.accessStatus == NotificationAccessStatus.SETTINGS_UNAVAILABLE ||
                                            settingsOpenResult == NotificationSettingsOpenResult.UNAVAILABLE
                                    PermissionMissingCard(
                                        settingsUnavailable = settingsUnavailable,
                                        adbGrantCommand = uiState.adbGrantCommand,
                                        settingsButtonFocusRequester = permissionSettingsFocus,
                                        onOpenSettings = {
                                            settingsOpenResult = onOpenNotificationSettings()
                                        },
                                        onRecheck = onRefreshAccessStatus,
                                    )
                                }
                            }

                            // 2. Now Playing section (Komorebi own playback + External Media sessions)
                            val hasAnyMedia = internalNowPlaying != null || uiState.externalMediaSessions.isNotEmpty()
                            if (hasAnyMedia) {
                                item(key = "media_section_header") {
                                    NotificationDrawerSectionLabel("再生中")
                                }
                            }

                            if (internalNowPlaying != null) {
                                item(key = "internal_now_playing") {
                                    InternalNowPlayingCard(
                                        state = internalNowPlaying,
                                        focusRequester = internalNowPlayingFocus,
                                        onClick = {
                                            onReturnToInternalPlayer()
                                            onDismiss()
                                        },
                                    )
                                }
                            }

                            itemsIndexed(
                                items = uiState.externalMediaSessions,
                                key = { _, session -> "media_${session.sessionKey}" },
                            ) { index, session ->
                                ExternalMediaSessionCard(
                                    session = session,
                                    nowElapsedMs = nowElapsedMs,
                                    primaryActionFocusRequester = if (index == 0) firstMediaPrimaryFocus else null,
                                    onSendMediaAction = { action -> onSendMediaAction(session, action) },
                                    onOpenApp = { onOpenMediaApp(session) },
                                )
                            }

                            // 3. Notifications section
                            item(key = "notifications_section_header") {
                                val headerText = if (uiState.notificationCount > 0) {
                                    "通知 (${uiState.notificationCount})"
                                } else {
                                    "通知"
                                }
                                NotificationDrawerSectionLabel(headerText)
                            }

                            if (uiState.notifications.isEmpty()) {
                                item(key = "notifications_empty") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 14.dp)
                                            .testTag(EMPTY_NOTIFICATIONS_TEST_TAG),
                                    ) {
                                        Text(
                                            text = "新しい通知はありません",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.textSecondary,
                                        )
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = uiState.notifications,
                                    key = { _, item -> "notif_${item.key}" },
                                ) { index, item ->
                                    val cardRequester = notificationCardRequesters.getOrPut(item.key) { FocusRequester() }
                                    val dismissRequester = notificationDismissRequesters.getOrPut(item.key) { FocusRequester() }

                                    NotificationRowItem(
                                        item = item,
                                        timeFormat = timeFormat,
                                        cardFocusRequester = cardRequester,
                                        dismissFocusRequester = dismissRequester,
                                        onOpen = { onOpenNotification(item) },
                                        onDismiss = {
                                            val nextItem = uiState.notifications.getOrNull(index + 1)
                                            val prevItem = uiState.notifications.getOrNull(index - 1)
                                            val remainingClearable = (uiState.clearableCount - 1).coerceAtLeast(0)
                                            val fallbackTarget = when {
                                                nextItem != null ->
                                                    notificationCardRequesters.getOrPut(nextItem.key) { FocusRequester() }
                                                prevItem != null ->
                                                    notificationCardRequesters.getOrPut(prevItem.key) { FocusRequester() }
                                                remainingClearable > 0 ->
                                                    clearAllFocus
                                                uiState.externalMediaSessions.isNotEmpty() ->
                                                    firstMediaPrimaryFocus
                                                internalNowPlaying != null ->
                                                    internalNowPlayingFocus
                                                else ->
                                                    closeFocus
                                            }
                                            onDismissNotification(item.key)
                                            scope.launch {
                                                withFrameNanos { }
                                                if (!fallbackTarget.safeRequestFocus("NotificationDismiss_Handoff")) {
                                                    closeFocus.safeRequestFocus("NotificationDismiss_CloseFallback")
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        // Footer divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.borderSubtle),
                        )

                        // Fixed Footer anchor
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (uiState.clearableCount > 0) {
                                NotificationDrawerFooterButton(
                                    label = "すべて消去",
                                    icon = Icons.Default.ClearAll,
                                    onClick = {
                                        onClearAllNotifications()
                                        scope.launch {
                                            withFrameNanos { }
                                            closeFocus.safeRequestFocus("NotificationClearAll_Close")
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag(FOOTER_CLEAR_ALL_TEST_TAG)
                                        .focusRequester(clearAllFocus),
                                )
                            }
                            NotificationDrawerFooterButton(
                                label = "閉じる",
                                icon = Icons.Default.Close,
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag(FOOTER_CLOSE_TEST_TAG)
                                    .focusRequester(closeFocus),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationDrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = KomorebiTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun PermissionMissingCard(
    settingsUnavailable: Boolean,
    adbGrantCommand: String,
    settingsButtonFocusRequester: FocusRequester,
    onOpenSettings: () -> Unit,
    onRecheck: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, cardShape)
            .padding(12.dp)
            .testTag(PERMISSION_CARD_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Text(
                text = "通知・外部メディア連携が未許可です（$ERROR_CODE_NOTIFICATION_ACCESS_REQUIRED）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
        }

        Text(
            text = "Android TV の通知アクセスを許可すると、他アプリで再生中のメディア操作やシステム通知をここで表示できます。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        NotificationDrawerActionButton(
            label = "通知アクセス設定を開く",
            icon = Icons.Default.Settings,
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PERMISSION_SETTINGS_BUTTON_TEST_TAG)
                .focusRequester(settingsButtonFocusRequester),
        )

        if (settingsUnavailable) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "この端末では通知アクセス設定画面を開けませんでした（$ERROR_CODE_NOTIFICATION_SETTINGS_UNAVAILABLE）。PC から ADB で許可してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                if (adbGrantCommand.isNotBlank()) {
                    Text(
                        text = adbGrantCommand,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface1, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                    )
                }
                NotificationDrawerActionButton(
                    label = "再確認",
                    icon = Icons.Default.Refresh,
                    onClick = onRecheck,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun InternalNowPlayingCard(
    state: InternalNowPlayingState,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .testTag(INTERNAL_NOW_PLAYING_CARD_TEST_TAG)
            .focusRequester(focusRequester),
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface2,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.accentSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(colors.accent, CircleShape),
                    )
                    Text(
                        text = "${state.badgeLabel} · Honorebi",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = state.title.ifBlank { state.badgeLabel },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.subtitle.isNotBlank()) {
                    Text(
                        text = state.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "プレイヤーへ戻る",
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ExternalMediaSessionCard(
    session: ExternalMediaSessionState,
    nowElapsedMs: Long,
    primaryActionFocusRequester: FocusRequester?,
    onSendMediaAction: (MediaTransportAction) -> Unit,
    onOpenApp: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val cardShape = RoundedCornerShape(12.dp)
    val playPauseEnabled = if (session.isPlaying) session.canPause else session.canPlay
    val statusText = when {
        session.isPlaying -> "再生中"
        session.isBuffering -> "読み込み中"
        else -> "一時停止"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface2, cardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Artwork + Metadata
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface1),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    session.artwork != null -> {
                        Image(
                            bitmap = session.artwork.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    !session.artworkUri.isNullOrBlank() -> {
                        AsyncImage(
                            model = session.artworkUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${session.appLabel} · $statusText",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.isPlaying) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = session.title.ifBlank { session.appLabel },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.subtitle.isNotBlank()) {
                    Text(
                        text = session.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Progress bar when duration > 0
        if (session.durationMs > 0L) {
            val livePositionMs = session.computeLivePositionMs(nowElapsedMs)
            val progressFraction = (livePositionMs.toFloat() / session.durationMs.toFloat()).coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.borderSubtle),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .background(colors.accent, RoundedCornerShape(2.dp)),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatMediaDuration(livePositionMs),
                        style = MaterialTheme.typography.labelSmall.tnum,
                        color = colors.textMuted,
                    )
                    Text(
                        text = formatMediaDuration(session.durationMs),
                        style = MaterialTheme.typography.labelSmall.tnum,
                        color = colors.textMuted,
                    )
                }
            }
        }

        // Fixed 4-slot transport row: [Prev][Play/Pause][Next][Open]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaTransportButton(
                icon = Icons.Default.SkipPrevious,
                contentDescription = "前へ",
                enabled = session.canSkipToPrevious,
                onClick = { onSendMediaAction(MediaTransportAction.SKIP_TO_PREVIOUS) },
                modifier = Modifier.weight(1f),
            )
            MediaTransportButton(
                icon = if (session.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (session.isPlaying) "一時停止" else "再生",
                enabled = playPauseEnabled,
                isPrimary = true,
                onClick = {
                    val action = if (session.isPlaying) MediaTransportAction.PAUSE else MediaTransportAction.PLAY
                    onSendMediaAction(action)
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(externalMediaPlayPauseTestTag(session.sessionKey))
                    .then(
                        if (primaryActionFocusRequester != null && playPauseEnabled) {
                            Modifier.focusRequester(primaryActionFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
            MediaTransportButton(
                icon = Icons.Default.SkipNext,
                contentDescription = "次へ",
                enabled = session.canSkipToNext,
                onClick = { onSendMediaAction(MediaTransportAction.SKIP_TO_NEXT) },
                modifier = Modifier.weight(1f),
            )
            MediaTransportButton(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "アプリを開く",
                enabled = true,
                onClick = onOpenApp,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (primaryActionFocusRequester != null && !playPauseEnabled) {
                            Modifier.focusRequester(primaryActionFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

@Composable
private fun MediaTransportButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(40.dp)
            .focusProperties { canFocus = enabled },
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary && enabled) colors.accentSoft else colors.surface1,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            disabledContainerColor = colors.surface1.copy(alpha = 0.45f),
            contentColor = if (isPrimary && enabled) colors.accent else colors.textPrimary,
            focusedContentColor = colors.accent,
            pressedContentColor = colors.accent,
            disabledContentColor = colors.textMuted.copy(alpha = 0.45f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            disabledBorder = Border(BorderStroke(1.dp, colors.borderSubtle.copy(alpha = 0.4f)), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> colors.textMuted.copy(alpha = 0.45f)
                    isFocused || isPrimary -> colors.accent
                    else -> colors.textPrimary
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun NotificationRowItem(
    item: SystemNotificationItem,
    timeFormat: String,
    cardFocusRequester: FocusRequester,
    dismissFocusRequester: FocusRequester,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val cardInteraction = remember { MutableInteractionSource() }
    val isCardFocused by cardInteraction.collectIsFocusedAsState()
    val dismissInteraction = remember { MutableInteractionSource() }
    val isDismissFocused by dismissInteraction.collectIsFocusedAsState()
    val cardShape = RoundedCornerShape(12.dp)
    val dismissShape = RoundedCornerShape(10.dp)
    val formattedTime = remember(item.postTimeMs, timeFormat) {
        formatNotificationTimestamp(item.postTimeMs, timeFormat)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onOpen,
            modifier = Modifier
                .weight(1f)
                .testTag(notificationRowCardTestTag(item.key))
                .focusRequester(cardFocusRequester)
                .focusProperties {
                    left = FocusRequester.Cancel
                    right = if (item.isClearable) dismissFocusRequester else FocusRequester.Cancel
                },
            interactionSource = cardInteraction,
            shape = ClickableSurfaceDefaults.shape(cardShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = colors.surface2,
                focusedContainerColor = colors.accentSoft,
                pressedContainerColor = colors.accentSoft,
                contentColor = colors.textPrimary,
                focusedContentColor = colors.textPrimary,
                pressedContentColor = colors.textPrimary,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = cardShape),
                focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = cardShape),
                pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = cardShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface1),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.appIcon != null) {
                        Image(
                            bitmap = item.appIcon.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (isCardFocused) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${item.appLabel} · $formattedTime",
                        style = MaterialTheme.typography.labelSmall.tnum,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.title.isNotBlank()) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isCardFocused) FontWeight.Bold else FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (item.isClearable) {
            Surface(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .testTag(notificationRowDismissTestTag(item.key))
                    .focusRequester(dismissFocusRequester)
                    .focusProperties {
                        left = cardFocusRequester
                        right = FocusRequester.Cancel
                    },
                interactionSource = dismissInteraction,
                shape = ClickableSurfaceDefaults.shape(dismissShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.surface2,
                    focusedContainerColor = colors.danger.copy(alpha = 0.20f),
                    pressedContainerColor = colors.danger.copy(alpha = 0.20f),
                    contentColor = colors.textSecondary,
                    focusedContentColor = colors.danger,
                    pressedContentColor = colors.danger,
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = dismissShape),
                    focusedBorder = Border(BorderStroke(2.dp, colors.danger), shape = dismissShape),
                    pressedBorder = Border(BorderStroke(2.dp, colors.danger), shape = dismissShape),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "消去",
                        tint = if (isDismissFocused) colors.danger else colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationDrawerActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationDrawerFooterButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier.padding(2.dp),
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface2,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            contentColor = colors.textSecondary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
internal fun formatNotificationTimestamp(postTimeMs: Long, timeFormat: String): String {
    if (postTimeMs <= 0L) return ""
    val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
    return runCatching {
        Instant.ofEpochMilli(postTimeMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))
    }.getOrDefault("")
}

internal fun formatMediaDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
