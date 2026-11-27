package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h1080dp-land")
class SettingNavigationControllerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun categoryFocusCoalescesAt200msInNormalModeAndBypassesDelayDuringUpgradeTour() {
        compose.mainClock.autoAdvance = false
        var tourActive by mutableStateOf(false)
        val renderedCategories = mutableListOf<SettingsCategory>()
        lateinit var controller: SettingNavigationController

        compose.setContent {
            KomorebiTheme {
                CompositionLocalProvider(LocalUpgradeTourActive provides tourActive) {
                    val destination = SettingsDestination.Default
                    controller = rememberSettingNavigationController(destination)
                    val sidebarRows = remember { buildSettingSidebarRows() }
                    val mainScrollState = rememberScrollState()
                    val sidebarScrollState = rememberScrollState()
                    val scope = rememberCoroutineScope()

                    SettingScaffold(
                        controller = controller,
                        destination = destination,
                        sidebarRows = sidebarRows,
                        mainScrollState = mainScrollState,
                        sidebarScrollState = sidebarScrollState,
                        onCloseDialog = { controller.closeDialog(scope) },
                        onBack = {},
                        categoryContent = { category ->
                            SideEffect { renderedCategories.add(category) }
                            Button(
                                onClick = {},
                                modifier = Modifier
                                    .testTag("content-${category.name}-0")
                                    .focusRequester(controller.focusRequesters.itemRequesters(category)[0]),
                            ) {
                                Text(category.name)
                            }
                        },
                    )
                }
            }
        }

        // Let initial focus settle on GENERAL.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(SettingsCategory.GENERAL, controller.activeCategory)

        // Rapid scrub GENERAL -> CONNECTION -> DEEP_TV within 100ms.
        compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_CONNECTION).requestFocus()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(SettingsCategory.CONNECTION, controller.selectedCategory)
        assertEquals(SettingsCategory.GENERAL, controller.activeCategory)

        compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_DEEP_TV).requestFocus()
        compose.mainClock.advanceTimeBy(150)
        assertEquals(SettingsCategory.DEEP_TV, controller.selectedCategory)
        assertEquals(SettingsCategory.GENERAL, controller.activeCategory)

        // Advancing past the 200ms coalesce window switches directly to DEEP_TV, skipping CONNECTION.
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        assertEquals(SettingsCategory.DEEP_TV, controller.activeCategory)
        assertFalse(SettingsCategory.CONNECTION in renderedCategories)

        // During Upgrade Tour, category switch bypasses the 200ms coalesce delay.
        compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_COMMENT).requestFocus()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(SettingsCategory.COMMENT, controller.selectedCategory)
        assertEquals(SettingsCategory.DEEP_TV, controller.activeCategory)

        compose.runOnIdle { tourActive = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(50)
        compose.waitForIdle()
        assertEquals(SettingsCategory.COMMENT, controller.selectedCategory)
        assertEquals(SettingsCategory.COMMENT, controller.activeCategory)
    }

    @Test
    fun commitCategoryViaClickOrRightKeyImmediatelySwitchesPaneAndFocusesFirstContentItem() {
        compose.mainClock.autoAdvance = false
        lateinit var controller: SettingNavigationController

        compose.setContent {
            KomorebiTheme {
                val destination = SettingsDestination.Default
                controller = rememberSettingNavigationController(destination)
                val sidebarRows = remember { buildSettingSidebarRows() }
                val mainScrollState = rememberScrollState()
                val sidebarScrollState = rememberScrollState()
                val scope = rememberCoroutineScope()

                SettingScaffold(
                    controller = controller,
                    destination = destination,
                    sidebarRows = sidebarRows,
                    mainScrollState = mainScrollState,
                    sidebarScrollState = sidebarScrollState,
                    onCloseDialog = { controller.closeDialog(scope) },
                    onBack = {},
                    categoryContent = { category ->
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .testTag("content-${category.name}-0")
                                .focusRequester(controller.focusRequesters.itemRequesters(category)[0]),
                        ) {
                            Text(category.name)
                        }
                    },
                )
            }
        }

        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // Focus PLAYBACK and press D-pad Right before the 200ms coalesce window elapses.
        val playbackNode = compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_PLAYBACK)
        playbackNode.requestFocus()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(SettingsCategory.GENERAL, controller.activeCategory)

        playbackNode.performKeyInput {
            keyDown(Key.DirectionRight)
            keyUp(Key.DirectionRight)
        }
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals(SettingsCategory.PLAYBACK, controller.activeCategory)
        compose.onNodeWithTag("content-PLAYBACK-0").assertIsFocused()

        // Focus EPG and trigger D-pad Center click before the 200ms coalesce window elapses.
        val epgNode = compose.onNodeWithText("番組表設定")
        epgNode.performScrollTo()
        epgNode.requestFocus()
        compose.mainClock.advanceTimeBy(50)
        assertEquals(SettingsCategory.PLAYBACK, controller.activeCategory)

        epgNode.performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals(SettingsCategory.EPG, controller.activeCategory)
        compose.onNodeWithTag("content-EPG-0").assertIsFocused()
    }

    @Test
    fun backKeyFromContentReturnsFocusToSidebarAndBackKeyFromSidebarExitsSettings() {
        var onBackCalls = 0
        lateinit var controller: SettingNavigationController

        compose.setContent {
            KomorebiTheme {
                val destination = SettingsDestination.Default
                controller = rememberSettingNavigationController(destination)
                val sidebarRows = remember { buildSettingSidebarRows() }
                val mainScrollState = rememberScrollState()
                val sidebarScrollState = rememberScrollState()
                val scope = rememberCoroutineScope()

                SettingScaffold(
                    controller = controller,
                    destination = destination,
                    sidebarRows = sidebarRows,
                    mainScrollState = mainScrollState,
                    sidebarScrollState = sidebarScrollState,
                    onCloseDialog = { controller.closeDialog(scope) },
                    onBack = { onBackCalls++ },
                    categoryContent = { category ->
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .testTag("content-${category.name}-0")
                                .focusRequester(controller.focusRequesters.itemRequesters(category)[0]),
                        ) {
                            Text(category.name)
                        }
                    },
                )
            }
        }

        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // Commit into GENERAL content pane via D-pad Center on the focused sidebar row.
        val generalSidebar = compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_GENERAL)
        generalSidebar.assertIsFocused()
        generalSidebar.performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        val generalContent = compose.onNodeWithTag("content-GENERAL-0")
        generalContent.assertIsFocused()
        assertFalse(controller.isSidebarFocused)

        // Back from content returns focus to the selected sidebar row without exiting settings.
        generalContent.performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }
        compose.waitForIdle()
        assertEquals(0, onBackCalls)
        assertTrue(controller.isSidebarFocused)
        generalSidebar.assertIsFocused()

        // Back from sidebar exits settings.
        generalSidebar.performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }
        compose.waitForIdle()
        assertEquals(1, onBackCalls)
    }

    @Test
    fun dialogCloseViaBackHandlerRestoresItemFocusAndFallsBackToSidebarWhenUnattached() {
        lateinit var controller: SettingNavigationController
        lateinit var backDispatcher: OnBackPressedDispatcher

        compose.setContent {
            KomorebiTheme {
                val destination = SettingsDestination.Default
                controller = rememberSettingNavigationController(destination)
                val sidebarRows = remember { buildSettingSidebarRows() }
                val mainScrollState = rememberScrollState()
                val sidebarScrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher

                SettingScaffold(
                    controller = controller,
                    destination = destination,
                    sidebarRows = sidebarRows,
                    mainScrollState = mainScrollState,
                    sidebarScrollState = sidebarScrollState,
                    onCloseDialog = { controller.closeDialog(scope) },
                    onBack = {},
                    categoryContent = { category ->
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .testTag("content-${category.name}-0")
                                .focusRequester(controller.focusRequesters.itemRequesters(category)[0]),
                        ) {
                            Text(category.name)
                        }
                    },
                    dialogsContent = {
                        if (controller.activeDialog is SettingDialogState.Selection) {
                            val dialogR = remember { FocusRequester() }
                            LaunchedEffect(controller.activeDialog) {
                                dialogR.safeRequestFocus("TestSelectionDialog")
                            }
                            Box {
                                Button(
                                    onClick = {},
                                    modifier = Modifier
                                        .testTag("test-selection-dialog")
                                        .focusRequester(dialogR),
                                ) {
                                    Text("Dialog")
                                }
                            }
                        }
                    },
                )
            }
        }

        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()

        // Focus content item, record restore target, and open dialog.
        val contentItem = compose.onNodeWithTag("content-GENERAL-0")
        contentItem.requestFocus().assertIsFocused()
        compose.runOnIdle {
            controller.recordRestoreTarget(
                controller.focusRequesters.itemRequesters(SettingsCategory.GENERAL)[0],
                SettingsCategory.GENERAL,
            )
            controller.activeDialog = SettingDialogState.Selection(
                title = "テスト",
                options = listOf("A" to "A"),
                current = "A",
                onSelect = {},
            )
        }
        compose.waitForIdle()

        // Dismiss via BackHandler and verify initial restoring state + post-600ms item focus restore.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(SettingDialogState.None, controller.activeDialog)
        assertTrue(controller.isRestoringFocus)

        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertFalse(controller.isRestoringFocus)
        contentItem.assertIsFocused()

        // Repeat with an unattached FocusRequester and verify fallback to selected sidebar category.
        compose.runOnIdle {
            controller.recordRestoreTarget(FocusRequester(), SettingsCategory.GENERAL)
            controller.activeDialog = SettingDialogState.Selection(
                title = "テスト2",
                options = listOf("A" to "A"),
                current = "A",
                onSelect = {},
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        compose.runOnIdle { backDispatcher.onBackPressed() }
        assertEquals(SettingDialogState.None, controller.activeDialog)
        assertTrue(controller.isRestoringFocus)

        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertFalse(controller.isRestoringFocus)
        compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_GENERAL).assertIsFocused()
    }

    @Test
    fun settingsDestinationDeepLinks_categoryItemTourScrollAndDeviceCapabilities() {
        var caseIndex by mutableStateOf(0)
        var tourActive by mutableStateOf(false)
        lateinit var controller: SettingNavigationController
        lateinit var mainScrollState: ScrollState
        lateinit var scope: CoroutineScope

        compose.setContent {
            KomorebiTheme {
                CompositionLocalProvider(LocalUpgradeTourActive provides tourActive) {
                    key(caseIndex) {
                        val destination = when (caseIndex) {
                            0 -> SettingsDestination.Category(SettingsCategory.DEEP_TV)
                            1 -> SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE)
                            else -> SettingsDestination.DeviceCapabilities
                        }
                        scope = rememberCoroutineScope()
                        controller = rememberSettingNavigationController(destination)
                        mainScrollState = rememberScrollState()
                        val sidebarScrollState = rememberScrollState()

                        SettingScaffold(
                            controller = controller,
                            destination = destination,
                            sidebarRows = remember { buildSettingSidebarRows() },
                            mainScrollState = mainScrollState,
                            sidebarScrollState = sidebarScrollState,
                            onCloseDialog = { controller.closeDialog(scope) },
                            onBack = {},
                            categoryContent = { category ->
                                when (category) {
                                    SettingsCategory.COMMENT -> {
                                        Spacer(Modifier.height(1600.dp))
                                        Button(
                                            onClick = {},
                                            modifier = Modifier
                                                .testTag("comment-engine")
                                                .focusRequester(
                                                    controller.focusRequesters.focusTargetRequester(
                                                        SettingsFocusTarget.COMMENT_ENGINE,
                                                    ),
                                                ),
                                        ) {
                                            Text("Comment Engine")
                                        }
                                    }
                                    SettingsCategory.APP_INFO -> {
                                        Button(
                                            onClick = {},
                                            modifier = Modifier
                                                .testTag("device-capabilities")
                                                .focusRequester(
                                                    controller.focusRequesters.focusTargetRequester(
                                                        SettingsFocusTarget.APP_INFO_DEVICE_CAPABILITIES,
                                                    ),
                                                ),
                                        ) {
                                            Text("Device Capabilities")
                                        }
                                    }
                                    else -> {
                                        Button(
                                            onClick = {},
                                            modifier = Modifier
                                                .testTag("content-${category.name}-0")
                                                .focusRequester(
                                                    controller.focusRequesters.itemRequesters(category)[0],
                                                ),
                                        ) {
                                            Text(category.name)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        // 1. SettingsDestination.Category(DEEP_TV)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(SettingsCategory.DEEP_TV, controller.activeCategory)
        compose.onNodeWithText(AppStrings.SETTINGS_CATEGORY_DEEP_TV).assertIsFocused()

        // 2. SettingsDestination.Item(COMMENT_ENGINE) during Upgrade Tour then after tour dismissal
        compose.runOnIdle {
            tourActive = true
            caseIndex = 1
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(SettingsCategory.COMMENT, controller.activeCategory)
        assertTrue(mainScrollState.maxValue > 0)
        assertEquals(mainScrollState.maxValue, mainScrollState.value)
        compose.onNodeWithTag("comment-engine").assertIsNotFocused()

        compose.runOnIdle { tourActive = false }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertFalse(controller.isSidebarFocused)
        compose.onNodeWithTag("comment-engine").assertIsFocused()

        // 3. SettingsDestination.DeviceCapabilities
        compose.runOnIdle { caseIndex = 2 }
        compose.waitForIdle()
        assertEquals(SettingsCategory.APP_INFO, controller.activeCategory)
        assertEquals(SettingDialogState.DeviceCapabilities, controller.activeDialog)

        compose.runOnIdle { controller.closeDialog(scope) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(SettingDialogState.None, controller.activeDialog)
        compose.onNodeWithTag("device-capabilities").assertIsFocused()
    }

    @Test
    fun resolveSettingContentScrollAction_unitMatrix() {
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_BOTTOM_AFTER_FRAME,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.COMMENT,
                isUpgradeTourActive = true,
                destination = SettingsDestination.Category(SettingsCategory.COMMENT),
            ),
        )
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_BOTTOM_AFTER_FRAME,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.COMMENT,
                isUpgradeTourActive = true,
                destination = SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE),
            ),
        )
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_TOP,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.DEEP_TV,
                isUpgradeTourActive = true,
                destination = SettingsDestination.Category(SettingsCategory.DEEP_TV),
            ),
        )
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_TOP,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.GENERAL,
                isUpgradeTourActive = false,
                destination = SettingsDestination.Category(SettingsCategory.GENERAL),
            ),
        )
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_TOP,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.GENERAL,
                isUpgradeTourActive = false,
                destination = SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE),
            ),
        )
        assertEquals(
            SettingContentScrollAction.NONE,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.COMMENT,
                isUpgradeTourActive = false,
                destination = SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE),
            ),
        )
        assertEquals(
            SettingContentScrollAction.SCROLL_TO_TOP,
            resolveSettingContentScrollAction(
                activeCategory = SettingsCategory.APP_INFO,
                isUpgradeTourActive = false,
                destination = SettingsDestination.DeviceCapabilities,
            ),
        )
    }
}
