@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dashboard
import com.beeregg2001.komorebi.R
import java.time.LocalTime
import kotlinx.coroutines.delay

internal data class SettingsGuideCard(
    val title: String,
    val categoryIndex: Int,
    val icon: ImageVector,
)

internal val settingsGuideCards = listOf(
    SettingsGuideCard("一般設定", 0, Icons.Default.Settings),
    SettingsGuideCard("接続", 1, Icons.Default.CastConnected),
    SettingsGuideCard("UI", 8, Icons.Default.Dashboard),
    SettingsGuideCard("アプリ情報", 11, Icons.Default.Info),
)

@Composable
internal fun SettingsGuideScreen(
    onSelectCategory: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val background = getSeasonalBackgroundBrush(KomorebiTheme.theme, remember { LocalTime.now() })
    val firstCardRequester = remember { FocusRequester() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        delay(120)
        firstCardRequester.safeRequestFocus("SettingsGuide_FirstCard")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(background)
            .padding(horizontal = 72.dp, vertical = 52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.honorebi_mark),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "設定",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            settingsGuideCards.forEachIndexed { index, card ->
                Surface(
                    onClick = { onSelectCategory(card.categoryIndex) },
                    modifier = Modifier
                        .weight(1f)
                        .height(230.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstCardRequester) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.surface.copy(alpha = 0.82f),
                        focusedContainerColor = MaterialTheme.colorScheme.primary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Box(Modifier.fillMaxSize().padding(26.dp)) {
                        Icon(
                            imageVector = card.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 24.dp, y = (-12).dp)
                                .size(132.dp),
                            tint = colors.textPrimary.copy(alpha = 0.09f),
                        )
                        Text(
                            card.title,
                            modifier = Modifier.align(Alignment.BottomStart),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Button(onClick = onBack) { Text("戻る") }
        }
    }
}
