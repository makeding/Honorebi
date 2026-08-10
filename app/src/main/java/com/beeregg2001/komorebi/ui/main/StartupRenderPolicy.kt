package com.beeregg2001.komorebi.ui.main

/**
 * Keeps the splash lightweight: readiness of the first Home tab is a focus/UI concern,
 * not a prerequisite for taking the splash off screen.  Otherwise the Home hierarchy is
 * composed behind an animated loading screen and both compete for the first frames.
 */
internal data class StartupRenderPlan(
    val showLoading: Boolean,
    val showMainContent: Boolean,
    val showSeasonalDecor: Boolean,
)

internal fun startupRenderPlan(
    isSystemReady: Boolean,
    isSettingsInitialized: Boolean,
    hasConnectionError: Boolean,
    isSyncingInitial: Boolean,
    isPlaybackActive: Boolean,
): StartupRenderPlan {
    // Do not use MainRootState.isUiReady here. It is reported by HomeContents only after
    // that hierarchy has been composed, so using it would put the splash and HomeContents
    // on screen at the same time (or create a circular readiness dependency).
    val showLoading = isSettingsInitialized && !hasConnectionError &&
            (!isSystemReady || isSyncingInitial)
    val showMainContent = isSystemReady && isSettingsInitialized &&
            !hasConnectionError && !isSyncingInitial && !showLoading

    return StartupRenderPlan(
        showLoading = showLoading,
        showMainContent = showMainContent,
        showSeasonalDecor = !isPlaybackActive && !showLoading,
    )
}
