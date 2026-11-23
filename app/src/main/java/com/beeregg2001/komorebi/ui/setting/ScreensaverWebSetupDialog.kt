package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.setup.ConnectionSetupUiState
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.components.DialogQrCard
import com.beeregg2001.komorebi.ui.components.DialogUrlBox
import com.beeregg2001.komorebi.ui.components.ThemedDialogSurface
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@Composable
fun ScreensaverWebSetupDialog(
    serverIp: String,
    state: ConnectionSetupUiState,
    savedImageCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val closeR = remember { FocusRequester() }
    DisposableEffect(Unit) { onStart(); onDispose { onStop() } }
    LaunchedEffect(Unit) {
        delay(120)
        closeR.safeRequestFocusWithRetry("ScreensaverWebSetup_Close")
    }
    val url = state.session?.let { "http://$serverIp:8081/screensaver/#${it.token}" }
    val bitmap = url?.let { rememberQrBitmap(it, 300, margin = 1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = true,
        ),
    ) {
        ThemedDialogSurface(width = 680.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = "スマホから画像を管理",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "同じネットワークの端末でQRコードを読み取るか、URLを開いてスクリーンセーバー画像を追加・削除できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )

                        DialogUrlBox(
                            url = url ?: "管理ページを準備しています…",
                            label = "管理URL",
                        )

                        Text(
                            text = "現在の登録画像: ${savedImageCount} 枚（スマホで追加・削除すると自動で反映されます）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary,
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.error != null) {
                                Text(
                                    text = state.error,
                                    color = colors.danger,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        if (state.error != null) {
                            DialogActionButton(DialogActionRole.Secondary, onStart) {
                                Text("再試行")
                            }
                        }
                    }

                    DialogQrCard(
                        bitmap = bitmap,
                        contentDescription = "スクリーンセーバー画像管理のQRコード",
                        size = 212.dp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(closeR),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}
