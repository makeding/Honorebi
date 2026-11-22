package com.beeregg2001.komorebi.ui.setting

import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.media.tv.TvInputManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

/** Shared by the connection settings and first-run source selection. */
@Composable
fun TifInputSettings(
    onRefresh: () -> Unit,
    first: FocusRequester = remember { FocusRequester() },
    last: FocusRequester = remember { FocusRequester() },
    before: FocusRequester = FocusRequester.Default,
    after: FocusRequester = FocusRequester.Default,
    sidebar: FocusRequester = FocusRequester.Default,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = "android.permission.READ_TV_LISTINGS"
    var generation by remember { mutableIntStateOf(0) }
    val inputs = remember(generation) {
        context.getSystemService(TvInputManager::class.java)?.tvInputList.orEmpty()
            .filterNot { it.isHidden(context) }
    }
    val granted = remember(generation) {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val latestRefresh by rememberUpdatedState(onRefresh)
    var error by remember { mutableStateOf<String?>(null) }
    var showDebug by remember { mutableStateOf(false) }
    fun refresh() { generation++; latestRefresh() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val setupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val needsPermission = !granted && inputs.any { !it.isPassthroughInput }
    val setupInputs = inputs.mapNotNull { input -> input.createSetupIntent()?.let { input to it } }
    val requesters = remember(needsPermission, setupInputs.map { it.first.id }) {
        List((if (needsPermission) 1 else 0) + setupInputs.size + 1) { FocusRequester() }
    }
    val actionCount = requesters.size + 1
    fun actionModifier(index: Int): Modifier {
        val requester = if (index == 0) first else if (index == actionCount - 1) last else requesters[index]
        val modifiers = if (actionCount == 1) Modifier.focusRequester(last).focusRequester(first)
            else Modifier.focusRequester(requester)
        return modifiers.focusProperties {
            up = if (index == 0) before else if (index == 1) first else requesters[index - 1]
            down = if (index == actionCount - 1) after else if (index == actionCount - 2) last else requesters[index + 1]
            left = sidebar
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var index = 0
        if (needsPermission) {
            SettingItem(
                title = "TV番組一覧へのアクセス",
                value = "許可する",
                icon = Icons.Default.LockOpen,
                modifier = actionModifier(index++),
                onClick = { permissionLauncher.launch(permission) },
            )
        }
        setupInputs.forEach { (input, intent) ->
            val label = input.loadCustomLabel(context)?.toString() ?: input.loadLabel(context).toString()
            SettingItem(
                title = label,
                value = "設定・チャンネルスキャン",
                icon = Icons.Default.Tune,
                modifier = actionModifier(index++),
                onClick = {
                    try {
                        error = null
                        setupLauncher.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        error = "TV入力の設定画面が見つかりません。端末の設定から確認してください。"
                    } catch (_: SecurityException) {
                        error = "TV入力の設定画面を開く権限がありません。端末の設定から確認してください。"
                    }
                },
            )
        }
        SettingItem(
            title = "チャンネル診断",
            value = "表示",
            icon = Icons.Default.Info,
            modifier = actionModifier(index++),
            onClick = { showDebug = true },
        )
        SettingItem(
            title = "TV入力を再読み込み",
            value = if (inputs.isEmpty()) "未検出" else "${inputs.size}件検出",
            icon = Icons.Default.Refresh,
            modifier = actionModifier(index),
            onClick = { refresh() },
        )
        if (inputs.isEmpty()) {
            Text(
                text = "TV入力がありません。端末のTV入力を接続または追加してください。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    if (showDebug) TifChannelDebugDialog(onDismiss = { showDebug = false })
}
