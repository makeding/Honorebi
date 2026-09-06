package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CloudflareAccessDialog(
    initialClientId: String,
    initialClientSecret: String,
    onDismiss: () -> Unit,
    onConfirm: suspend (String, String) -> Unit,
) {
    val colors = KomorebiTheme.colors
    var clientId by remember { mutableStateOf(initialClientId) }
    var clientSecret by remember { mutableStateOf(initialClientSecret) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val id = clientId.trim()
    val secret = clientSecret.trim()
    val valid = id.isBlank() == secret.isBlank() && (id + secret).all { it.code in 33..126 }
    val status = error ?: when {
        saving -> "設定を保存しています…"
        !valid -> "ID と Secret を半角で両方入力するか、両方消去してください。"
        id.isBlank() -> "保存するとサービス トークンを消去します。"
        else -> "保存後の接続からサービス トークンを使用します。"
    }
    val clientIdFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); clientIdFocus.safeRequestFocus() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.8f)).focusGroup().onKeyEvent {
            if (it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK || it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ESCAPE) {
                if (!saving && it.type == KeyEventType.KeyUp) onDismiss()
                true
            } else false
        },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), colors = SurfaceDefaults.colors(containerColor = colors.surface), modifier = Modifier.width(620.dp)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Cloudflare Access", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("保護する HTTPS バックエンドだけにサービス トークンを送信します。ID と Secret は両方入力するか、両方消去してください。")
                DialogTextField(clientId, { if (!saving) { clientId = it; error = null } }, "Client ID", focusRequester = clientIdFocus)
                DialogTextField(clientSecret, { if (!saving) { clientSecret = it; error = null } }, "Client Secret", isPassword = true, focusRequester = remember { FocusRequester() })
                Box(Modifier.fillMaxWidth().height(48.dp).testTag("access-status"), contentAlignment = Alignment.CenterStart) {
                    Text(status, color = if (error != null || !valid) Color(0xFFE53935) else colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !saving) { Text("キャンセル") }
                    Button(onClick = {
                        val id = clientId.trim()
                        val secret = clientSecret.trim()
                        if (id.isBlank() != secret.isBlank()) error = "Client ID と Client Secret を両方入力するか、両方消去してください。"
                        else {
                            saving = true
                            scope.launch {
                            try { onConfirm(id, secret); onDismiss() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "端末に設定を書き込めませんでした。空き容量を確認して再試行してください。" }
                            finally { saving = false }
                            }
                        }
                    }, modifier = Modifier.weight(1f).testTag("access-save"), enabled = !saving && valid
                    ) { Text(if (saving) "保存中…" else "保存") }
                }
            }
        }
    }
}
