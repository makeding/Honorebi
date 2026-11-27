package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class SettingFocusRequesters {
    val sidebarRowRequesters: List<FocusRequester> =
        List(SettingsCategory.SIDEBAR_ORDER.size) { FocusRequester() }

    val categoryFocusRequesters: Map<SettingsCategory, FocusRequester> =
        SettingsCategory.SIDEBAR_ORDER.mapIndexed { i, cat -> cat to sidebarRowRequesters[i] }.toMap()

    val homeBackRequester: FocusRequester = FocusRequester()

    // カテゴリごとの項目フォーカスリクエスタ
    val itemFocusRequesters: Map<SettingsCategory, List<FocusRequester>> = mapOf(
        SettingsCategory.GENERAL to List(8) { FocusRequester() },
        SettingsCategory.CONNECTION to List(ConnectionItemIndex.COUNT) { FocusRequester() },
        SettingsCategory.PLAYBACK to List(12) { FocusRequester() },
        SettingsCategory.DATA_BROADCASTING to List(DataBroadcastingItemIndex.COUNT) { FocusRequester() },
        SettingsCategory.RECORDING to List(2) { FocusRequester() },
        SettingsCategory.EPG to List(4) { FocusRequester() },
        SettingsCategory.COMMENT to List(CommentItemIndex.COUNT) { FocusRequester() },
        SettingsCategory.HOME to List(3) { FocusRequester() },
        SettingsCategory.LAUNCHER to List(5) { FocusRequester() },
        SettingsCategory.DISPLAY to List(13) { FocusRequester() },
        SettingsCategory.DEEP_TV to List(DeepTvItemIndex.COUNT) { FocusRequester() },
        SettingsCategory.CACHE to List(7) { FocusRequester() },
        SettingsCategory.APP_INFO to List(AppInfoItemIndex.COUNT) { FocusRequester() },
        SettingsCategory.DEBUG to List(DebugItemIndex.COUNT) { FocusRequester() },
    )

    val seekStepRequester: FocusRequester = FocusRequester()
    val playerClockModeRequester: FocusRequester = FocusRequester()
    val screensaverUpcomingRequester: FocusRequester = FocusRequester()
    val screensaverWebUploadRequester: FocusRequester = FocusRequester()
    val screensaverGalleryRequester: FocusRequester = FocusRequester()
    val screensaverPlayerPreviewRequester: FocusRequester = FocusRequester()

    fun categoryRequester(category: SettingsCategory): FocusRequester =
        categoryFocusRequesters.getValue(category)

    fun itemRequesters(category: SettingsCategory): List<FocusRequester> =
        itemFocusRequesters.getValue(category)

    fun focusTargetRequester(target: SettingsFocusTarget): FocusRequester = when (target) {
        SettingsFocusTarget.CONNECTION_SETUP ->
            itemRequesters(SettingsCategory.CONNECTION)[ConnectionItemIndex.WEB_SETUP]
        SettingsFocusTarget.COMMENT_ENGINE ->
            itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.COMMENT_ENGINE]
        SettingsFocusTarget.APP_INFO_VERSION ->
            itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.VERSION]
        SettingsFocusTarget.APP_INFO_DEVICE_CAPABILITIES ->
            itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.DEVICE_CAPABILITIES]
    }
}

internal enum class SettingBackDisposition {
    RETURN_TO_SIDEBAR,
    EXIT_SETTINGS,
}

internal enum class SettingContentScrollAction {
    NONE,
    SCROLL_TO_TOP,
    SCROLL_TO_BOTTOM_AFTER_FRAME,
}

internal fun resolveSettingContentScrollAction(
    activeCategory: SettingsCategory,
    isUpgradeTourActive: Boolean,
    destination: SettingsDestination,
): SettingContentScrollAction = when {
    isUpgradeTourActive && activeCategory == SettingsCategory.COMMENT ->
        SettingContentScrollAction.SCROLL_TO_BOTTOM_AFTER_FRAME
    isUpgradeTourActive ->
        SettingContentScrollAction.SCROLL_TO_TOP
    destination !is SettingsDestination.Item || activeCategory != destination.category ->
        SettingContentScrollAction.SCROLL_TO_TOP
    else ->
        SettingContentScrollAction.NONE
}

@Stable
internal class SettingNavigationController(
    initialCategory: SettingsCategory = SettingsCategory.GENERAL,
    val focusRequesters: SettingFocusRequesters = SettingFocusRequesters(),
) {
    var activeDialog by mutableStateOf<SettingDialogState>(SettingDialogState.None)
    var showInternalImagePicker by mutableStateOf(false)
    var showScreensaverGallery by mutableStateOf(false)
    var screensaverPreview by mutableStateOf(false)
    var screensaverPlayerPreview by mutableStateOf(false)

    val isDialogOpen: Boolean
        get() = activeDialog !is SettingDialogState.None ||
            showInternalImagePicker ||
            showScreensaverGallery ||
            screensaverPreview ||
            screensaverPlayerPreview

    var selectedCategory by mutableStateOf(initialCategory)
    var activeCategory by mutableStateOf(initialCategory)
    var pendingContentFocusCategory by mutableStateOf<SettingsCategory?>(null)
    var restoreFocusRequester by mutableStateOf<FocusRequester?>(null)
    var restoreCategory by mutableStateOf<SettingsCategory?>(null)
    var isSidebarFocused by mutableStateOf(true)
    var isRestoringFocus by mutableStateOf(false)

    fun onSidebarCategoryFocused(category: SettingsCategory) {
        selectedCategory = category
    }

    fun coalesceDelayMillis(isUpgradeTourActive: Boolean): Long =
        if (isUpgradeTourActive) 0L else SETTING_CATEGORY_COALESCE_MS

    fun applyCoalescedCategory() {
        activeCategory = selectedCategory
    }

    /**
     * Commits [category] immediately. If [activeCategory] already equals [category], clears
     * [pendingContentFocusCategory] and returns the category's first item [FocusRequester] for
     * synchronous focus; otherwise sets [activeCategory] and [pendingContentFocusCategory] and
     * returns null so [SettingNavigationEffects] focuses after frame composition.
     */
    fun commitCategory(category: SettingsCategory): FocusRequester? {
        selectedCategory = category
        return if (activeCategory == category) {
            pendingContentFocusCategory = null
            focusRequesters.itemRequesters(category).firstOrNull()
        } else {
            activeCategory = category
            pendingContentFocusCategory = category
            null
        }
    }

    fun consumePendingContentFocus(): FocusRequester? {
        if (pendingContentFocusCategory != activeCategory) return null
        pendingContentFocusCategory = null
        return focusRequesters.itemRequesters(activeCategory).firstOrNull()
    }

    fun recordRestoreTarget(requester: FocusRequester, category: SettingsCategory) {
        restoreFocusRequester = requester
        restoreCategory = category
    }

    /** Resolves Back/Escape key when no dialog is open. */
    fun resolveBackKeyDisposition(): SettingBackDisposition =
        if (!isSidebarFocused) {
            SettingBackDisposition.RETURN_TO_SIDEBAR
        } else {
            SettingBackDisposition.EXIT_SETTINGS
        }

    /** Applies [SettingsDestination.DeviceCapabilities] deep-link state. */
    fun applyDestination(destination: SettingsDestination) {
        if (destination is SettingsDestination.DeviceCapabilities) {
            selectedCategory = SettingsCategory.APP_INFO
            activeCategory = SettingsCategory.APP_INFO
            restoreFocusRequester =
                focusRequesters.focusTargetRequester(SettingsFocusTarget.APP_INFO_DEVICE_CAPABILITIES)
            restoreCategory = SettingsCategory.APP_INFO
            activeDialog = SettingDialogState.DeviceCapabilities
        }
    }

    /** Executes initial focus for [destination] (called from [SettingsInitialFocusEffect]). */
    suspend fun performInitialFocus(destination: SettingsDestination) {
        if (destination is SettingsDestination.DeviceCapabilities) return
        delay(400)

        if (destination is SettingsDestination.Item) {
            isSidebarFocused = false
            val targetRequester = focusRequesters.focusTargetRequester(destination.target)

            var success = false
            for (i in 0..5) {
                try {
                    if (targetRequester.requestFocus()) {
                        success = true
                        break
                    } else {
                        delay(150)
                    }
                } catch (e: Exception) {
                    delay(150)
                }
            }

            if (!success) {
                focusRequesters.categoryRequester(destination.category)
                    .safeRequestFocus("Settings_Fallback")
            }
        } else {
            focusRequesters.categoryRequester(selectedCategory)
                .safeRequestFocus("Settings_Initial")
        }
    }

    /** Closes any open dialog/overlay and runs the 300ms + retry focus restoration sequence. */
    fun closeDialog(scope: CoroutineScope) {
        isRestoringFocus = true
        activeDialog = SettingDialogState.None
        showInternalImagePicker = false
        showScreensaverGallery = false
        screensaverPreview = false
        screensaverPlayerPreview = false
        scope.launch {
            delay(300)
            val restored = restoreFocusRequester
                ?.safeRequestFocusWithRetry("SettingScreen_Restore", maxRetries = 3, delayMillis = 50)
                ?: false
            delay(100)
            isRestoringFocus = false
            if (!restored) {
                delay(50)
                focusRequesters.categoryRequester(selectedCategory)
                    .safeRequestFocusWithRetry("SettingScreen_Restore_Fallback")
            }
        }
    }
}

@Composable
internal fun rememberSettingNavigationController(
    destination: SettingsDestination,
): SettingNavigationController {
    val focusRequesters = remember { SettingFocusRequesters() }
    return remember(destination.category, focusRequesters) {
        SettingNavigationController(
            initialCategory = destination.category,
            focusRequesters = focusRequesters,
        )
    }
}

@Composable
internal fun SettingNavigationEffects(
    controller: SettingNavigationController,
    destination: SettingsDestination,
    mainScrollState: ScrollState,
) {
    val isUpgradeTourActive = LocalUpgradeTourActive.current

    // Coalesce rapid D-pad scrubs on the settings sidebar before swapping the right-hand content tree.
    LaunchedEffect(controller, controller.selectedCategory, isUpgradeTourActive) {
        if (controller.activeCategory != controller.selectedCategory) {
            val delayMs = controller.coalesceDelayMillis(isUpgradeTourActive)
            if (delayMs > 0L) {
                delay(delayMs)
            }
            controller.applyCoalescedCategory()
        }
    }

    LaunchedEffect(controller, controller.activeCategory, controller.pendingContentFocusCategory) {
        if (controller.pendingContentFocusCategory == controller.activeCategory) {
            withFrameNanos { }
            controller.focusRequesters.itemRequesters(controller.activeCategory)
                .firstOrNull()
                ?.safeRequestFocusWithRetry(
                    tag = "SettingScreen_CommitCategory",
                    maxRetries = 4,
                    delayMillis = 40L,
                )
            controller.consumePendingContentFocus()
        }
    }

    LaunchedEffect(controller.activeCategory, isUpgradeTourActive, destination) {
        when (
            resolveSettingContentScrollAction(
                activeCategory = controller.activeCategory,
                isUpgradeTourActive = isUpgradeTourActive,
                destination = destination,
            )
        ) {
            SettingContentScrollAction.SCROLL_TO_BOTTOM_AFTER_FRAME -> {
                withFrameNanos { }
                mainScrollState.scrollTo(mainScrollState.maxValue)
            }
            SettingContentScrollAction.SCROLL_TO_TOP -> {
                mainScrollState.scrollTo(0)
            }
            SettingContentScrollAction.NONE -> Unit
        }
    }

    SettingsInitialFocusEffect {
        controller.performInitialFocus(destination)
    }

    LaunchedEffect(controller, destination) {
        controller.applyDestination(destination)
    }
}
