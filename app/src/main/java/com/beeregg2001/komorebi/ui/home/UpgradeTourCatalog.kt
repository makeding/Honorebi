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

    fun tourForVersion(versionCode: Int): List<UpgradeTourStep> = when (versionCode) {
        VERSION_CODE_1_2_0 -> listOf(
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.NAV_RAIL,
                shape = UpgradeSpotlightShape.CIRCLE,
                badge = "Honorebi 1.2.0 • 新ナビゲーション",
                title = "左サイドバーに全面刷新",
                description = "タブの切り替えや設定へのアクセスが左側の格子メニューに集約されました。コンテンツ操作中は自動で折りたたまれ、左キーでいつでも展開できます。",
                placement = UpgradeCalloutPlacement.RIGHT_OF_TARGET,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.SYSTEM_STRIP,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.0 • システムバー",
                title = "右上システムバーで素早く操作",
                description = "時計（クリックでスクリーンセーバー）、外部入力切替（HDMI / TIF）、ネットワーク設定、システム設定へすぐアクセスできます。スクロール時は自動でスリム化します。",
                placement = UpgradeCalloutPlacement.BELOW_RIGHT,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.CONTENT_STAGE,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.0 • 起動時フォーカス & UI刷新",
                title = "起動時フォーカスと各タブを刷新",
                description = "「設定 ＞ 画面」から、起動時に左サイドバーと右側のコンテンツカードのどちらへ最初にフォーカスを当てるか選べるようになりました。ホーム・ビデオ・番組表・予約の各画面も全面刷新しています。",
                placement = UpgradeCalloutPlacement.BOTTOM_CENTER,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.DEEP_TV_SETTING,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.0 • 注目新機能",
                title = "ディープTV統合（HDMI / TIF入力）",
                description = "Android TV の外部入力や Watch Next 連携を Honorebi に統合しました。右上バーからの入力切替、弱信号時の自動再接続、無信号時の演出、空入力の非表示まで設定できます。",
                placement = UpgradeCalloutPlacement.BOTTOM_CENTER,
                actionLabel = "この設定を変更",
                actionTarget = UpgradeTourAction.OPEN_DEEP_TV_SETTINGS,
            ),
            UpgradeTourStep(
                targetId = UpgradeTourTargetId.COMMENT_ENGINE_SETTING,
                shape = UpgradeSpotlightShape.ROUNDED_RECT,
                badge = "Honorebi 1.2.0 • 弾幕 & プレイヤー",
                title = "OpenGL ES 3.0 弾幕エンジン（Beta）",
                description = "コメント描画エンジンをここから切り替えられます。さらにチャプター解析強化、プレイヤー下キー操作の高速化、libaribcaption のセキュリティ修正（CVE-2026-112284）も実装しました。",
                placement = UpgradeCalloutPlacement.BELOW_RIGHT,
                actionLabel = "この設定を変更",
                actionTarget = UpgradeTourAction.OPEN_COMMENT_ENGINE_SETTINGS,
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
