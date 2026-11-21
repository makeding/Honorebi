@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum

/** Shared TV dialog shell and action roles. Sizes remain owned by each dialog. */
enum class DialogActionRole { Secondary, Primary, Destructive }

/** Unified dark backdrop scrim for full-screen modal overlays (zero blur, 32-bit TV safe). */
val DialogBackdropScrim: Color = Color.Black.copy(alpha = 0.72f)

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
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .width(width)
            .border(1.dp, colors.borderSubtle, shape),
        shape = shape,
        colors = SurfaceDefaults.colors(
            containerColor = colors.overlay,
            contentColor = colors.textPrimary,
        ),
    ) {
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

/** High-contrast flat URL display block for QR / companion setup dialogs. */
@Composable
fun DialogUrlBox(
    url: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface2, shape)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = url,
            style = MaterialTheme.typography.titleSmall.tnum,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Clean rounded white container for QR code bitmaps so edges never clip sharply. */
@Composable
fun DialogQrCard(
    bitmap: ImageBitmap?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    placeholder: String = "QRコードを準備中",
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White, shape)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF222222),
            )
        }
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
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val buttonShape = RoundedCornerShape(10.dp)
    val focusBorder = Border(BorderStroke(2.dp, colors.accent), shape = buttonShape)
    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer { clip = false }
            .hoverable(interactionSource, enabled),
        interactionSource = interactionSource,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = buttonShape),
        scale = ButtonDefaults.scale(focusedScale = 1.0f),
        colors = ButtonDefaults.colors(
            containerColor = background,
            contentColor = foreground,
            focusedContainerColor = background,
            focusedContentColor = foreground,
        ),
        border = ButtonDefaults.border(
            border = if (hovered && enabled) focusBorder else Border.None,
            focusedBorder = focusBorder,
        ),
    ) { content() }
}
