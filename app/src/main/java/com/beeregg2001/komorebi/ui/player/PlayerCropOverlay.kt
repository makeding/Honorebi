package com.beeregg2001.komorebi.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

private const val CROP_OVERLAY_TAG = "PlayerLCropOverlay"
private const val CROP_ZOOM_STEP = 2f
private val CROP_ZOOM_PRESETS = listOf(115, 125, 135)

internal const val CROP_MENU_PANEL_TEST_TAG = "player-crop-menu-panel"
internal const val CROP_DIRECT_ADJUST_HUD_TEST_TAG = "player-crop-direct-adjust-hud"
internal const val CROP_DIRECT_ADJUST_BUTTON_TEST_TAG = "player-crop-direct-adjust-button"
internal const val CROP_ZOOM_OUT_BUTTON_TEST_TAG = "player-crop-zoom-out-button"
internal const val CROP_ZOOM_IN_BUTTON_TEST_TAG = "player-crop-zoom-in-button"
internal const val CROP_ORIGIN_BUTTON_TEST_TAG = "player-crop-origin-button"
internal const val CROP_RESET_BUTTON_TEST_TAG = "player-crop-reset-button"
internal const val CROP_DISABLE_BUTTON_TEST_TAG = "player-crop-disable-button"
internal const val CROP_DONE_BUTTON_TEST_TAG = "player-crop-done-button"

/**
 * L字クロップの共有オーバーレイ (ライブ/録画/SMB 共通: PlayerCropState のみを参照する)。
 * ライブ側 LCropOverlay / 録画側 VideoLCropOverlay から委譲される。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerLCropOverlay(
    crop: PlayerCropState,
    onClose: () -> Unit,
    inputProfile: PlayerCropInputProfile = LivePlayerCropInputProfile,
) {
    val colors = KomorebiTheme.colors
    val menuFocusRequester = remember { FocusRequester() }
    val directAdjustFocusRequester = remember { FocusRequester() }
    var hasMenuFocus by remember { mutableStateOf(false) }
    var hasDirectAdjustFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = crop.mode != PlayerCropMode.HIDDEN) {
        if (crop.mode == PlayerCropMode.DIRECT_ADJUST) {
            crop.mode = PlayerCropMode.MENU
        } else {
            onClose()
        }
    }

    // モード切り替え時や背後オーバーレイのフェードアウトでフォーカスが抜けた際も確実に再取得する
    LaunchedEffect(crop.mode, hasMenuFocus) {
        if (crop.mode == PlayerCropMode.MENU && !hasMenuFocus) {
            withFrameNanos { }
            menuFocusRequester.safeRequestFocusWithRetry(
                tag = CROP_OVERLAY_TAG,
                maxRetries = 8,
                delayMillis = 80L,
                isCurrent = { crop.mode == PlayerCropMode.MENU }
            )
        }
    }

    LaunchedEffect(crop.mode, hasDirectAdjustFocus) {
        if (crop.mode == PlayerCropMode.DIRECT_ADJUST && !hasDirectAdjustFocus) {
            withFrameNanos { }
            directAdjustFocusRequester.safeRequestFocusWithRetry(
                tag = CROP_OVERLAY_TAG,
                maxRetries = 8,
                delayMillis = 80L,
                isCurrent = { crop.mode == PlayerCropMode.DIRECT_ADJUST }
            )
        }
    }

    if (crop.mode == PlayerCropMode.DIRECT_ADJUST) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PlayerOverlayEdgePaddingHorizontal, vertical = 24.dp)
                .focusRequester(directAdjustFocusRequester)
                .onFocusChanged { hasDirectAdjustFocus = it.hasFocus }
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == android.view.KeyEvent.KEYCODE_BACK ||
                        code == android.view.KeyEvent.KEYCODE_ESCAPE
                    ) {
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            crop.mode = PlayerCropMode.MENU
                        }
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            DirectAdjustPillHud(
                crop = crop,
                inputProfile = inputProfile,
            )
        }
    } else if (crop.mode == PlayerCropMode.MENU) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PlayerOverlayEdgePaddingHorizontal, vertical = 24.dp)
                .onPreviewKeyEvent { keyEvent ->
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == android.view.KeyEvent.KEYCODE_BACK ||
                        code == android.view.KeyEvent.KEYCODE_ESCAPE
                    ) {
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            onClose()
                        }
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 740.dp)
                    .fillMaxWidth()
                    .testTag(CROP_MENU_PANEL_TEST_TAG)
                    .background(
                        color = colors.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = colors.textPrimary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .onFocusChanged { hasMenuFocus = it.hasFocus }
                    .focusGroup()
            ) {
                // --- ヘッダー: タイトル + 現在値バッジ ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Crop,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "L字クロップ",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CropStatusBadge(
                            text = "倍率 ${crop.zoomPercent.toInt()}%",
                            highlighted = crop.zoomPercent > 100f
                        )
                        CropStatusBadge(
                            text = "X ${crop.xPercent.toInt()}% · Y ${crop.yPercent.toInt()}%",
                            highlighted = crop.xPercent != 0f || crop.yPercent != 0f
                        )
                        CropStatusBadge(
                            text = "起点 ${playerZoomOriginLabel(crop.origin)}",
                            highlighted = false
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- 1行目: メイン調整コントロール ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 拡大率ステッパー (- / 現在値 / +)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(44.dp)
                            .background(
                                color = colors.textPrimary.copy(alpha = 0.06f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = colors.textPrimary.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 4.dp)
                    ) {
                        CropIconButton(
                            icon = Icons.Default.Remove,
                            contentDescription = "拡大率を下げる",
                            modifier = Modifier.testTag(CROP_ZOOM_OUT_BUTTON_TEST_TAG)
                        ) {
                            crop.zoomPercent = (crop.zoomPercent - CROP_ZOOM_STEP).coerceAtLeast(100f)
                        }
                        Text(
                            text = "${crop.zoomPercent.toInt()}%",
                            color = colors.textPrimary,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(54.dp)
                        )
                        CropIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = "拡大率を上げる",
                            modifier = Modifier.testTag(CROP_ZOOM_IN_BUTTON_TEST_TAG)
                        ) {
                            crop.zoomPercent = (crop.zoomPercent + CROP_ZOOM_STEP).coerceAtMost(200f)
                        }
                    }

                    // 拡大起点セレクター (2x2コーナー図付き)
                    CropActionChip(
                        label = "起点: ${playerZoomOriginLabel(crop.origin)}",
                        leadingContent = { contentColor ->
                            ZoomOriginIndicator(
                                origin = crop.origin,
                                activeColor = contentColor,
                                inactiveColor = contentColor.copy(alpha = 0.28f)
                            )
                        },
                        onClick = {
                            crop.origin = nextPlayerZoomOrigin(crop.origin)
                        },
                        modifier = Modifier
                            .height(44.dp)
                            .testTag(CROP_ORIGIN_BUTTON_TEST_TAG)
                    )

                    // ダイレクト調整開始 (初期フォーカス)
                    CropActionChip(
                        label = "十字キーで位置調整",
                        icon = Icons.Default.OpenWith,
                        emphasized = true,
                        onClick = { crop.mode = PlayerCropMode.DIRECT_ADJUST },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(menuFocusRequester)
                            .testTag(CROP_DIRECT_ADJUST_BUTTON_TEST_TAG)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // --- 2行目: クイック倍率プリセット + ユーティリティ / 完了 ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CROP_ZOOM_PRESETS.forEach { preset ->
                        val isActive = crop.zoomPercent.toInt() == preset
                        CropActionChip(
                            label = "${preset}%",
                            selected = isActive,
                            onClick = { crop.zoomPercent = preset.toFloat() },
                            modifier = Modifier.height(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    CropActionChip(
                        label = "リセット",
                        icon = Icons.Default.RestartAlt,
                        onClick = { crop.reset() },
                        modifier = Modifier
                            .height(38.dp)
                            .testTag(CROP_RESET_BUTTON_TEST_TAG)
                    )

                    CropActionChip(
                        label = "解除",
                        icon = Icons.Default.Close,
                        onClick = {
                            crop.isEnabled = false
                            crop.reset()
                            onClose()
                        },
                        modifier = Modifier
                            .height(38.dp)
                            .testTag(CROP_DISABLE_BUTTON_TEST_TAG)
                    )

                    CropActionChip(
                        label = "完了",
                        icon = Icons.Default.Check,
                        emphasized = true,
                        onClick = onClose,
                        modifier = Modifier
                            .height(38.dp)
                            .testTag(CROP_DONE_BUTTON_TEST_TAG)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectAdjustPillHud(
    crop: PlayerCropState,
    inputProfile: PlayerCropInputProfile,
) {
    val colors = KomorebiTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .testTag(CROP_DIRECT_ADJUST_HUD_TEST_TAG)
            .background(
                color = colors.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(999.dp)
            )
            .border(
                width = 1.dp,
                color = colors.accent.copy(alpha = 0.55f),
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Crop,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "${crop.zoomPercent.toInt()}%",
            color = colors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "X ${crop.xPercent.toInt()}% · Y ${crop.yPercent.toInt()}%",
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(16.dp)
                .background(colors.textPrimary.copy(alpha = 0.16f))
        )

        KeyHintBadge(key = "十字", label = "移動")
        if (inputProfile.centerCyclesZoom) {
            KeyHintBadge(key = "決定", label = "倍率切替")
        } else if (inputProfile.supportsZoomKeys) {
            KeyHintBadge(key = "早送り/巻戻し", label = "倍率")
            KeyHintBadge(key = "決定", label = "メニュー")
        }
        KeyHintBadge(key = "戻る", label = "メニュー")
    }
}

@Composable
private fun KeyHintBadge(key: String, label: String) {
    val colors = KomorebiTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = colors.textPrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(5.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = key,
                color = colors.textPrimary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = label,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
        )
    }
}

@Composable
private fun CropStatusBadge(text: String, highlighted: Boolean) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .background(
                color = if (highlighted) {
                    colors.accent.copy(alpha = 0.16f)
                } else {
                    colors.textPrimary.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = if (highlighted) colors.accent else colors.textSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CropActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leadingContent: (@Composable (Color) -> Unit)? = null,
    emphasized: Boolean = false,
    selected: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White
    val containerColor = when {
        emphasized -> colors.accent.copy(alpha = 0.18f)
        selected -> colors.accent.copy(alpha = 0.14f)
        else -> colors.textPrimary.copy(alpha = 0.08f)
    }
    val contentColor = when {
        emphasized || selected -> colors.accent
        else -> colors.textPrimary
    }
    val unfocusedBorderColor = when {
        emphasized || selected -> colors.accent.copy(alpha = 0.45f)
        else -> colors.textPrimary.copy(alpha = 0.10f)
    }

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 0.98f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = if (emphasized) colors.accent else colors.textPrimary,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = colors.textPrimary.copy(alpha = 0.85f),
            pressedContentColor = focusedContentColor,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, unfocusedBorderColor),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, colors.textPrimary),
                shape = RoundedCornerShape(10.dp)
            ),
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingContent != null) {
                leadingContent(LocalContentColor.current)
                Spacer(modifier = Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CropIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f, pressedScale = 0.95f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.10f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = colors.textPrimary.copy(alpha = 0.85f),
            pressedContentColor = focusedContentColor,
        ),
        modifier = modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ZoomOriginIndicator(
    origin: PlayerZoomOrigin,
    activeColor: Color,
    inactiveColor: Color,
) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val gap = 2.dp.toPx()
        val cell = (size.minDimension - gap) / 2f
        val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        val stroke = Stroke(width = 1.dp.toPx())

        val corners = listOf(
            PlayerZoomOrigin.TopLeft to Offset(0f, 0f),
            PlayerZoomOrigin.TopRight to Offset(cell + gap, 0f),
            PlayerZoomOrigin.BottomLeft to Offset(0f, cell + gap),
            PlayerZoomOrigin.BottomRight to Offset(cell + gap, cell + gap),
        )
        corners.forEach { (corner, topLeft) ->
            if (corner == origin) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = topLeft,
                    size = Size(cell, cell),
                    cornerRadius = radius
                )
            } else {
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = topLeft,
                    size = Size(cell, cell),
                    cornerRadius = radius,
                    style = stroke
                )
            }
        }
    }
}

private fun nextPlayerZoomOrigin(origin: PlayerZoomOrigin): PlayerZoomOrigin = when (origin) {
    PlayerZoomOrigin.TopLeft -> PlayerZoomOrigin.TopRight
    PlayerZoomOrigin.TopRight -> PlayerZoomOrigin.BottomRight
    PlayerZoomOrigin.BottomRight -> PlayerZoomOrigin.BottomLeft
    PlayerZoomOrigin.BottomLeft -> PlayerZoomOrigin.TopLeft
}

/** 拡大起点の日本語ラベル (ライブ/録画共通) */
fun playerZoomOriginLabel(origin: PlayerZoomOrigin): String = when (origin) {
    PlayerZoomOrigin.TopLeft -> "左上"
    PlayerZoomOrigin.TopRight -> "右上"
    PlayerZoomOrigin.BottomLeft -> "左下"
    PlayerZoomOrigin.BottomRight -> "右下"
}
