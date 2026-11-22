package com.beeregg2001.komorebi.ui.home

enum class UpgradeTourTargetId {
    NAV_RAIL,
    SYSTEM_STRIP,
    CONTENT_STAGE,
    DEEP_TV_SETTING,
    COMMENT_ENGINE_SETTING,
}

enum class UpgradeSpotlightShape {
    ROUNDED_RECT,
    CIRCLE,
}

enum class UpgradeCalloutPlacement {
    RIGHT_OF_TARGET,
    BELOW_RIGHT,
    BOTTOM_CENTER,
    BOTTOM_LEFT_BESIDE_RAIL,
    LEFT_SIDE_PANEL,
}

enum class UpgradeTourAction {
    OPEN_COMMENT_ENGINE_SETTINGS,
    OPEN_DEEP_TV_SETTINGS,
}

data class UpgradeTourStep(
    val targetId: UpgradeTourTargetId,
    val shape: UpgradeSpotlightShape,
    val badge: String,
    val title: String,
    val description: String,
    val placement: UpgradeCalloutPlacement,
    val actionLabel: String? = null,
    val actionTarget: UpgradeTourAction? = null,
)

object UpgradeTourCatalog {
    const val VERSION_CODE_1_2_0 = 35
    const val VERSION_CODE_1_2_1 = 36

    fun tourForVersion(versionCode: Int): List<UpgradeTourStep> = when (versionCode) {
        VERSION_CODE_1_2_0, VERSION_CODE_1_2_1 -> listOf(
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.NAV_RAIL,
                shape = UpgradeSpotlightShape.CIRCLE,
                badge = "Honorebi 1.2.1 • 新ナビゲーション",
                title = "左サイドバーに集約",
                description = "タブの切り替えや設定へのアクセスを左サイドバーに集約しました。コンテンツ操作中は自動で折りたたまれ、左キーでいつでも展開できます。",
                placement = UpgradeCalloutPlacement.RIGHT_OF_TARGET,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.CONTENT_STAGE,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.1 • 起動時フォーカス & 新デザイン",
                title = "起動時フォーカスと各画面を一新",
                description = "「設定 ＞ 基本設定」から、起動時に左サイドバーとコンテンツ先頭のどちらへ最初にフォーカスを当てるか選べるようになりました。ホーム・ビデオ・番組表・予約の各画面デザインも一新しています。",
                placement = UpgradeCalloutPlacement.BOTTOM_CENTER,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.SYSTEM_STRIP,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.1 • システムバー",
                title = "右上システムバーで素早く操作",
                description = "TV入力（HDMI / チューナー切替）、システム設定、ネットワーク設定、時計（選択でスクリーンセーバー起動）へすぐアクセスできます。スクロール時は自動でスリム化します。",
                placement = UpgradeCalloutPlacement.BELOW_RIGHT,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.DEEP_TV_SETTING,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.1 • 注目新機能",
                title = "Android TV 連携「TV統合」",
                description = "Android TV ホームの「次に観る（Watch Next）」やチャンネル公開、ピクチャー・イン・ピクチャー、リモコンの数字選局に対応しました。HDMI や内蔵チューナーなどのTV入力（TIF）もアプリ内で直接再生・切り替えられます。",
                placement = UpgradeCalloutPlacement.LEFT_SIDE_PANEL,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.COMMENT_ENGINE_SETTING,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.1 • コメント & プレイヤー",
                title = "コメント描画エンジンを強化",
                description = "ハードウェア描画（標準）と、独自実装の OpenGL ES 3.0（Beta）をここから切り替えられます。さらにチャプター解析強化、プレイヤー下キー操作の高速化、libaribcaption のセキュリティ修正（CVE-2026-112284）も実装しました。",
                placement = UpgradeCalloutPlacement.BELOW_RIGHT,
            ),
        )
        else -> emptyList()
    }
}

fun shouldShowUpgradeLandingTour(
    lastSeenVersionCode: Int?,
    currentVersionCode: Int,
    isSettingsInitialized: Boolean,
    hasTourForCurrentVersion: Boolean,
): Boolean {
    if (!hasTourForCurrentVersion) return false
    if (lastSeenVersionCode == null) {
        return isSettingsInitialized
    }
    return lastSeenVersionCode < currentVersionCode
}
