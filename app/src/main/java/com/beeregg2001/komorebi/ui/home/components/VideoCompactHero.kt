package com.beeregg2001.komorebi.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum

@Immutable
data class VideoHeroPreview(
    val title: String,
    val metaLine: String = "",
    val description: String = "",
    val progress: Float? = null,
    val resumeLabel: String? = null,
    val isRecording: Boolean = false,
)

@Composable
fun VideoCompactHero(
    state: VideoHeroPreview,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(108.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.metaLine.isNotBlank() || state.isRecording || state.progress != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 280.dp),
            ) {
                if (state.isRecording) {
                    Box(
                        modifier = Modifier
                            .background(colors.recording, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "REC",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                lineHeight = 13.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                            ),
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (state.metaLine.isNotBlank()) {
                    Text(
                        text = state.metaLine,
                        style = MaterialTheme.typography.labelMedium.tnum,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (state.progress != null && !state.isRecording) {
                    Spacer(Modifier.width(10.dp))
                    val pillText = state.resumeLabel?.takeIf { it.isNotBlank() }
                        ?: "視聴済み ${(state.progress * 100).toInt().coerceIn(1, 100)}%"
                    Box(
                        modifier = Modifier
                            .background(colors.surface2, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = pillText,
                            style = MaterialTheme.typography.labelSmall.tnum.copy(
                                lineHeight = 13.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both,
                                ),
                            ),
                            color = colors.accent,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
        }

        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 280.dp),
        )

        if (state.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
