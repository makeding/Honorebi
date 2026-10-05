@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Dashboard
import com.beeregg2001.komorebi.R
import java.time.LocalTime
import kotlinx.coroutines.delay

internal data class SettingsGuideCard(
    val title: String,
    val categoryIndex: Int,
    val icon: ImageVector,
    val openDecoderCheck: Boolean = false,
)

internal val settingsGuideCards = listOf(
    SettingsGuideCard("一般設定", 0, Icons.Default.Settings),
    SettingsGuideCard("再生設定", 2, Icons.Default.PlayCircle),
    SettingsGuideCard("画面設定", 8, Icons.Default.Dashboard),
    SettingsGuideCard("コメント設定", 5, Icons.Default.Tv),
    SettingsGuideCard("接続設定", 1, Icons.Default.Link),
    SettingsGuideCard("データ管理", 10, Icons.Default.DeleteSweep),
    SettingsGuideCard("デコード能力チェック", 11, Icons.Default.Memory, openDecoderCheck = true),
    SettingsGuideCard("アプリ情報", 11, Icons.Default.Info),
)

@Composable
internal fun SettingsGuideScreen(
    onSelectCategory: (SettingsGuideCard) -> Unit,
    initialCardIndex: Int = 0,
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
            .padding(horizontal = 72.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.honorebi_mark),
                contentDescription = null,
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "設定",
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 40.sp,
                lineHeight = 48.sp,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            settingsGuideCards.chunked(4).forEachIndexed { rowIndex, rowCards ->
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowCards.forEachIndexed { columnIndex, card ->
                        val index = rowIndex * 4 + columnIndex
                        val interactionSource = remember { MutableInteractionSource() }
                        val focused by interactionSource.collectIsFocusedAsState()
                        val hovered by interactionSource.collectIsHoveredAsState()
                        val highlighted = focused || hovered
                        val highlightContent = MaterialTheme.colorScheme.onPrimary
                        Surface(
                            onClick = { onSelectCategory(card) },
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .hoverable(interactionSource)
                                .then(if (index == initialCardIndex) Modifier.focusRequester(firstCardRequester) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (hovered) MaterialTheme.colorScheme.primary
                                else colors.surface.copy(alpha = 0.82f),
                                focusedContainerColor = MaterialTheme.colorScheme.primary,
                                contentColor = colors.textPrimary,
                                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = card.icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 20.dp, end = 20.dp)
                                        .size(64.dp),
                                    tint = when {
                                        highlighted && colors.isDark -> Color.White
                                        highlighted -> highlightContent
                                        colors.isDark -> Color.White.copy(alpha = 0.20f)
                                        else -> colors.textPrimary.copy(alpha = 0.18f)
                                    },
                                )
                                Text(
                                    card.title,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(18.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (highlighted) highlightContent else colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
