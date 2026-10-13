package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.setup.ConnectionSetupUiState
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.components.ThemedDialogSurface
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

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
) {
    val colors = KomorebiTheme.colors
    val manualR = remember { FocusRequester() }
    DisposableEffect(Unit) { onStart(); onDispose { onStop() } }
    LaunchedEffect(Unit) { withFrameNanos { }; manualR.safeRequestFocus("ConnectionSetup_Initial") }
    val session = state.session
    val url = session?.let { "http://$serverIp:8081/connections/" +
        (it.source?.let { source -> "?source=$source" } ?: "") + "#${it.token}" }
    val bitmap = url?.let { rememberQrBitmap(it, 300) }
    Dialog(onDismissRequest = if (initialSetup && !canFinishInitialSetup) ({}) else onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !initialSetup)) {
        ThemedDialogSurface(width = 680.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("スマホで接続設定", style = MaterialTheme.typography.titleLarge)
                        Text("同じネットワークのスマホでQRコードを読み取り、設定を入力して保存してください。",
                            color = colors.textSecondary)
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.CenterStart) {
                            Text(state.error ?: if (session == null) "設定ページを準備しています…"
                                else if (initialSetup && !canFinishInitialSetup) "接続設定を保存すると、初期設定を完了できます。"
                                else "テレビに設定を反映します。",
                                color = if (state.error != null) Color(0xFFE53935) else colors.textSecondary,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(Modifier.fillMaxWidth().height(48.dp)) {
                            if (state.error != null) DialogActionButton(DialogActionRole.Secondary, onStart) { Text("再試行") }
                        }
                    }
                    Box(Modifier.size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap, "接続設定のQRコード", Modifier.fillMaxSize())
                        else Text("QRコードを準備中", color = Color.Black)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DialogActionButton(DialogActionRole.Secondary, onDismiss, Modifier.weight(1f),
                        enabled = !initialSetup || canFinishInitialSetup) {
                        Text(if (initialSetup) "設定を完了" else "閉じる")
                    }
                    DialogActionButton(DialogActionRole.Primary, onManualInput,
                        Modifier.weight(1f).focusRequester(manualR)) { Text("リモコンで手動入力") }
                }
            }
        }
    }
}
