package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay

/**
 * L字クロップの共有オーバーレイ (ライブ/録画とも同一パネル: PlayerCropState のみを参照する)。
 * ライブ側 LCropOverlay / 録画側 VideoLCropOverlay から委譲される。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerLCropOverlay(
    crop: PlayerCropState,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val menuFocusRequester = remember { FocusRequester() }
    val directAdjustFocusRequester = remember { FocusRequester() }

    // フォーカス時のコンテンツカラー判定
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    // モード切り替え時の自動フォーカス制御
    LaunchedEffect(crop.mode) {
        if (crop.mode == PlayerCropMode.MENU) {
            delay(150)
            try {
                menuFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        } else if (crop.mode == PlayerCropMode.DIRECT_ADJUST) {
            delay(150)
            try {
                directAdjustFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    if (crop.mode == PlayerCropMode.DIRECT_ADJUST) {
        // --- ダイレクト調整モード (操作ガイド表示) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 48.dp)
                .focusRequester(directAdjustFocusRequester)
                .focusable(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(colors.background.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .border(1.dp, colors.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Crop, contentDescription = null, tint = colors.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "L字クロップ: ダイレクト調整中",
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("十字キー: 映像を移動", color = colors.textPrimary)
                Text(
                    "決定ボタン: 倍率切り替え (${crop.zoomPercent.toInt()}%)",
                    color = colors.textPrimary
                )
                Text("戻るボタン: メニューへ戻る", color = colors.textSecondary.copy(alpha = 0.7f))
            }
        }
    } else if (crop.mode == PlayerCropMode.MENU) {
        // --- メニューモード (ボトムパネル) ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                                keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                    ) {
                        onClose()
                        true
                    } else false
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.2f to colors.background.copy(alpha = 0.85f),
                            1f to colors.background.copy(alpha = 0.95f)
                        )
                    )
                    .padding(start = 64.dp, end = 64.dp, top = 64.dp, bottom = 48.dp)
            ) {
                // ヘッダー
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Crop,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "L字クロップ設定",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // --- 左側：操作ボタン ---
                    Column(modifier = Modifier.weight(1.2f)) {
                        Button(
                            onClick = { crop.mode = PlayerCropMode.DIRECT_ADJUST },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .focusRequester(menuFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.accent,
                                contentColor = focusedContentColor,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = focusedContentColor
                            )
                        ) {
                            Text("十字キーでダイレクト調整を開始", fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth(0.9f),
                            colors = ButtonDefaults.colors(
                                containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                contentColor = colors.textPrimary,
                                focusedContainerColor = colors.textPrimary,
                                focusedContentColor = focusedContentColor
                            )
                        ) {
                            Text("確定して閉じる", fontWeight = FontWeight.Bold)
                        }
                    }

                    // --- 右側：微調整エリア ---
                    Column(modifier = Modifier.weight(1f)) {
                        Text("微調整", color = colors.accent, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 拡大率の +/- 調整
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "拡大率:",
                                color = colors.textSecondary,
                                modifier = Modifier.width(80.dp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PlayerAdjustmentButton(icon = Icons.Default.Remove) {
                                    crop.zoomPercent = (crop.zoomPercent - 1f).coerceAtLeast(100f)
                                }
                                Text(
                                    text = "${crop.zoomPercent.toInt()}%",
                                    color = colors.textPrimary,
                                    modifier = Modifier.width(60.dp),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                PlayerAdjustmentButton(icon = Icons.Default.Add) {
                                    crop.zoomPercent = (crop.zoomPercent + 1f).coerceAtMost(200f)
                                }
                            }
                        }

                        Text(
                            text = "座標: X ${crop.xPercent.toInt()}% / Y ${crop.yPercent.toInt()}%",
                            color = colors.textSecondary.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 80.dp, top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 拡大起点のトグル
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "拡大起点:",
                                color = colors.textSecondary,
                                modifier = Modifier.width(80.dp)
                            )
                            Button(
                                onClick = {
                                    crop.origin = when (crop.origin) {
                                        PlayerZoomOrigin.TopLeft -> PlayerZoomOrigin.TopRight
                                        PlayerZoomOrigin.TopRight -> PlayerZoomOrigin.BottomRight
                                        PlayerZoomOrigin.BottomRight -> PlayerZoomOrigin.BottomLeft
                                        PlayerZoomOrigin.BottomLeft -> PlayerZoomOrigin.TopLeft
                                    }
                                },
                                colors = ButtonDefaults.colors(
                                    containerColor = colors.textPrimary.copy(alpha = 0.1f),
                                    contentColor = colors.textPrimary,
                                    focusedContainerColor = colors.textPrimary,
                                    focusedContentColor = focusedContentColor
                                )
                            ) {
                                Text(playerZoomOriginLabel(crop.origin), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 拡大率等の数値を調整するための小型円形ボタン (ライブ/録画共通)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerAdjustmentButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = focusedContentColor
        ),
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, null, modifier = Modifier.size(24.dp))
        }
    }
}

/** 拡大起点の日本語ラベル (ライブ/録画共通) */
fun playerZoomOriginLabel(origin: PlayerZoomOrigin): String = when (origin) {
    PlayerZoomOrigin.TopLeft -> "左上"
    PlayerZoomOrigin.TopRight -> "右上"
    PlayerZoomOrigin.BottomLeft -> "左下"
    PlayerZoomOrigin.BottomRight -> "右下"
}
