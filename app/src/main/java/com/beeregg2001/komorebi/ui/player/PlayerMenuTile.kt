package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.OsdBorderSubtle
import com.beeregg2001.komorebi.ui.theme.OsdSurface
import com.beeregg2001.komorebi.ui.theme.OsdSurfaceFocused
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.tnum

/** Visual differences are explicit, so shared tile behavior does not pick a player style. */
data class PlayerMenuTileStyle(val disabledAlpha: Float)

val LivePlayerMenuTileStyle = PlayerMenuTileStyle(disabledAlpha = 0.5f)
val RecordedPlayerMenuTileStyle = PlayerMenuTileStyle(disabledAlpha = 0.4f)

@Composable
fun PlayerMenuTile(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit,
    style: PlayerMenuTileStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 136.dp,
    height: Dp = 86.dp,
    contentColor: Color = Color.White
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = OsdSurface,
            contentColor = if (enabled) contentColor else colors.textPrimary.copy(alpha = 0.3f),
            focusedContainerColor = OsdSurfaceFocused,
            focusedContentColor = Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, OsdBorderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent), shape = shape)
        ),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        modifier = modifier.width(width).height(height).alpha(if (enabled) 1f else style.disabledAlpha)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.tnum,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
