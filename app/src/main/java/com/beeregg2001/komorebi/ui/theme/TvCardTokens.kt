package com.beeregg2001.komorebi.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border

// ============================================================================
// TV カード / 行 / レイアウト / OSD 共有ビジュアルトークン
// ============================================================================

/** 角丸トークン: 16:9 グリッドカード (録画カード / SMB グリッド / アプリバナー) */
val TvCardRadiusGrid = 12.dp

/** 角丸トークン: シリーズグリッドカード (番組グリッドカードに合わせる) */
val TvCardRadiusSeries = 12.dp

/** 角丸トークン: リスト行と横長カード (録画リスト行 / 予約カード / 設定行) */
val TvCardRadiusListRow = 10.dp

/** フォーカススケール: 16:9 グリッド系カード */
const val TvCardFocusScaleGrid = 1.045f

/** フォーカススケール: ワイドカード (ライブ EPG / ホームショートカット) */
const val TvCardFocusScaleWideCard = 1.03f

/** フォーカススケール: リスト行 / 設定行 / OSD タイル */
const val TvCardFocusScaleListRow = 1.02f

/** フォーカススケール: システムアイコンボタン (隣接要素の配置不変) */
const val TvCardFocusScaleSystemIcon = 1.0f

/** フォーカス時のアクセント枠の幅 (行 / ピル / レール / OSD タイル / システムアイコン) */
val TvCardFocusBorderWidth = 2.dp

/** フォーカス時のアクセント枠の幅 (16:9 グリッド / ワイドカード / クイック再開ヒーロー) */
val TvCardFocusBorderWidthCard = 2.5.dp

/** サムネイルスクリム: グリッドカードのフォーカス時 (Below-the-Card 設計のため 0) */
const val TvCardScrimAlphaFocused = 0f

/** サムネイルスクリム: グリッドカードの非フォーカス時 (余計な全面アルファ合成を排除) */
const val TvCardScrimAlphaUnfocused = 0f

// ============================================================================
// ナビゲーションレール / トップシステムストリップ / オーバースキャン安全余白
// ============================================================================

/** 左ナビゲーションレール (折りたたみ時) */
val TvRailCollapsedWidth = 68.dp

/** 左ナビゲーションレール (展開時・オーバーレイ) */
val TvRailExpandedWidth = 196.dp

/** レール / サイドバー選択中インジケータ (左端ピル) */
val TvRailSelectedIndicatorWidth = 3.dp
val TvRailSelectedIndicatorVerticalInset = 8.dp

/** 右上システムユーティリティストリップ (48dp 行 + 8dp 上余白) */
val TvTopStripHeight = 56.dp

/** レール併用時のコンテンツ内側パディング (左 68+28=96dp / 右 32dp) */
val TvContentPaddingStart = 28.dp
val TvContentPaddingEnd = 32.dp
val TvContentPaddingTop = 16.dp
val TvContentPaddingBottom = 24.dp

/** 単独フルスクリーン画面 (設定 / プレイヤー OSD / ダイアログ) の安全余白 */
val TvScreenPaddingHorizontal = 32.dp
val TvScreenPaddingVertical = 24.dp

// ============================================================================
// プレイヤー OSD 固定ダークサーフェス (映像上に重なるためテーマ非依存)
// ============================================================================

/** OSD タイル / パネル基本面 (フラット半透明ダーク・ゼロブラー) */
val OsdSurface = Color(0xD91E2029)

/** OSD フォーカス面 */
val OsdSurfaceFocused = Color(0xFF282C3D)

/** Signal HUD / オーバーレイ情報面 */
val OsdHudSurface = Color(0xEB0D0E14)

/** OSD 非フォーカス時の微細枠線 */
val OsdBorderSubtle = Color.White.copy(alpha = 0.12f)

// ============================================================================
// ステータス / マーキー
// ============================================================================

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

    /** ライブ 3 列 EPG / ホーム 2x2 ワイドカード */
    WIDE_CARD,

    /** リスト行 / 設定行 / OSD タイル */
    LIST_ROW,

    /** トップバーのシステムアイコンボタン (拡大なし) */
    SYSTEM_ICON,
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
    /** 非フォーカス時の微細枠線 */
    val restBorder: Border = Border.None,
)

/**
 * フォーカス演出を 1 箇所に集約した共有ヘルパー。
 * - GRID        : スケール 1.045 / 枠 2.5dp / スクリムなし
 * - WIDE_CARD   : スケール 1.03  / 枠 2.5dp / スクリムなし
 * - LIST_ROW    : スケール 1.02  / 枠 2.0dp / スクリムなし
 * - SYSTEM_ICON : スケール 1.00  / 枠 2.0dp / スクリムなし
 */
@Composable
fun tvCardFocus(family: TvCardFamily): TvCardFocusSpec {
    val colors = KomorebiTheme.colors
    val policy = when (family) {
        TvCardFamily.GRID -> FocusPolicy(
            focusedScale = TvCardFocusScaleGrid,
            borderWidth = TvCardFocusBorderWidthCard,
            scrimAlphaFocused = TvCardScrimAlphaFocused,
            scrimAlphaUnfocused = TvCardScrimAlphaUnfocused,
            radius = TvCardRadiusGrid,
        )
        TvCardFamily.WIDE_CARD -> FocusPolicy(
            focusedScale = TvCardFocusScaleWideCard,
            borderWidth = TvCardFocusBorderWidthCard,
            scrimAlphaFocused = 0f,
            scrimAlphaUnfocused = 0f,
            radius = TvCardRadiusGrid,
        )
        TvCardFamily.LIST_ROW -> FocusPolicy(
            focusedScale = TvCardFocusScaleListRow,
            borderWidth = TvCardFocusBorderWidth,
            scrimAlphaFocused = 0f,
            scrimAlphaUnfocused = 0f,
            radius = TvCardRadiusListRow,
        )
        TvCardFamily.SYSTEM_ICON -> FocusPolicy(
            focusedScale = TvCardFocusScaleSystemIcon,
            borderWidth = TvCardFocusBorderWidth,
            scrimAlphaFocused = 0f,
            scrimAlphaUnfocused = 0f,
            radius = TvCardRadiusGrid,
        )
    }
    val shape = RoundedCornerShape(policy.radius)
    return TvCardFocusSpec(
        focusedScale = policy.focusedScale,
        focusedBorder = Border(
            border = BorderStroke(policy.borderWidth, colors.accent),
            shape = shape,
        ),
        scrimAlphaFocused = policy.scrimAlphaFocused,
        scrimAlphaUnfocused = policy.scrimAlphaUnfocused,
        restBorder = Border(
            border = BorderStroke(1.dp, colors.borderSubtle),
            shape = shape,
        ),
    )
}

private data class FocusPolicy(
    val focusedScale: Float,
    val borderWidth: Dp,
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
