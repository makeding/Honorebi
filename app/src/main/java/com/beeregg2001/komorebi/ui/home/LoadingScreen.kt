package com.beeregg2001.komorebi.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum

private val ProgressTrackWidth = 240.dp
private val ProgressTrackHeight = 4.dp
private val IndeterminatePillWidth = 84.dp
private val ProgressShape = RoundedCornerShape(999.dp)

@Composable
fun LoadingScreen(
    message: String = "",
    progressRatio: Float? = null,
) {
    val colors = KomorebiTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.honorebi_mark),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .width(ProgressTrackWidth)
                    .height(ProgressTrackHeight)
                    .clip(ProgressShape)
                    .background(colors.surface2),
            ) {
                if (progressRatio != null) {
                    val animatedProgress = animateFloatAsState(
                        targetValue = progressRatio.coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 320),
                        label = "loading_progress_anim",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = animatedProgress.value
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                            .background(colors.accent, ProgressShape),
                    )
                } else {
                    val infiniteTransition = rememberInfiniteTransition(label = "loading_indeterminate")
                    val progress = infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "loading_indeterminate_progress",
                    )
                    Box(
                        modifier = Modifier
                            .width(IndeterminatePillWidth)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val pillWidthPx = size.width
                                val trackWidthPx = ProgressTrackWidth.toPx()
                                translationX = -pillWidthPx + (trackWidthPx + pillWidthPx) * progress.value
                            }
                            .background(colors.accent, ProgressShape),
                    )
                }
            }
            if (message.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.tnum,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
