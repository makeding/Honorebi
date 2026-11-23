package com.beeregg2001.komorebi.ui.setting

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.remote.CastAccessMode
import kotlinx.coroutines.launch

@Composable
fun CastAccessSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { SettingsRepository(context) }
    val mode by repository.castAccessMode.collectAsState(initial = CastAccessMode.ACCOUNT)
    val scope = rememberCoroutineScope()
    SettingItem(
        title = "キャスト・リモート操作の接続許可",
        value = mode.label,
        icon = Icons.Default.SettingsRemote,
        modifier = modifier,
        onClick = {
            scope.launch {
                val next = CastAccessMode.entries[(mode.ordinal + 1) % CastAccessMode.entries.size]
                repository.saveString(SettingsRepository.CAST_ACCESS_MODE, next.name)
            }
        },
    )
}
