package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.components.ThemedDialogSurface

@Composable
fun HonomiLogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    var confirmed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            cancelFocus.safeRequestFocusWithRetry("HonomiLogoutDialog_Cancel")
        }
        ThemedDialogSurface(width = 420.dp) {
            Text("HonomiTV からログアウトしますか？", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogActionButton(
                    DialogActionRole.Secondary,
                    onDismiss,
                    Modifier.weight(1f).focusRequester(cancelFocus),
                ) { Text(AppStrings.BUTTON_CANCEL) }
                DialogActionButton(
                    DialogActionRole.Destructive,
                    onClick = {
                        if (!confirmed) {
                            confirmed = true
                            onConfirm()
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("honomi-logout-confirm"),
                ) { Text("ログアウト") }
            }
        }
    }
}
