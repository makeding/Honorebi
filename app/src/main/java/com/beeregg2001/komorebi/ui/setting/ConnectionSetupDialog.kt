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
fun ConnectionSetupDialog(
    title: String,
    serverIp: String,
    state: ConnectionSetupUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    onManualInput: () -> Unit,
    initialSetup: Boolean = false,
    canFinishInitialSetup: Boolean = false,
    onSelectTvInput: (() -> Unit)? = null,
) {
    val colors = KomorebiTheme.colors
    val manualR = remember { FocusRequester() }
    val closeR = remember { FocusRequester() }
    DisposableEffect(Unit) { onStart(); onDispose { onStop() } }
    LaunchedEffect(initialSetup) {
        delay(120)
        if (initialSetup) manualR.safeRequestFocusWithRetry("ConnectionSetup_Initial")
        else closeR.safeRequestFocusWithRetry("ConnectionSetup_Close")
    }
    val session = state.session
    val url = session?.let {
        "http://$serverIp:8081/connections/" +
            (it.source?.let { source -> "?source=$source" } ?: "") + "#${it.token}"
    }
    val bitmap = url?.let { rememberQrBitmap(it, 300, margin = 1) }

    Dialog(
        onDismissRequest = if (initialSetup && !canFinishInitialSetup) ({}) else onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = !initialSetup,
        ),
    ) {
        ThemedDialogSurface(width = 680.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = title,
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
                            text = "同じネットワークの端末で読み取るか、URLを開いて設定",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )

                        DialogUrlBox(
                            url = url ?: "設定ページを準備しています…",
                            label = "設定URL",
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 24.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            when {
                                state.error != null -> Text(
                                    text = state.error,
                                    color = colors.danger,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                initialSetup && !canFinishInitialSetup -> Text(
                                    text = "接続設定を保存すると初期設定を完了できます。",
                                    color = colors.textMuted,
                                    style = MaterialTheme.typography.bodySmall,
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
                        contentDescription = "接続設定のQRコード",
                        size = 212.dp,
                    )
                }

                if (initialSetup) {
                    if (onSelectTvInput != null) {
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = onSelectTvInput,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("本体のテレビ入力を使う")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            enabled = canFinishInitialSetup,
                        ) { Text("設定を完了") }
                        DialogActionButton(
                            role = DialogActionRole.Primary,
                            onClick = onManualInput,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(manualR),
                        ) { Text("リモコンで手動入力") }
                    }
                } else {
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
}
