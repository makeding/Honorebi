package com.beeregg2001.komorebi.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

const val DEFAULT_TOAST_DURATION_MS = 3_000L

val LocalShowToast = compositionLocalOf<(String) -> Unit> { {} }

@Composable
fun GlobalToast(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.BottomCenter)
            .padding(start = 32.dp, end = 32.dp, bottom = 48.dp)
    ) {
        Surface(
            color = colors.overlay,
            contentColor = colors.textPrimary,
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, colors.borderSubtle),
            shadowElevation = 0.dp,
            modifier = Modifier.widthIn(min = 160.dp, max = 560.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
