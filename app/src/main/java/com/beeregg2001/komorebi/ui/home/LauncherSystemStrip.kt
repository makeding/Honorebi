@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.notification.LauncherMediaPillState
import com.beeregg2001.komorebi.ui.main.NetworkConnectionStatus
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardFocusScaleSystemIcon
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvTopStripHeight
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val NETWORK_STATUS_BUTTON_SIZE_DP = 48
internal const val NETWORK_STATUS_BUTTON_TEST_TAG = "network-status-button"
internal const val MEDIA_PILL_TEST_TAG = "launcher-media-pill"
internal const val NOTIFICATION_BELL_BUTTON_TEST_TAG = "notification-bell-button"
internal const val NOTIFICATION_BELL_BADGE_TEST_TAG = "notification-bell-badge"
internal const val SYSTEM_SETTINGS_BUTTON_TEST_TAG = "system-settings-button"
internal const val CLOCK_BUTTON_TEST_TAG = "launcher-clock-button"
private val MEDIA_PILL_MAX_WIDTH = 188.dp

internal fun formatNotificationBadgeCount(count: Int): String =
    if (count > 9) "9+" else count.coerceAtLeast(0).toString()

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DigitalClock(timeFormat: String, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(60000)
        }
    }

    val pattern = if (timeFormat == "12H") "a h:mm" else "HH:mm"
    val formattedTime = currentTime.format(DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE))

    Text(
        text = formattedTime,
        color = KomorebiTheme.colors.textPrimary,
        style = MaterialTheme.typography.titleLarge.tnum,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
private fun PinnedSystemAppButton(
    label: String,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    canTakeFocus: Boolean,
    onClick: () -> Unit,
    onFocusedChanged: (Boolean) -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusedChanged(it.isFocused) }
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
                down = downFocusRequester
                up = FocusRequester.Cancel
                canFocus = canTakeFocus
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = RoundedCornerShape(10.dp)),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.SettingsInputHdmi,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun NetworkStatusButton(
    status: NetworkConnectionStatus,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    canTakeFocus: Boolean,
    onClick: () -> Unit,
    downFocusRequester: FocusRequester = FocusRequester.Default,
    connectionFeedbackKind: HomeConnectionFeedbackKind = HomeConnectionFeedbackKind.Hidden,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)
    val hasServerFailure = status.isAvailable && connectionFeedbackKind != HomeConnectionFeedbackKind.Hidden
    val isAlertState = !status.isAvailable || hasServerFailure
    val (icon, description) = when {
        !status.isAvailable -> Icons.Default.WifiOff to "オフライン。Wi-Fi 設定"
        connectionFeedbackKind == HomeConnectionFeedbackKind.Recovering ->
            Icons.Default.CloudOff to "サーバーへの接続を確認しています。Wi-Fi 設定"
        hasServerFailure -> Icons.Default.CloudOff to "サーバーに接続できません。Wi-Fi 設定"
        status.transport == NetworkTransport.WIFI -> Icons.Default.Wifi to "Wi-Fi 設定"
        status.transport == NetworkTransport.ETHERNET -> Icons.Default.SettingsEthernet to "有線ネットワーク設定"
        status.transport == NetworkTransport.OTHER -> Icons.Default.Wifi to "ネットワーク設定"
        else -> Icons.Default.WifiOff to "オフライン。Wi-Fi 設定"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(NETWORK_STATUS_BUTTON_SIZE_DP.dp)
            .testTag(NETWORK_STATUS_BUTTON_TEST_TAG)
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
                down = downFocusRequester
                up = FocusRequester.Cancel
                canFocus = canTakeFocus
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = RoundedCornerShape(10.dp)),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isAlertState) colors.danger.copy(alpha = 0.16f) else Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = if (isAlertState) colors.danger else colors.textSecondary,
            focusedContentColor = if (isAlertState) colors.danger else colors.textPrimary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
internal fun NotificationBellButton(
    notificationCount: Int,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    canTakeFocus: Boolean,
    onClick: () -> Unit,
    onFocusedChanged: (Boolean) -> Unit = {},
    downFocusRequester: FocusRequester = FocusRequester.Cancel,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)
    val hasNotifications = notificationCount > 0
    val icon = if (hasNotifications) Icons.Default.Notifications else Icons.Default.NotificationsNone
    val description = if (hasNotifications) "通知・メディア（${notificationCount}件）" else "通知・メディア"

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(NETWORK_STATUS_BUTTON_SIZE_DP.dp)
            .testTag(NOTIFICATION_BELL_BUTTON_TEST_TAG)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusedChanged(it.isFocused) }
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
                down = downFocusRequester
                up = FocusRequester.Cancel
                canFocus = canTakeFocus
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = RoundedCornerShape(10.dp)),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = if (hasNotifications) colors.accent else colors.textSecondary,
            focusedContentColor = if (hasNotifications) colors.accent else colors.textPrimary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(24.dp),
            )
            if (hasNotifications) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .background(colors.accent, CircleShape)
                        .padding(horizontal = 4.dp)
                        .testTag(NOTIFICATION_BELL_BADGE_TEST_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatNotificationBadgeCount(notificationCount),
                        color = colors.onAccent,
                        style = MaterialTheme.typography.labelSmall.tnum.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Top-right system utility strip for the launcher:
 * `[UnifiedMediaPill?][InputSource?][Settings?][Network][Bell?][Clock]`.
 * When [isCompactStatusOnly] is true and the strip is unfocused, action buttons (HDMI & System Settings)
 * collapse horizontally; Bell collapses only when [notificationCount] == 0.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun LauncherSystemStrip(
    timeFormat: String,
    mediaPillState: LauncherMediaPillState,
    showInputSource: Boolean,
    networkConnectionStatus: NetworkConnectionStatus,
    canTakeFocus: Boolean,
    returnPlayerFocusRequester: FocusRequester,
    inputSourceFocusRequester: FocusRequester,
    wifiSettingsFocusRequester: FocusRequester,
    systemSettingsFocusRequester: FocusRequester,
    leftEdgeFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onMediaPillClick: () -> Unit,
    onLaunchInputSourcePicker: () -> Unit,
    onLaunchWifiSettings: () -> Unit,
    onLaunchSystemSettings: () -> Unit,
    onHasFocusChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    notificationCount: Int = 0,
    bellFocusRequester: FocusRequester = remember { FocusRequester() },
    onBellClick: () -> Unit = {},
    clockFocusRequester: FocusRequester = remember { FocusRequester() },
    onClockClick: () -> Unit = {},
    isContentReady: Boolean = true,
    isCompactStatusOnly: Boolean = false,
    connectionFeedbackKind: HomeConnectionFeedbackKind = HomeConnectionFeedbackKind.Hidden,
) {
    val colors = KomorebiTheme.colors
    val focusManager = LocalFocusManager.current
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)
    val stripShape = RoundedCornerShape(14.dp)

    var stripFocused by remember { mutableStateOf(false) }
    var mediaPillFocused by remember { mutableStateOf(false) }
    var inputSourceFocused by remember { mutableStateOf(false) }
    var bellFocused by remember { mutableStateOf(false) }
    val hasMediaPill = mediaPillState !is LauncherMediaPillState.None
    val showActionButtons = !isCompactStatusOnly || stripFocused
    val showBellButton = showActionButtons || notificationCount > 0

    LaunchedEffect(hasMediaPill, showInputSource, showActionButtons, showBellButton) {
        if (!hasMediaPill && mediaPillFocused) {
            mediaPillFocused = false
            val target = when {
                showActionButtons && showInputSource -> inputSourceFocusRequester
                showActionButtons -> systemSettingsFocusRequester
                else -> wifiSettingsFocusRequester
            }
            target.safeRequestFocus("StripHandoff_MediaPill")
        } else if (!showInputSource && inputSourceFocused) {
            inputSourceFocused = false
            val target = if (showActionButtons) systemSettingsFocusRequester else wifiSettingsFocusRequester
            target.safeRequestFocus("StripHandoff_Input")
        } else if (!showBellButton && bellFocused) {
            bellFocused = false
            wifiSettingsFocusRequester.safeRequestFocus("StripHandoff_Bell")
        }
    }

    Row(
        modifier = modifier
            .background(colors.background.copy(alpha = 0.60f), stripShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged {
                stripFocused = it.hasFocus
                onHasFocusChanged(it.hasFocus)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onBack()
                        true
                    }
                    Key.DirectionDown -> {
                        if (isContentReady) {
                            if (!downFocusRequester.safeRequestFocus("StripDown")) {
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        }
                        true
                    }
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasMediaPill) {
            val pillLabel = when (mediaPillState) {
                is LauncherMediaPillState.InternalMiniPlayer -> mediaPillState.label
                is LauncherMediaPillState.ExternalMedia -> mediaPillState.displayLabel
                LauncherMediaPillState.None -> ""
            }
            val pillShape = RoundedCornerShape(20.dp)
            Surface(
                onClick = onMediaPillClick,
                modifier = Modifier
                    .height(40.dp)
                    .widthIn(max = MEDIA_PILL_MAX_WIDTH)
                    .testTag(MEDIA_PILL_TEST_TAG)
                    .focusRequester(returnPlayerFocusRequester)
                    .onFocusChanged { mediaPillFocused = it.isFocused }
                    .focusProperties {
                        left = leftEdgeFocusRequester
                        right = when {
                            showActionButtons && showInputSource -> inputSourceFocusRequester
                            showActionButtons -> systemSettingsFocusRequester
                            else -> wifiSettingsFocusRequester
                        }
                        down = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        canFocus = canTakeFocus
                    },
                shape = ClickableSurfaceDefaults.shape(pillShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = TvCardFocusScaleSystemIcon),
                border = ClickableSurfaceDefaults.border(
                    border = Border(
                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
                        shape = pillShape,
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(TvCardFocusBorderWidth, colors.accent),
                        shape = pillShape,
                    ),
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.accentSoft,
                    focusedContainerColor = colors.surface2,
                    contentColor = colors.accent,
                    focusedContentColor = colors.textPrimary,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = pillLabel,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = pillLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showActionButtons && showInputSource,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            if (showInputSource) {
                PinnedSystemAppButton(
                    label = "TV入力",
                    focusRequester = inputSourceFocusRequester,
                    leftFocusRequester = if (hasMediaPill) {
                        returnPlayerFocusRequester
                    } else {
                        leftEdgeFocusRequester
                    },
                    rightFocusRequester = systemSettingsFocusRequester,
                    downFocusRequester = FocusRequester.Cancel,
                    canTakeFocus = canTakeFocus,
                    onClick = onLaunchInputSourcePicker,
                    onFocusedChanged = { inputSourceFocused = it },
                )
            }
        }

        AnimatedVisibility(
            visible = showActionButtons,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Surface(
                onClick = onLaunchSystemSettings,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(SYSTEM_SETTINGS_BUTTON_TEST_TAG)
                    .focusRequester(systemSettingsFocusRequester)
                    .focusProperties {
                        left = when {
                            showInputSource -> inputSourceFocusRequester
                            hasMediaPill -> returnPlayerFocusRequester
                            else -> leftEdgeFocusRequester
                        }
                        right = wifiSettingsFocusRequester
                        down = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        canFocus = canTakeFocus
                    },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
                border = ClickableSurfaceDefaults.border(
                    border = Border.None,
                    focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = RoundedCornerShape(10.dp)),
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = colors.surface2,
                    contentColor = colors.textSecondary,
                    focusedContentColor = colors.textPrimary,
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "システム設定",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        NetworkStatusButton(
            status = networkConnectionStatus,
            focusRequester = wifiSettingsFocusRequester,
            leftFocusRequester = when {
                showActionButtons -> systemSettingsFocusRequester
                hasMediaPill -> returnPlayerFocusRequester
                else -> leftEdgeFocusRequester
            },
            rightFocusRequester = if (showBellButton) bellFocusRequester else clockFocusRequester,
            canTakeFocus = canTakeFocus,
            onClick = onLaunchWifiSettings,
            downFocusRequester = FocusRequester.Cancel,
            connectionFeedbackKind = connectionFeedbackKind,
        )

        AnimatedVisibility(
            visible = showBellButton,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            NotificationBellButton(
                notificationCount = notificationCount,
                focusRequester = bellFocusRequester,
                leftFocusRequester = wifiSettingsFocusRequester,
                rightFocusRequester = clockFocusRequester,
                canTakeFocus = canTakeFocus,
                onClick = onBellClick,
                onFocusedChanged = { bellFocused = it },
                downFocusRequester = FocusRequester.Cancel,
            )
        }

        Surface(
            onClick = onClockClick,
            modifier = Modifier
                .height(48.dp)
                .testTag(CLOCK_BUTTON_TEST_TAG)
                .focusRequester(clockFocusRequester)
                .focusProperties {
                    left = if (showBellButton) bellFocusRequester else wifiSettingsFocusRequester
                    right = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    canFocus = canTakeFocus
                },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
            border = ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = RoundedCornerShape(10.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = colors.surface2,
                contentColor = colors.textPrimary,
                focusedContentColor = colors.textPrimary,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                DigitalClock(timeFormat = timeFormat)
            }
        }
    }
}
