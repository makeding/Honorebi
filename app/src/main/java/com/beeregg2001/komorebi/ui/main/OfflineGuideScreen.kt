@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.components.ConnectionFailureDetailsDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@Composable
fun OfflineGuideScreen(
    hasOfflineCache: Boolean,
    isDeviceOffline: Boolean,
    onContinueOffline: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    isRetrying: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val primaryFocusRequester = remember { FocusRequester() }
    val reasonFocusRequester = remember { FocusRequester() }
    var showReasonDetails by remember { mutableStateOf(false) }
    var hasOpenedReasonDetails by remember { mutableStateOf(false) }

    LaunchedEffect(hasOfflineCache) {
        delay(250)
        primaryFocusRequester.safeRequestFocus("OfflineGuide")
    }

    LaunchedEffect(showReasonDetails) {
        if (!showReasonDetails && hasOpenedReasonDetails) {
            reasonFocusRequester.safeRequestFocus("OfflineGuideReasonReturn")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (colors.isDark) 0.26f else 0.08f))
            .padding(horizontal = 72.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.width(620.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(colors.surface.copy(alpha = 0.72f), CircleShape)
                    .border(1.dp, colors.textPrimary.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = colors.textPrimary.copy(alpha = 0.92f),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isDeviceOffline) "ネットワークに接続されていません" else "サーバーに接続できません",
                color = colors.textPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (hasOfflineCache) {
                    "接続を確認して再試行するか、保存済みの履歴とキャッシュでホーム画面を開けます。"
                } else {
                    "Wi-Fi または有線 LAN の接続と、配信サーバーの設定を確認してください。"
                },
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            // The preview and its detail affordance always own the same space, so a changing
            // error cannot move the actions. The full safe reason remains available by remote.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(top = 4.dp)
                    .testTag("offline-guide-reason"),
                contentAlignment = Alignment.Center
            ) {
                if (!reason.isNullOrBlank()) {
                    Button(
                        onClick = {
                            hasOpenedReasonDetails = true
                            showReasonDetails = true
                        },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.colors(
                            containerColor = colors.textPrimary.copy(alpha = 0.08f),
                            contentColor = colors.textSecondary,
                            focusedContainerColor = colors.textPrimary.copy(alpha = 0.18f),
                            focusedContentColor = colors.textPrimary,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .focusRequester(reasonFocusRequester)
                            .testTag("offline-guide-reason-open"),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("詳細を表示", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(360.dp)
            ) {
                if (hasOfflineCache) {
                    OfflineGuideButton(
                        text = "キャッシュで続行",
                        icon = Icons.Default.Storage,
                        isPrimary = true,
                        modifier = Modifier.focusRequester(primaryFocusRequester),
                        onClick = onContinueOffline
                    )
                }

                OfflineGuideButton(
                    text = when {
                        isDeviceOffline -> "ネットワーク接続待ち"
                        isRetrying -> "接続中…"
                        else -> "再試行"
                    },
                    icon = Icons.Default.Refresh,
                    isPrimary = !hasOfflineCache,
                    pending = isRetrying || isDeviceOffline,
                    pendingStateDescription = if (isDeviceOffline) "ネットワーク接続待ち" else "接続中",
                    modifier = if (!hasOfflineCache) Modifier.focusRequester(primaryFocusRequester) else Modifier,
                    onClick = onRetry
                )

                OfflineGuideButton(
                    text = "接続設定を開く",
                    icon = Icons.Default.Settings,
                    isPrimary = false,
                    onClick = onOpenSettings
                )
            }
        }
    }

    if (showReasonDetails && !reason.isNullOrBlank()) {
        ConnectionFailureDetailsDialog(
            reason = reason,
            onDismiss = { showReasonDetails = false },
            detailTestTag = "offline-guide-reason-details",
            closeTestTag = "offline-guide-reason-close",
        )
    }
}

@Composable
private fun OfflineGuideButton(
    text: String,
    icon: ImageVector,
    isPrimary: Boolean,
    pending: Boolean = false,
    pendingStateDescription: String = "接続中",
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    Button(
        onClick = { if (!pending) onClick() },
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (isPrimary) colors.accent else colors.textPrimary.copy(alpha = 0.12f),
            contentColor = if (isPrimary && colors.isDark) Color.Black else colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        modifier = modifier
            .width(360.dp)
            .semantics {
                stateDescription = if (pending) pendingStateDescription else "実行できます"
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = text, fontWeight = FontWeight.SemiBold)
        }
    }
}
