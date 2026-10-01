package com.beeregg2001.komorebi.ui.player

import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * ライブ / 録画 / 二画面の各プレイヤーで共有するオーバーレイ表示の定数。
 * 自動非表示時間・縁余白・時刻表記がスクリーンごとにドリフトしないようにする。
 */

// プレイヤーの操作系オーバーレイを自動で隠すまでの維持時間
const val PLAYER_CONTROLS_AUTO_HIDE_MS = 5000L

// オーバーレイの左右の縁余白 (シーク/進行状況行などの左右の揃い位置)
val PlayerOverlayEdgePaddingHorizontal = 48.dp

// オーバーレイの上下の縁余白
val PlayerOverlayEdgePaddingVertical = 48.dp

// 秒を mm:ss / h:mm:ss 表記に変換する (プレイヤー内で時刻表記を統一する)
fun formatPlayerDurationSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, remainingSeconds)
    }
}

// ミリ秒版 (formatPlayerDurationSeconds への委譲)
fun formatPlayerDurationMillis(millis: Long): String = formatPlayerDurationSeconds(millis / 1000)
