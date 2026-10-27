@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.launch

/** Connection reasons are readable in place, with D-pad scrolling only when needed. */
@Composable
fun ConnectionFailureReason(reason: String, modifier: Modifier = Modifier) {
    val colors = KomorebiTheme.colors
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var detailFocused by remember { mutableStateOf(false) }

    LaunchedEffect(reason) { scrollState.scrollTo(0) }

    Box(
        modifier
            .border(
                width = 1.dp,
                color = if (detailFocused) colors.accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .verticalScroll(scrollState)
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
            .focusable(),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(reason, color = colors.textPrimary, style = MaterialTheme.typography.bodySmall)
    }
}
