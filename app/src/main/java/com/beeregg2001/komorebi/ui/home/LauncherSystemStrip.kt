@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.home

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.LauncherApp
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
private val RETURN_PLAYER_SLOT_WIDTH = 112.dp

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
    app: LauncherApp,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    canTakeFocus: Boolean,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
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
            containerColor = Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.SettingsInputHdmi,
                contentDescription = app.label,
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
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)
    val (icon, description) = when (status.transport) {
        NetworkTransport.WIFI -> Icons.Default.Wifi to "Wi-Fi 設定"
        NetworkTransport.ETHERNET -> Icons.Default.SettingsEthernet to "有線ネットワーク設定"
        NetworkTransport.OTHER -> Icons.Default.Wifi to "ネットワーク設定"
        NetworkTransport.DISCONNECTED -> Icons.Default.WifiOff to "オフライン。Wi-Fi 設定"
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
            containerColor = if (status.isAvailable) Color.Transparent else colors.accentSoft,
            focusedContainerColor = colors.surface2,
            contentColor = if (status.isAvailable) colors.textSecondary else colors.accent,
            focusedContentColor = if (status.isAvailable) colors.textPrimary else colors.accent,
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

/**
 * Top-right system utility strip for the launcher:
 * `[再生中 112dp slot][入力切替 if present][Network][本体設定][Clock]`.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun LauncherSystemStrip(
    timeFormat: String,
    hasActivePlayer: Boolean,
    inputSourceApp: LauncherApp?,
    networkConnectionStatus: NetworkConnectionStatus,
    canTakeFocus: Boolean,
    returnPlayerFocusRequester: FocusRequester,
    inputSourceFocusRequester: FocusRequester,
    wifiSettingsFocusRequester: FocusRequester,
    systemSettingsFocusRequester: FocusRequester,
    leftEdgeFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onReturnToPlayerClick: () -> Unit,
    onLaunchInputSourcePicker: (LauncherApp) -> Unit,
    onLaunchWifiSettings: () -> Unit,
    onLaunchSystemSettings: () -> Unit,
    onHasFocusChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    clockFocusRequester: FocusRequester = remember { FocusRequester() },
    onClockClick: () -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    val focusSpec = tvCardFocus(TvCardFamily.SYSTEM_ICON)
    val stripShape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .background(colors.overlay, stripShape)
            .border(1.dp, colors.borderSubtle, stripShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onFocusChanged { onHasFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Back || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasActivePlayer) {
            val pillShape = RoundedCornerShape(20.dp)
            Surface(
                onClick = onReturnToPlayerClick,
                modifier = Modifier
                    .height(40.dp)
                    .focusRequester(returnPlayerFocusRequester)
                    .focusProperties {
                        left = leftEdgeFocusRequester
                        right = if (inputSourceApp != null) {
                            inputSourceFocusRequester
                        } else {
                            wifiSettingsFocusRequester
                        }
                        down = downFocusRequester
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
                        contentDescription = "再生中",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "再生中",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        if (inputSourceApp != null) {
            PinnedSystemAppButton(
                app = inputSourceApp,
                focusRequester = inputSourceFocusRequester,
                leftFocusRequester = if (hasActivePlayer) {
                    returnPlayerFocusRequester
                } else {
                    leftEdgeFocusRequester
                },
                rightFocusRequester = wifiSettingsFocusRequester,
                downFocusRequester = downFocusRequester,
                canTakeFocus = canTakeFocus,
                onClick = { onLaunchInputSourcePicker(inputSourceApp) },
            )
        }

        NetworkStatusButton(
            status = networkConnectionStatus,
            focusRequester = wifiSettingsFocusRequester,
            leftFocusRequester = when {
                inputSourceApp != null -> inputSourceFocusRequester
                hasActivePlayer -> returnPlayerFocusRequester
                else -> leftEdgeFocusRequester
            },
            rightFocusRequester = systemSettingsFocusRequester,
            canTakeFocus = canTakeFocus,
            onClick = onLaunchWifiSettings,
            downFocusRequester = downFocusRequester,
        )

        Surface(
            onClick = onLaunchSystemSettings,
            modifier = Modifier
                .size(48.dp)
                .focusRequester(systemSettingsFocusRequester)
                .focusProperties {
                    left = wifiSettingsFocusRequester
                    right = clockFocusRequester
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

        Surface(
            onClick = onClockClick,
            modifier = Modifier
                .height(48.dp)
                .focusRequester(clockFocusRequester)
                .focusProperties {
                    left = systemSettingsFocusRequester
                    right = FocusRequester.Cancel
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
