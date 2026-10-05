package com.beeregg2001.komorebi.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border

// ============================================================================
// TV カード / 行 共有ビジュアルトークン
// 16:9 カード・リスト行・ステータス表示の見た目を 1 箇所で管理する。
// ============================================================================

/** 角丸トークン: 16:9 グリッドカード (録画カード / SMB グリッド) */
val TvCardRadiusGrid = 12.dp

/** 角丸トークン: シリーズグリッドカード (番組グリッドカードに合わせる) */
val TvCardRadiusSeries = 12.dp

/** 角丸トークン: リスト行と横長カード (録画リスト行 / 予約カード / キーワード条件カード) */
val TvCardRadiusListRow = 8.dp

/** フォーカススケール: グリッド系カード */
const val TvCardFocusScaleGrid = 1.05f

/** フォーカススケール: リスト行 */
const val TvCardFocusScaleListRow = 1.02f

/** フォーカス時のアクセント枠の幅 (全カード / 行共通) */
val TvCardFocusBorderWidth = 2.dp

/** サムネイルスクリム: グリッドカードのフォーカス時 (文字可読性のため薄く残す) */
const val TvCardScrimAlphaFocused = 0.1f

/** サムネイルスクリム: グリッドカードの非フォーカス時 (暗くしてフォーカスを区別する) */
const val TvCardScrimAlphaUnfocused = 0.4f

/** ステータス色: 録画中 (全画面共通。EpgDrawer などの画面と値を合わせる) */
val StatusRecordingColor = Color(0xFFE53935)

/** ステータス色: メタデータ解析中 */
val StatusAnalyzingColor = Color(0xFFFB8C00)

/** ステータス色: 一部録画などの警告 */
val StatusWarningColor = Color(0xFFFFCA28)

/** マーキー: 繰り返し回数 (実質無限) */
const val TvCardMarqueeIterations = Int.MAX_VALUE

/** マーキー: 1 周後の停止時間 (ms) */
const val TvCardMarqueeRepeatDelayMillis = 1000

/** カードの所属分類 (フォーカス演出を分ける単位) */
enum class TvCardFamily {
    /** 16:9 サムネイル付きグリッドカード */
    GRID,

    /** リスト行 / 横長カード */
    LIST_ROW,
}

/**
 * フォーカス時の演出仕様。
 * [tvCardFocus] 経由でのみ取得すること (値の定義もそちらに集約)。
 */
@Immutable
data class TvCardFocusSpec(
    /** フォーカス時の拡大率 */
    val focusedScale: Float,
    /** フォーカス時のアクセント枠 */
    val focusedBorder: Border,
    /** サムネイルスクリムの濃度 (フォーカス時) */
    val scrimAlphaFocused: Float,
    /** サムネイルスクリムの濃度 (非フォーカス時) */
    val scrimAlphaUnfocused: Float,
)

/**
 * フォーカス演出を 1 箇所に集約した共有ヘルパー。
 * - GRID    : スケール 1.05 / サムネイルに常設スクリム (フォーカス=薄 / 非フォーカス=濃)
 * - LIST_ROW: スケール 1.02 / スクリムなし
 * 枠はどちらも [TvCardFocusBorderWidth] のアクセント色。
 */
@Composable
fun tvCardFocus(family: TvCardFamily): TvCardFocusSpec {
    val colors = KomorebiTheme.colors
    val (focusedScale, scrimAlphaFocused, scrimAlphaUnfocused, radius) = when (family) {
        TvCardFamily.GRID -> FocusPolicy(
            focusedScale = TvCardFocusScaleGrid,
            scrimAlphaFocused = TvCardScrimAlphaFocused,
            scrimAlphaUnfocused = TvCardScrimAlphaUnfocused,
            radius = TvCardRadiusGrid,
        )
        TvCardFamily.LIST_ROW -> FocusPolicy(
            focusedScale = TvCardFocusScaleListRow,
            scrimAlphaFocused = 0f,
            scrimAlphaUnfocused = 0f,
            radius = TvCardRadiusListRow,
        )
    }
    return TvCardFocusSpec(
        focusedScale = focusedScale,
        focusedBorder = Border(
            border = BorderStroke(TvCardFocusBorderWidth, colors.accent),
            shape = RoundedCornerShape(radius),
        ),
        scrimAlphaFocused = scrimAlphaFocused,
        scrimAlphaUnfocused = scrimAlphaUnfocused,
    )
}

private data class FocusPolicy(
    val focusedScale: Float,
    val scrimAlphaFocused: Float,
    val scrimAlphaUnfocused: Float,
    val radius: Dp,
)

/**
 * マーキー表示を統一設定で適用する。
 * [active] (フォーカス中など) が true のときのみスクロールする。
 */
fun Modifier.tvCardMarquee(active: Boolean): Modifier =
    if (active) basicMarquee(
        iterations = TvCardMarqueeIterations,
        repeatDelayMillis = TvCardMarqueeRepeatDelayMillis,
    ) else this

private const val FOCUS_ANIMATION_DURATION_MS = 200

@Composable
fun Modifier.tvFocusAnimation(
    isFocused: Boolean,
    focusedScale: Float = 1.05f,
    animationDuration: Int = FOCUS_ANIMATION_DURATION_MS,
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1.0f,
        animationSpec = tween(animationDuration),
        label = "focusScale"
    )
    val borderAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(animationDuration),
        label = "borderAlpha"
    )
    val colors = KomorebiTheme.colors

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(
            width = if (isFocused) TvCardFocusBorderWidth else 0.dp,
            color = colors.accent.copy(alpha = borderAlpha),
            shape = RoundedCornerShape(TvCardRadiusGrid)
        )
}
