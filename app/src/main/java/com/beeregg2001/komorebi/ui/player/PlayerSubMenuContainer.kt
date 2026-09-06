package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

/** Common fixed menu surface. Player modes supply their own focus and navigation modifiers. */
@Composable
fun PlayerSubMenuContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.background.copy(alpha = 0.9f), Color.Transparent)
                )
            )
            .padding(top = 24.dp, bottom = 48.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        content()
    }
}
