@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import androidx.compose.ui.window.Dialog

@Composable
fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }

    val colors = KomorebiTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focusRequester.safeRequestFocusWithRetry("ExitDialog")
        }
        ThemedDialogSurface(width = 420.dp) {
            Text("アプリを終了しますか？", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Left,
                color = colors.textPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogActionButton(DialogActionRole.Secondary, onDismiss, Modifier.weight(1f)) { Text("キャンセル") }
                DialogActionButton(DialogActionRole.Primary, onConfirm, Modifier.weight(1f).focusRequester(focusRequester)) { Text("終了") }
            }
        }
    }
}
