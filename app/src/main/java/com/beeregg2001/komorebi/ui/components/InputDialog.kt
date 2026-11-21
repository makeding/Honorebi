package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = KomorebiTheme.colors
    var text by remember { mutableStateOf(initialValue) }

    val textFieldFocusRequester = remember { FocusRequester() }
    val saveButtonFocusRequester = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        ThemedDialogSurface(width = 460.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(textFieldFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface2,
                        focusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        cursorColor = colors.accent,
                        unfocusedContainerColor = colors.surface1,
                        unfocusedTextColor = colors.textPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { saveButtonFocusRequester.requestFocus() }
                    )
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("キャンセル") }

                    DialogActionButton(
                        role = DialogActionRole.Primary,
                        onClick = { onConfirm(text) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveButtonFocusRequester),
                    ) { Text("保存") }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        textFieldFocusRequester.requestFocus()
    }
}
