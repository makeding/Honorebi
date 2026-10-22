@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Full, remote-scrollable safe backend reason shared by startup and cached-home recovery. */
@Composable
fun ConnectionFailureDetailsDialog(
    reason: String,
    onDismiss: () -> Unit,
    detailTestTag: String,
    closeTestTag: String,
) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()
    val detailFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var detailFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        detailFocusRequester.safeRequestFocus("ConnectionFailureDetails")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ThemedDialogSurface(width = 680.dp) {
            Text(
                "接続エラーの詳細",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .border(
                        width = if (detailFocused) 2.dp else 1.dp,
                        color = if (detailFocused) colors.accent else colors.textPrimary.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(16.dp)
                    .verticalScroll(scrollState)
                    .focusRequester(detailFocusRequester)
                    .onFocusChanged { detailFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> if (scrollState.canScrollForward) {
                                scope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value + 96).coerceAtMost(scrollState.maxValue),
                                    )
                                }
                                true
                            } else false
                            Key.DirectionUp -> if (scrollState.canScrollBackward) {
                                scope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value - 96).coerceAtLeast(0),
                                    )
                                }
                                true
                            } else false
                            else -> false
                        }
                    }
                    .focusable()
                    .testTag(detailTestTag),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(reason, color = colors.textPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(18.dp))
            DialogActionButton(
                role = DialogActionRole.Secondary,
                onClick = onDismiss,
                modifier = Modifier
                    .width(140.dp)
                    .testTag(closeTestTag),
            ) { Text("閉じる") }
        }
    }
}
