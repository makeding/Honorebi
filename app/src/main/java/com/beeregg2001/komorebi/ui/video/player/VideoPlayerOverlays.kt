package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.player.PlayerLCropOverlay


/* 画面中央に表示される再生・一時停止等のオーバーレイを表示するメソッド */
@Composable
fun PlaybackIndicator(state: IndicatorState?) {
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        if (state != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(0.7f), MaterialTheme.shapes.large)
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(state.icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(state.label, color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * 画面下部に表示される一時的な通知（トースト）
 */
@Composable
fun VideoToast(messageState: Pair<String, Long>?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = messageState != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(0.85f), RoundedCornerShape(32.dp))
                    .border(1.dp, Color.White.copy(0.2f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    text = messageState?.first ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        }
    }
}

@Composable
fun ManualCmSkipPrompt() {
    Box(
        modifier = Modifier
            .padding(end = 48.dp, bottom = 64.dp)
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "決定ボタンでCMをスキップ",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }
}

/**
 * ★ 追加: L字クロップ機能の設定・調整用オーバーレイ (録画視聴版)
 * (パネル本体は ui/player/PlayerCropOverlay.kt のライブ/録画共通実装に委譲)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoLCropOverlay(
    state: VideoPlayerState,
    onClose: () -> Unit
) {
    PlayerLCropOverlay(crop = state.crop, onClose = onClose)
}
