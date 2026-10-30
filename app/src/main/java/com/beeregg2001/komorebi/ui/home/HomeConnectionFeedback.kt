@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.components.ConnectionFailureReason
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

internal const val HOME_CONNECTION_FEEDBACK_SLOT_TAG = "home-connection-feedback-slot"
internal const val HOME_CONNECTION_FEEDBACK_SLOT_HEIGHT_DP = 72

internal enum class HomeConnectionFeedbackKind {
    Hidden,
    DeviceOffline,
    BackendFailure,
    Recovering,
}

internal data class HomeConnectionFeedbackState(
    val kind: HomeConnectionFeedbackKind,
    val reason: String? = null,
)

/**
 * Only an explicit recovery request may produce [HomeConnectionFeedbackKind.Recovering].
 * Ordinary channel polling must pass false even while its request is in flight.
 */
internal fun homeConnectionFeedbackState(
    networkAvailable: Boolean,
    hasBackendFailure: Boolean,
    backendFailureReason: String?,
    recoveryInProgress: Boolean,
): HomeConnectionFeedbackState = when {
    !networkAvailable -> HomeConnectionFeedbackState(HomeConnectionFeedbackKind.DeviceOffline)
    recoveryInProgress -> HomeConnectionFeedbackState(
        HomeConnectionFeedbackKind.Recovering,
        backendFailureReason?.takeIf { it.isNotBlank() },
    )
    hasBackendFailure -> HomeConnectionFeedbackState(
        HomeConnectionFeedbackKind.BackendFailure,
        backendFailureReason?.takeIf { it.isNotBlank() }
            ?: "サーバーから最新の情報を取得できませんでした。",
    )
    else -> HomeConnectionFeedbackState(HomeConnectionFeedbackKind.Hidden)
}

/**
 * Permanent overlay slot: connection changes never reflow retained home content.
 * Purely informational — none of it takes focus or accepts clicks.
 */
@Composable
internal fun HomeConnectionFeedback(
    state: HomeConnectionFeedbackState,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HOME_CONNECTION_FEEDBACK_SLOT_HEIGHT_DP.dp)
            .testTag(HOME_CONNECTION_FEEDBACK_SLOT_TAG),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (state.kind == HomeConnectionFeedbackKind.Hidden) return@Box

        val presentation = connectionFeedbackPresentation(state)
        Row(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .height(HOME_CONNECTION_FEEDBACK_SLOT_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background.copy(alpha = 0.96f))
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = presentation.title,
                    color = colors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(3.dp))
                ConnectionFailureReason(
                    reason = state.reason ?: presentation.detail,
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                )
            }
        }
    }
}

private data class HomeConnectionFeedbackPresentation(
    val icon: ImageVector,
    val title: String,
    val detail: String,
)

private fun connectionFeedbackPresentation(
    state: HomeConnectionFeedbackState,
): HomeConnectionFeedbackPresentation = when (state.kind) {
    HomeConnectionFeedbackKind.DeviceOffline -> HomeConnectionFeedbackPresentation(
        icon = Icons.Default.WifiOff,
        title = "ネットワークに接続されていません",
        detail = "キャッシュ済みの情報を表示しています。接続を確認してください。",
    )
    HomeConnectionFeedbackKind.BackendFailure -> HomeConnectionFeedbackPresentation(
        icon = Icons.Default.CloudOff,
        title = "サーバーに接続できません",
        detail = "接続設定を確認してください。接続が戻ると自動で更新します。",
    )
    HomeConnectionFeedbackKind.Recovering -> HomeConnectionFeedbackPresentation(
        icon = Icons.Default.CloudOff,
        title = "サーバーへの接続を確認しています…",
        detail = "キャッシュ済みの情報を表示したまま、自動で更新します。",
    )
    HomeConnectionFeedbackKind.Hidden -> error("Hidden feedback has no presentation")
}
