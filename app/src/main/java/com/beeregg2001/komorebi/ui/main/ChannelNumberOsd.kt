package com.beeregg2001.komorebi.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

data class ChannelNumberOsdState(
    val inputBuffer: String = "",
    val matchedChannelName: String? = null,
    val notFoundInput: String? = null,
) {
    val isVisible: Boolean get() = inputBuffer.isNotEmpty() || notFoundInput != null
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChannelNumberOsd(
    state: ChannelNumberOsdState,
    isInOsPip: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    var lastVisibleState by remember { mutableStateOf(state) }
    if (state.isVisible) {
        lastVisibleState = state
    }

    AnimatedVisibility(
        visible = !isInOsPip && state.isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.focusProperties { canFocus = false },
    ) {
        val shown = if (state.isVisible) state else lastVisibleState
        val displayDigits = shown.inputBuffer.ifEmpty { shown.notFoundInput.orEmpty() }
        val subtitle = if (shown.inputBuffer.isNotEmpty()) {
            shown.matchedChannelName ?: "該当チャンネルなし"
        } else {
            val missing = shown.notFoundInput.orEmpty()
            "CH $missing が見つかりません。番号を確認してもう一度入力してください"
        }

        Surface(
            color = colors.surface2,
            contentColor = colors.textPrimary,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.5.dp, colors.accent),
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 260.dp, max = 380.dp)
                .heightIn(min = 84.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "CH $displayDigits",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        letterSpacing = 1.sp,
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    minLines = 1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
