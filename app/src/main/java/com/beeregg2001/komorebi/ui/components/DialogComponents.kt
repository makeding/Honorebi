@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

/** Shared TV dialog shell and action roles. Sizes remain owned by each dialog. */
enum class DialogActionRole { Secondary, Primary, Destructive }

fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Chooses the black or white foreground with the highest WCAG contrast for this background. */
fun dialogForeground(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

@Composable
fun ThemedDialogSurface(
    width: Dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(24.dp),
    content: @Composable () -> Unit,
) {
    val colors = KomorebiTheme.colors
    Surface(
        modifier = modifier.width(width),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = colors.surface, contentColor = colors.textPrimary),
    ) {
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun DialogActionButton(
    role: DialogActionRole,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val background = when (role) {
        DialogActionRole.Secondary -> colors.textPrimary.copy(alpha = 0.10f)
        DialogActionRole.Primary -> colors.accent
        DialogActionRole.Destructive -> MaterialTheme.colorScheme.error
    }
    val foreground = when (role) {
        DialogActionRole.Secondary -> colors.textPrimary
        DialogActionRole.Primary -> dialogForeground(background)
        DialogActionRole.Destructive -> dialogForeground(background)
    }
    val focusedBackground = when (role) {
        DialogActionRole.Secondary -> colors.accent
        else -> background
    }
    val focusedForeground = when (role) {
        DialogActionRole.Secondary -> dialogForeground(focusedBackground)
        else -> foreground
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = background,
            contentColor = foreground,
            focusedContainerColor = focusedBackground,
            focusedContentColor = focusedForeground,
        ),
    ) { content() }
}
