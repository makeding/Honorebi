package com.beeregg2001.komorebi.ui.home

/**
 * Typed identity and lifecycle capabilities for every top-level launcher tab.
 *
 * Replacing raw string labels and positional arrays with [LauncherTab] keeps focus requesters,
 * side effects, and return-focus routing aligned even when the Apps tab is hidden or shown.
 */
enum class LauncherTab(
    val title: String,
    val requiresLivePolling: Boolean = false,
    val requiresEpgData: Boolean = false,
    val ownsExternalReturnFocus: Boolean = false,
    val ownsOverlayReturnFocus: Boolean = false,
    val refreshesHomeDataOnActivate: Boolean = false,
) {
    HOME(
        title = "ホーム",
        requiresLivePolling = true,
        refreshesHomeDataOnActivate = true,
    ),
    LIVE(
        title = "ライブ",
        requiresLivePolling = true,
        ownsExternalReturnFocus = true,
    ),
    APPS(
        title = "アプリ",
    ),
    ON_AIR(
        title = "放送中",
        ownsExternalReturnFocus = true,
    ),
    VIDEO(
        title = "ビデオ",
        ownsExternalReturnFocus = true,
    ),
    EPG(
        title = "番組表",
        requiresEpgData = true,
        ownsOverlayReturnFocus = true,
    ),
    RESERVE(
        title = "録画予約",
        ownsOverlayReturnFocus = true,
    );

    companion object {
        fun fromTitle(title: String?): LauncherTab? = entries.firstOrNull { it.title == title }

        fun visibleTabs(hideAppsTab: Boolean, tif: Boolean = false): List<LauncherTab> =
            entries.filterNot { (hideAppsTab && it == APPS) || (tif && (it == RESERVE || it == ON_AIR)) }
    }
}

/**
 * Explicit lifecycle phase of the active launcher tab session.
 */
enum class LauncherTabPhase {
    /** Top-bar selection moved; coalescing rapid D-pad scrubs before mounting new tab content. */
    COALESCING,

    /** New tab content is mounted and composing; waiting for its first-item readiness signal. */
    MOUNTING,

    /** Active tab content is laid out and interactive; DOWN entry and tab side effects are live. */
    ACTIVE,

    /** Launcher is obscured by a full-screen overlay/player or Activity pause; side effects pause. */
    SUSPENDED,
}

/**
 * Spatial direction of a tab transition, used for directional content motion.
 */
enum class TabTransitionDirection(val sign: Int) {
    NONE(0),
    FORWARD(1),
    BACKWARD(-1),
}

/**
 * Observable lifecycle events emitted by [LauncherTabController].
 */
enum class LauncherTabLifecycleEvent {
    ENTER,
    READY,
    SUSPEND,
    RESUME,
    EXIT,
}

/**
 * Immutable render key pairing a tab with its monotonic mount generation.
 */
data class TabContentSnapshot(
    val tab: LauncherTab,
    val generation: Long,
)

/**
 * Distinct triggers that require deterministic focus restoration inside the launcher.
 */
enum class FocusReturnTrigger {
    LAUNCHER_HOME,
    ACTIVITY_RESUME,
    OVERLAY_DISMISS,
    PLAYER_RETURN,
    AI_RETURN,
}

/**
 * Resolved focus action for a [FocusReturnTrigger].
 */
sealed interface TabFocusAction {
    data class FocusTab(val tab: LauncherTab, val tabIndex: Int) : TabFocusAction
    data class FocusContentTop(val tab: LauncherTab, val tabIndex: Int) : TabFocusAction
    data class FocusItem(
        val tab: LauncherTab,
        val tabIndex: Int,
        val section: String,
        val itemId: String,
    ) : TabFocusAction

    data class DelegateToTabContent(val consumeImmediately: Boolean = false) : TabFocusAction
    data object NoOp : TabFocusAction
}

/**
 * Single source of truth for launcher return-focus routing across all lifecycle triggers.
 */
internal fun resolveTabReturnFocus(
    trigger: FocusReturnTrigger,
    tab: LauncherTab,
    tabIndex: Int,
    lastClickedSection: String?,
    lastClickedItemId: String?,
): TabFocusAction = when (trigger) {
    FocusReturnTrigger.LAUNCHER_HOME -> TabFocusAction.FocusTab(tab, tabIndex)

    FocusReturnTrigger.ACTIVITY_RESUME -> {
        if (tab == LauncherTab.APPS && lastClickedSection == "apps" && lastClickedItemId != null) {
            TabFocusAction.FocusItem(tab, tabIndex, "apps", lastClickedItemId)
        } else {
            TabFocusAction.FocusTab(tab, tabIndex)
        }
    }

    FocusReturnTrigger.AI_RETURN -> when (tab) {
        LauncherTab.HOME -> {
            if (lastClickedSection != null && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, lastClickedSection, lastClickedItemId)
            } else {
                TabFocusAction.FocusContentTop(tab, tabIndex)
            }
        }

        LauncherTab.APPS -> {
            if (lastClickedSection == "apps" && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, "apps", lastClickedItemId)
            } else {
                TabFocusAction.FocusTab(tab, tabIndex)
            }
        }

        LauncherTab.LIVE, LauncherTab.VIDEO -> TabFocusAction.DelegateToTabContent()

        LauncherTab.ON_AIR, LauncherTab.EPG, LauncherTab.RESERVE -> TabFocusAction.FocusTab(tab, tabIndex)
    }

    FocusReturnTrigger.PLAYER_RETURN -> when (tab) {
        LauncherTab.LIVE, LauncherTab.VIDEO, LauncherTab.ON_AIR -> TabFocusAction.DelegateToTabContent()

        LauncherTab.HOME -> {
            if (lastClickedSection != null && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, lastClickedSection, lastClickedItemId)
            } else {
                TabFocusAction.FocusTab(tab, tabIndex)
            }
        }

        LauncherTab.APPS -> {
            if (lastClickedSection == "apps" && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, "apps", lastClickedItemId)
            } else {
                TabFocusAction.FocusTab(tab, tabIndex)
            }
        }

        LauncherTab.EPG -> TabFocusAction.DelegateToTabContent(consumeImmediately = true)

        LauncherTab.RESERVE -> TabFocusAction.DelegateToTabContent(consumeImmediately = false)
    }

    FocusReturnTrigger.OVERLAY_DISMISS -> when (tab) {
        LauncherTab.EPG, LauncherTab.RESERVE -> TabFocusAction.NoOp

        LauncherTab.HOME -> {
            if (lastClickedSection != null && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, lastClickedSection, lastClickedItemId)
            } else {
                TabFocusAction.FocusTab(tab, tabIndex)
            }
        }

        LauncherTab.APPS -> {
            if (lastClickedSection == "apps" && lastClickedItemId != null) {
                TabFocusAction.FocusItem(tab, tabIndex, "apps", lastClickedItemId)
            } else {
                TabFocusAction.FocusTab(tab, tabIndex)
            }
        }

        LauncherTab.LIVE, LauncherTab.ON_AIR -> TabFocusAction.FocusTab(tab, tabIndex)

        LauncherTab.VIDEO -> TabFocusAction.DelegateToTabContent()
    }
}
