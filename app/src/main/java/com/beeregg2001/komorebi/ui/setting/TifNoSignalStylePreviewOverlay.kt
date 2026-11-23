@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.TifNoSignalAccentColor
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.live.TifNoSignalLayer
import com.beeregg2001.komorebi.ui.live.TifStatusBanner
import com.beeregg2001.komorebi.ui.live.previewNoSignalRenderMode
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.tnum

internal const val TIF_NO_SIGNAL_PREVIEW_OVERLAY_TAG = "tif-no-signal-preview-overlay"
internal const val TIF_NO_SIGNAL_PREVIEW_LABEL_TAG = "tif-no-signal-preview-label"
internal const val TIF_NO_SIGNAL_PREVIEW_DESCRIPTION_TAG = "tif-no-signal-preview-description"
internal const val TIF_NO_SIGNAL_PREVIEW_COUNTER_TAG = "tif-no-signal-preview-counter"
internal const val TIF_NO_SIGNAL_PREVIEW_APPLIED_BADGE_TAG = "tif-no-signal-preview-applied-badge"
internal const val TIF_NO_SIGNAL_PREVIEW_PREV_TAG = "tif-no-signal-preview-prev"
internal const val TIF_NO_SIGNAL_PREVIEW_NEXT_TAG = "tif-no-signal-preview-next"
internal const val TIF_NO_SIGNAL_PREVIEW_COLOR_TAG = "tif-no-signal-preview-color"

private val PreviewHudScrim = Color(0xD60C1018)
private val PreviewHudBorder = Color.White.copy(alpha = 0.16f)
private val PreviewArrowBg = Color.White.copy(alpha = 0.10f)

@Composable
internal fun TifNoSignalStylePreviewOverlay(
    currentStyle: TifNoSignalStyle,
    screensaverImageUris: List<String>,
    screensaverIntervalSeconds: Int,
    screensaverTransition: String,
    onSelect: (TifNoSignalStyle) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    currentAccentColor: TifNoSignalAccentColor = TifNoSignalAccentColor.RANDOM,
    onSelectAccentColor: ((TifNoSignalAccentColor) -> Unit)? = null,
) {
    val colors = KomorebiTheme.colors
    val styles = remember { TifNoSignalStyle.entries }
    val accentColors = remember { TifNoSignalAccentColor.entries }
    var previewIndex by remember(currentStyle) {
        mutableIntStateOf(styles.indexOf(currentStyle).coerceAtLeast(0))
    }
    var accentColorIndex by remember(currentAccentColor) {
        mutableIntStateOf(accentColors.indexOf(currentAccentColor).coerceAtLeast(0))
    }
    val previewStyle = styles[previewIndex]
    val previewAccentColor = accentColors[accentColorIndex]
    val supportsColorCustomization = previewStyle == TifNoSignalStyle.NOK_CALLSIGN
    val focusRequester = remember { FocusRequester() }

    val selectPrevious = {
        previewIndex = (previewIndex - 1 + styles.size) % styles.size
    }
    val selectNext = {
        previewIndex = (previewIndex + 1) % styles.size
    }
    val selectPreviousColor = {
        accentColorIndex = (accentColorIndex - 1 + accentColors.size) % accentColors.size
    }
    val selectNextColor = {
        accentColorIndex = (accentColorIndex + 1) % accentColors.size
    }

    LaunchedEffect(Unit) {
        focusRequester.safeRequestFocusWithRetry("TifNoSignalStylePreviewOverlay")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(TIF_NO_SIGNAL_PREVIEW_OVERLAY_TAG)
            .focusRequester(focusRequester)
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        selectPrevious()
                        true
                    }
                    Key.DirectionRight -> {
                        selectNext()
                        true
                    }
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter -> {
                        if (supportsColorCustomization) {
                            onSelectAccentColor?.invoke(previewAccentColor)
                        }
                        onSelect(previewStyle)
                        true
                    }
                    Key.Back,
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.DirectionUp -> {
                        if (supportsColorCustomization) selectPreviousColor()
                        true
                    }
                    Key.DirectionDown -> {
                        if (supportsColorCustomization) selectNextColor()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (previewStyle == TifNoSignalStyle.BANNER) {
            TifStatusBanner(
                text = "信号がありません",
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            TifNoSignalLayer(
                mode = previewNoSignalRenderMode(previewStyle),
                style = previewStyle,
                accentColor = previewAccentColor,
                statusText = "信号がありません",
                inputLabel = "HDMI 1",
                isPassthroughInput = true,
                screensaverImageUris = screensaverImageUris,
                screensaverIntervalSeconds = screensaverIntervalSeconds,
                screensaverTransition = screensaverTransition,
                showOverlay = false,
                paused = false,
                onDegraded = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        val hudShape = RoundedCornerShape(18.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 24.dp)
                .widthIn(min = 440.dp, max = 640.dp)
                .background(PreviewHudScrim, hudShape)
                .border(1.dp, PreviewHudBorder, hudShape)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreviewArrowButton(
                    icon = Icons.Default.ChevronLeft,
                    contentDescription = "前のスタイル",
                    onClick = selectPrevious,
                    modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_PREV_TAG),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = previewStyle.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_LABEL_TAG),
                        )
                        if (supportsColorCustomization) {
                            Row(
                                modifier = Modifier
                                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 9.dp, vertical = 2.dp)
                                    .testTag(TIF_NO_SIGNAL_PREVIEW_COLOR_TAG),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(previewAccentColor.mainArgb), CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                                )
                                Text(
                                    text = "カラー: ${previewAccentColor.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.90f),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (previewStyle == currentStyle) {
                            Box(
                                modifier = Modifier
                                    .background(colors.accent, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "適用中",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onAccent,
                                    maxLines = 1,
                                    modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_APPLIED_BADGE_TAG),
                                )
                            }
                        }
                    }

                    Text(
                        text = previewStyle.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.80f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_DESCRIPTION_TAG),
                    )

                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            styles.indices.forEach { index ->
                                val isActive = index == previewIndex
                                Box(
                                    modifier = Modifier
                                        .width(if (isActive) 14.dp else 5.dp)
                                        .height(5.dp)
                                        .background(
                                            color = if (isActive) colors.accent else Color.White.copy(alpha = 0.28f),
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }

                        Text(
                            text = "${previewIndex + 1} / ${styles.size}",
                            style = MaterialTheme.typography.labelSmall.tnum,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_COUNTER_TAG),
                        )
                    }
                }

                PreviewArrowButton(
                    icon = Icons.Default.ChevronRight,
                    contentDescription = "次のスタイル",
                    onClick = selectNext,
                    modifier = Modifier.testTag(TIF_NO_SIGNAL_PREVIEW_NEXT_TAG),
                )
            }

            Text(
                text = if (supportsColorCustomization) {
                    "◀ ▶ 切替   •   ▲ ▼ カラー   •   決定 選択   •   戻る キャンセル"
                } else {
                    "◀ ▶ 切替   •   決定 選択   •   戻る キャンセル"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PreviewArrowButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(PreviewArrowBg, CircleShape)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.90f),
            modifier = Modifier.size(22.dp),
        )
    }
}
