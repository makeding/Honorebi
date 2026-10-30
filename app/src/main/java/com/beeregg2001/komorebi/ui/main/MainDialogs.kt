@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    isSubmitting: Boolean = false,
    failure: UserFacingFailure? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("DeleteConfirm") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Text(
                    text = failure?.let { "${it.message} [${it.code}]" } ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failure != null) MaterialTheme.colorScheme.error else Color.Transparent,
                    modifier = Modifier.height(64.dp),
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = { if (!isSubmitting) onCancel() },
                        modifier = Modifier.weight(1f).semantics { if (isSubmitting) disabled() },
                    ) { Text(AppStrings.BUTTON_CANCEL) }
                    DialogActionButton(
                        role = DialogActionRole.Destructive,
                        onClick = { if (!isSubmitting) onConfirm() },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .semantics { if (isSubmitting) disabled() }
                    ) { Text(if (isSubmitting) "処理中…" else AppStrings.BUTTON_DELETE) }
                }
            }
        }
    }
}

@Composable
fun IncompatibleOsDialog(onExit: () -> Unit) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); focusRequester.safeRequestFocus("IncompatibleOS") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            modifier = Modifier.width(420.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.INCOMPATIBLE_OS_TITLE,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = String.format(
                        AppStrings.INCOMPATIBLE_OS_MESSAGE,
                        Build.VERSION.SDK_INT
                    ), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(32.dp))
                DialogActionButton(
                    role = DialogActionRole.Secondary,
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                ) { Text(AppStrings.EXIT_APP_FULL) }
            }
        }
    }
}
