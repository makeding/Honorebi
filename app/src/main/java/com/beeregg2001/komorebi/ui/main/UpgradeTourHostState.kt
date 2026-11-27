package com.beeregg2001.komorebi.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beeregg2001.komorebi.ui.home.UpgradeTourAction
import com.beeregg2001.komorebi.ui.home.UpgradeTourStep
import com.beeregg2001.komorebi.ui.home.UpgradeTourTargetId
import com.beeregg2001.komorebi.ui.home.shouldActivateUpgradeTourOverlay
import com.beeregg2001.komorebi.ui.home.shouldShowUpgradeLandingTour
import com.beeregg2001.komorebi.ui.setting.SettingsCategory
import com.beeregg2001.komorebi.ui.setting.SettingsDestination
import com.beeregg2001.komorebi.ui.setting.SettingsFocusTarget

@Stable
internal class UpgradeTourHostState(
    val steps: List<UpgradeTourStep>,
    val introducedVersionCode: Int?,
) {
    var isDismissed by mutableStateOf(false)
    var isManualOpen by mutableStateOf(false)
    var isControllingSettings by mutableStateOf(false)

    fun shouldAutoShow(
        lastSeenVersionCode: Int?,
        currentVersionCode: Int,
        isSettingsInitialized: Boolean,
    ): Boolean = !isDismissed && shouldShowUpgradeLandingTour(
        lastSeenVersionCode = lastSeenVersionCode,
        currentVersionCode = currentVersionCode,
        isSettingsInitialized = isSettingsInitialized,
        hasTourForCurrentVersion = steps.isNotEmpty(),
        tourIntroducedVersionCode = introducedVersionCode ?: currentVersionCode,
    )

    fun isActive(
        showMainContent: Boolean,
        isPlaybackActive: Boolean,
        isSettingsOpen: Boolean,
        hasBlockingOverlay: Boolean,
        shouldAutoShow: Boolean,
    ): Boolean = shouldActivateUpgradeTourOverlay(
        hasTourSteps = steps.isNotEmpty(),
        showMainContent = showMainContent,
        isPlaybackActive = isPlaybackActive,
        isSettingsOpen = isSettingsOpen,
        isUpgradeTourControllingSettings = isControllingSettings,
        hasBlockingOverlay = hasBlockingOverlay,
        isManualUpgradeTourOpen = isManualOpen,
        shouldAutoShowUpgradeTour = shouldAutoShow,
    )

    fun openManually(
        onCloseSettings: () -> Unit,
        onUnavailable: () -> Unit,
    ): Boolean {
        if (steps.isEmpty()) {
            onUnavailable()
            return false
        }
        onCloseSettings()
        isManualOpen = true
        return true
    }

    fun handleStepChanged(stepIndex: Int, state: MainRootState) {
        when (steps.getOrNull(stepIndex)?.targetId) {
            UpgradeTourTargetId.DEEP_TV_SETTING -> {
                isControllingSettings = true
                state.openSettings(SettingsDestination.Category(SettingsCategory.DEEP_TV))
            }
            UpgradeTourTargetId.COMMENT_ENGINE_SETTING -> {
                isControllingSettings = true
                state.openSettings(SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE))
            }
            else -> {
                if (isControllingSettings) {
                    isControllingSettings = false
                    state.closeSettings()
                }
            }
        }
    }

    fun handleStepAction(
        action: UpgradeTourAction,
        state: MainRootState,
        onMarkSeen: () -> Unit,
    ) {
        isControllingSettings = false
        isManualOpen = false
        isDismissed = true
        onMarkSeen()
        when (action) {
            UpgradeTourAction.OPEN_COMMENT_ENGINE_SETTINGS -> {
                state.openSettings(SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE))
            }
            UpgradeTourAction.OPEN_DEEP_TV_SETTINGS -> {
                state.openSettings(SettingsDestination.Category(SettingsCategory.DEEP_TV))
            }
        }
    }

    fun finish(state: MainRootState, onMarkSeen: () -> Unit) {
        val wasControllingSettings = isControllingSettings
        isControllingSettings = false
        isManualOpen = false
        isDismissed = true
        onMarkSeen()
        if (wasControllingSettings) {
            state.closeSettings()
        }
    }

    fun dismissIfActive(
        shouldAutoShow: Boolean,
        onMarkSeen: () -> Unit,
    ): Boolean {
        if (!isControllingSettings && !isManualOpen && !shouldAutoShow) return false
        isControllingSettings = false
        isManualOpen = false
        isDismissed = true
        onMarkSeen()
        return true
    }

    fun dismissForHomeIntent(
        shouldAutoShow: Boolean,
        onMarkSeen: () -> Unit,
    ): Boolean = dismissIfActive(shouldAutoShow, onMarkSeen)
}
