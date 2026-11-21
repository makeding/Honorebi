package com.beeregg2001.komorebi.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum

@Composable
fun LoadingScreen(
    message: String = "",
    progressRatio: Float? = null,
) {
    val colors = KomorebiTheme.colors
    val progressShape = RoundedCornerShape(999.dp)

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
            if (progressRatio != null) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progressRatio.coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 320),
                    label = "loading_progress_anim",
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    color = colors.accent,
                    trackColor = colors.surface2,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .width(240.dp)
                        .height(4.dp)
                        .clip(progressShape),
                )
            } else {
                LinearProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.surface2,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .width(240.dp)
                        .height(4.dp)
                        .clip(progressShape),
                )
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
