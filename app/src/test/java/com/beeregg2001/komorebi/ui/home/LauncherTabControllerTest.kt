package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.StartupFocusTarget
import com.beeregg2001.komorebi.ui.theme.TvRailCollapsedWidth
import com.beeregg2001.komorebi.ui.theme.TvRailExpandedWidth
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherTabControllerTest {
    private val effects = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(effects))

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun fullLifecycleSequenceCoversEnterReadyCoalesceExitSuspendResumeAndDispose() {
        val events = mutableListOf<Pair<LauncherTab, LauncherTabLifecycleEvent>>()
        val controller = LauncherTabController(
            initialTab = LauncherTab.HOME,
            onLifecycleEvent = { tab, event -> events += tab to event },
        )

        assertEquals(LauncherTabPhase.MOUNTING, controller.phase)
        assertEquals(listOf(LauncherTab.HOME to LauncherTabLifecycleEvent.ENTER), events)

        // First readiness callback for generation 0 transitions MOUNTING -> ACTIVE.
        assertTrue(controller.markContentReady(0L))
        assertFalse(controller.markContentReady(0L)) // Idempotent
        assertEquals(LauncherTabPhase.ACTIVE, controller.phase)
        assertTrue(controller.isActiveContentReady)

        // Selecting another tab enters COALESCING immediately without tearing down HOME yet.
        controller.selectTab(LauncherTab.VIDEO)
        assertEquals(LauncherTab.VIDEO, controller.selectedTab)
        assertEquals(LauncherTab.HOME, controller.activeTab)
        assertEquals(LauncherTabPhase.COALESCING, controller.phase)
        assertEquals(TabTransitionDirection.FORWARD, controller.transitionDirection)
        assertFalse(controller.isActiveContentReady)

        // Committing exits HOME, advances generation, and enters VIDEO in MOUNTING phase.
        controller.commitPendingSelection()
        assertEquals(LauncherTab.VIDEO, controller.activeTab)
        assertEquals(1L, controller.generation)
        assertEquals(LauncherTabPhase.MOUNTING, controller.phase)

        // Stale readiness callback from old generation 0 is ignored.
        assertFalse(controller.markContentReady(0L))
        assertFalse(controller.isContentReadyFor(LauncherTab.VIDEO))

        // Readiness for generation 1 unlocks VIDEO.
        assertTrue(controller.markContentReady(1L))
        assertEquals(LauncherTabPhase.ACTIVE, controller.phase)
        assertTrue(controller.isContentReadyFor(LauncherTab.VIDEO))

        // Suspend & resume preserve active readiness cleanly.
        controller.updateSuspended(true)
        assertEquals(LauncherTabPhase.SUSPENDED, controller.phase)
        assertTrue(controller.isSuspended)
        controller.updateSuspended(false)
        assertEquals(LauncherTabPhase.ACTIVE, controller.phase)
        assertFalse(controller.isSuspended)

        controller.dispose()
        controller.dispose() // Idempotent

        assertEquals(
            listOf(
                LauncherTab.HOME to LauncherTabLifecycleEvent.ENTER,
                LauncherTab.HOME to LauncherTabLifecycleEvent.READY,
                LauncherTab.HOME to LauncherTabLifecycleEvent.EXIT,
                LauncherTab.VIDEO to LauncherTabLifecycleEvent.ENTER,
                LauncherTab.VIDEO to LauncherTabLifecycleEvent.READY,
                LauncherTab.VIDEO to LauncherTabLifecycleEvent.SUSPEND,
                LauncherTab.VIDEO to LauncherTabLifecycleEvent.RESUME,
                LauncherTab.VIDEO to LauncherTabLifecycleEvent.EXIT,
            ),
            events,
        )
    }

    @Test
    fun scrubbingBackToActiveTabCancelsCoalescingWithoutRemount() {
        val events = mutableListOf<Pair<LauncherTab, LauncherTabLifecycleEvent>>()
        val controller = LauncherTabController(
            initialTab = LauncherTab.LIVE,
            onLifecycleEvent = { tab, event -> events += tab to event },
        )
        assertTrue(controller.markContentReady(controller.generation))

        controller.selectTab(LauncherTab.VIDEO)
        assertEquals(LauncherTabPhase.COALESCING, controller.phase)
        assertEquals(TabTransitionDirection.FORWARD, controller.transitionDirection)

        // User moves back to LIVE before the 60ms window elapses.
        controller.selectTab(LauncherTab.LIVE)
        assertEquals(LauncherTabPhase.ACTIVE, controller.phase)
        assertEquals(0L, controller.generation)
        assertTrue(controller.isActiveContentReady)
        assertEquals(
            listOf(
                LauncherTab.LIVE to LauncherTabLifecycleEvent.ENTER,
                LauncherTab.LIVE to LauncherTabLifecycleEvent.READY,
            ),
            events,
        )
    }

    @Test
    fun hidingAppsTabPreservesFocusRequesterIdentityAndFallsBackWhenAppsSelected() {
        val controller = LauncherTabController(initialTab = LauncherTab.APPS)
        val videoTabRequesterBefore = controller.tabRequester(LauncherTab.VIDEO)
        val videoContentRequesterBefore = controller.contentRequester(LauncherTab.VIDEO)
        val appsTabRequesterBefore = controller.tabRequester(LauncherTab.APPS)
        assertNotSame(videoTabRequesterBefore, appsTabRequesterBefore)

        val fallback = controller.syncVisibleTabs(LauncherTab.visibleTabs(hideAppsTab = true))
        assertEquals(LauncherTab.HOME, fallback)
        assertEquals(LauncherTab.HOME, controller.selectedTab)
        assertEquals(LauncherTab.HOME, controller.activeTab)

        // VIDEO moved from index 4 to index 3, yet keeps its exact FocusRequester instances.
        assertEquals(3, controller.visibleTabs.indexOf(LauncherTab.VIDEO))
        assertSame(videoTabRequesterBefore, controller.tabRequesterAt(3))
        assertSame(videoContentRequesterBefore, controller.contentRequesterAt(3))

        // Syncing again when current tab is still visible returns null.
        assertNull(controller.syncVisibleTabs(LauncherTab.visibleTabs(hideAppsTab = false)))
    }

    @Test
    fun lifecycleHostManagesCoalescingPollingSuspensionAndOfflineEpgPreload() {
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        val fullScreen = mutableStateOf(false)
        val networkAvailable = mutableStateOf(true)
        val pollingHistory = mutableListOf<Boolean>()
        var epgLoads = 0
        var homeRefreshes = 0

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LauncherTabLifecycleHost(
                    controller = controller,
                    isFullScreenMode = fullScreen.value,
                    networkAvailable = networkAvailable.value,
                    onEnsureEpgLoaded = { epgLoads++ },
                    onPollingChanged = { pollingHistory += it },
                    onRefreshHomeData = { homeRefreshes++ },
                )
            }
        }

        compose.runOnIdle {
            assertEquals(true, pollingHistory.last())
            assertEquals(1, homeRefreshes)
            assertEquals(0, epgLoads)
        }

        // Entering fullscreen suspends tab and stops polling.
        compose.runOnIdle { fullScreen.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(LauncherTabPhase.SUSPENDED, controller.phase)
            assertEquals(false, pollingHistory.last())
        }

        // Leaving fullscreen resumes polling.
        compose.runOnIdle { fullScreen.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(true, pollingHistory.last()) }

        // Activity ON_PAUSE suspends polling, ON_RESUME restores it.
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(controller.isSuspended)
            assertEquals(false, pollingHistory.last())
        }
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(controller.isSuspended)
            assertEquals(true, pollingHistory.last())
        }

        // Going offline and scrubbing to EPG still preloads cached EPG after coalesce window.
        compose.runOnIdle {
            networkAvailable.value = false
            controller.selectTab(LauncherTab.LIVE)
            controller.selectTab(LauncherTab.EPG)
        }
        compose.runOnIdle {
            effects.advanceTimeBy(TAB_RENDER_COALESCE_MS - 1)
            effects.runCurrent()
            assertEquals(LauncherTab.HOME, controller.activeTab)
            assertEquals(0, epgLoads)
        }
        compose.runOnIdle {
            effects.advanceTimeBy(1)
            effects.runCurrent()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(LauncherTab.EPG, controller.activeTab)
            assertEquals(1, epgLoads)
            assertEquals(false, pollingHistory.last())
        }
    }

    @Test
    fun returnFocusPolicyCoversEveryTriggerAndTabOwnershipContract() {
        assertEquals(
            TabFocusAction.FocusItem(LauncherTab.HOME, 0, "history", "42"),
            resolveTabReturnFocus(FocusReturnTrigger.AI_RETURN, LauncherTab.HOME, 0, "history", "42"),
        )
        assertEquals(
            TabFocusAction.FocusContentTop(LauncherTab.HOME, 0),
            resolveTabReturnFocus(FocusReturnTrigger.AI_RETURN, LauncherTab.HOME, 0, null, null),
        )
        assertEquals(
            TabFocusAction.DelegateToTabContent(),
            resolveTabReturnFocus(FocusReturnTrigger.AI_RETURN, LauncherTab.LIVE, 1, null, null),
        )
        assertEquals(
            TabFocusAction.DelegateToTabContent(),
            resolveTabReturnFocus(FocusReturnTrigger.PLAYER_RETURN, LauncherTab.VIDEO, 3, null, null),
        )
        assertEquals(
            TabFocusAction.DelegateToTabContent(consumeImmediately = true),
            resolveTabReturnFocus(FocusReturnTrigger.PLAYER_RETURN, LauncherTab.EPG, 4, null, null),
        )
        assertEquals(
            TabFocusAction.FocusItem(LauncherTab.APPS, 2, "apps", "com.example"),
            resolveTabReturnFocus(FocusReturnTrigger.ACTIVITY_RESUME, LauncherTab.APPS, 2, "apps", "com.example"),
        )
        assertEquals(
            TabFocusAction.NoOp,
            resolveTabReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS, LauncherTab.RESERVE, 5, null, null),
        )
        assertEquals(
            TabFocusAction.DelegateToTabContent(),
            resolveTabReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS, LauncherTab.VIDEO, 3, null, null),
        )
        assertEquals(
            TabFocusAction.FocusItem(LauncherTab.HOME, 0, "shortcuts", "onair"),
            resolveTabReturnFocus(FocusReturnTrigger.OVERLAY_DISMISS, LauncherTab.HOME, 0, "shortcuts", "onair"),
        )
    }

    @Test
    fun navRailHandlesDpadSelectionDirectionalTransitionsUpEdgeAndReadyGatedRight() {
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()

        compose.setContent {
            Column {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top-strip-entry")
                        .focusRequester(upEdgeRequester)
                        .focusable(),
                )
                Row {
                    LauncherNavRail(
                        controller = controller,
                        isEpgJumping = false,
                        upEdgeFocusRequester = upEdgeRequester,
                        settingsItemFocusRequester = settingsRequester,
                        settingsCanFocus = true,
                        onTabFocused = { controller.selectTab(it) },
                        onOpenSettings = {},
                        onHasFocusChanged = {},
                        onBack = {},
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("home-content")
                            .focusRequester(controller.contentRequester(LauncherTab.HOME))
                            .focusable(),
                    )
                }
            }
        }

        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()

        // Pressing UP on the first rail tab moves focus to the top-right strip entry.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("top-strip-entry").assertIsFocused()

        // Return to HOME and press DOWN to select LIVE with FORWARD direction.
        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("launcher-tab-live").assertIsFocused()
        compose.runOnIdle {
            assertEquals(LauncherTab.LIVE, controller.selectedTab)
            assertEquals(TabTransitionDirection.FORWARD, controller.transitionDirection)
        }

        // Pressing UP returns to HOME immediately.
        compose.onNodeWithTag("launcher-tab-live").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("launcher-tab-home").assertIsFocused()
        compose.runOnIdle {
            assertEquals(LauncherTab.HOME, controller.selectedTab)
        }

        // Once HOME content marks ready, RIGHT moves focus cleanly into content.
        compose.runOnIdle { assertTrue(controller.markContentReady(controller.generation)) }
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("home-content").assertIsFocused()
    }

    @Test
    fun startupFocusTargetDefaultsToSidebarAndParsesKnownValues() {
        assertEquals(StartupFocusTarget.SIDEBAR, StartupFocusTarget.fromPreference(null))
        assertEquals(StartupFocusTarget.SIDEBAR, StartupFocusTarget.fromPreference("UNKNOWN"))
        assertEquals(StartupFocusTarget.SIDEBAR, StartupFocusTarget.fromPreference("SIDEBAR"))
        assertEquals(StartupFocusTarget.CONTENT, StartupFocusTarget.fromPreference("CONTENT"))
    }

    @Test
    fun navRailStartsCollapsedOnInitialFocusAndExpandsOnDownOrSubsequentReturn() {
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        assertTrue(controller.markContentReady(controller.generation))
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()
        val startCollapsed = mutableStateOf(true)

        compose.setContent {
            Row {
                LauncherNavRail(
                    controller = controller,
                    isEpgJumping = false,
                    upEdgeFocusRequester = upEdgeRequester,
                    settingsItemFocusRequester = settingsRequester,
                    settingsCanFocus = true,
                    onTabFocused = { controller.selectTab(it) },
                    onOpenSettings = {},
                    onHasFocusChanged = {},
                    onBack = {},
                    startCollapsed = startCollapsed.value,
                    onStartCollapsedConsumed = { startCollapsed.value = false },
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home-content")
                        .focusRequester(controller.contentRequester(LauncherTab.HOME))
                        .focusable(),
                )
            }
        }

        // Initial focus on rail keeps it collapsed at 68dp.
        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)
        assertTrue(startCollapsed.value)

        // Pressing RIGHT enters content directly and consumes initial collapsed suppression.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("home-content").assertIsFocused()
        compose.waitForIdle()
        assertFalse(startCollapsed.value)
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)

        // Returning focus to the rail now expands it normally.
        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailExpandedWidth)
    }

    @Test
    fun navRailStartingCollapsedExpandsImmediatelyWhenPressingDown() {
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()
        val startCollapsed = mutableStateOf(true)

        compose.setContent {
            LauncherNavRail(
                controller = controller,
                isEpgJumping = false,
                upEdgeFocusRequester = upEdgeRequester,
                settingsItemFocusRequester = settingsRequester,
                settingsCanFocus = true,
                onTabFocused = { controller.selectTab(it) },
                onOpenSettings = {},
                onHasFocusChanged = {},
                onBack = {},
                startCollapsed = startCollapsed.value,
                onStartCollapsedConsumed = { startCollapsed.value = false },
            )
        }

        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)

        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("launcher-tab-live").assertIsFocused()
        compose.waitForIdle()
        assertFalse(startCollapsed.value)
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailExpandedWidth)
    }

    @Test
    fun navRailLeftKeyTogglesExpandedAndCollapsedStateAndResetsAfterLeavingRail() {
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        assertTrue(controller.markContentReady(controller.generation))
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()
        val startCollapsed = mutableStateOf(true)

        compose.setContent {
            Row {
                LauncherNavRail(
                    controller = controller,
                    isEpgJumping = false,
                    upEdgeFocusRequester = upEdgeRequester,
                    settingsItemFocusRequester = settingsRequester,
                    settingsCanFocus = true,
                    onTabFocused = { controller.selectTab(it) },
                    onOpenSettings = {},
                    onHasFocusChanged = {},
                    onBack = {},
                    startCollapsed = startCollapsed.value,
                    onStartCollapsedConsumed = { startCollapsed.value = false },
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("home-content")
                        .focusRequester(controller.contentRequester(LauncherTab.HOME))
                        .focusable(),
                )
            }
        }

        // Starts collapsed when startCollapsed is true.
        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)

        // Pressing LEFT expands the rail and consumes initial collapsed suppression.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        assertFalse(startCollapsed.value)
        compose.onNodeWithTag("launcher-tab-home").assertIsFocused()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailExpandedWidth)

        // Pressing LEFT again collapses the rail while keeping focus on the same tab.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-tab-home").assertIsFocused()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)

        // Pressing LEFT a third time expands it again.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-tab-home").assertIsFocused()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailExpandedWidth)

        // Manually collapse, leave rail via RIGHT into content, then return -> rail expands normally.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailCollapsedWidth)

        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("home-content").assertIsFocused()
        compose.waitForIdle()

        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
        compose.waitForIdle()
        compose.onNodeWithTag("launcher-nav-rail").assertWidthIsEqualTo(TvRailExpandedWidth)
    }

    @Test
    fun scrubbingUpPastHomeToTopStripCancelsPendingSelectionAndKeepsActiveTab() {
        val controller = LauncherTabController(initialTab = LauncherTab.VIDEO)
        assertTrue(controller.markContentReady(controller.generation))
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()
        var railHasFocus = false

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LauncherTabLifecycleHost(
                    controller = controller,
                    isFullScreenMode = false,
                    networkAvailable = true,
                    onEnsureEpgLoaded = {},
                    onPollingChanged = {},
                    onRefreshHomeData = {},
                )
                Column {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("top-strip-entry")
                            .focusRequester(upEdgeRequester)
                            .focusable(),
                    )
                    LauncherNavRail(
                        controller = controller,
                        isEpgJumping = false,
                        upEdgeFocusRequester = upEdgeRequester,
                        settingsItemFocusRequester = settingsRequester,
                        settingsCanFocus = true,
                        onTabFocused = { controller.selectTab(it) },
                        onOpenSettings = {},
                        onHasFocusChanged = { hasFocus ->
                            val hadFocus = railHasFocus
                            railHasFocus = hasFocus
                            if (hadFocus && !hasFocus) {
                                controller.cancelPendingSelection()
                            }
                        },
                        onBack = {},
                    )
                }
            }
        }

        // Start focused on VIDEO (the active tab) and rapidly press UP past HOME into the top-right strip.
        compose.onNodeWithTag("launcher-tab-video").requestFocus().assertIsFocused()
        compose.onNodeWithTag("launcher-tab-video").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("launcher-tab-apps").assertIsFocused()
        compose.onNodeWithTag("launcher-tab-apps").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("launcher-tab-live").assertIsFocused()
        compose.onNodeWithTag("launcher-tab-live").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("launcher-tab-home").assertIsFocused()
        compose.runOnIdle {
            assertEquals(LauncherTab.HOME, controller.selectedTab)
            assertEquals(LauncherTab.VIDEO, controller.activeTab)
            assertEquals(LauncherTabPhase.COALESCING, controller.phase)
        }

        // Pressing UP from HOME moves focus into the top-right status strip and cancels the pending HOME scrub.
        compose.onNodeWithTag("launcher-tab-home").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("top-strip-entry").assertIsFocused()
        compose.runOnIdle {
            assertEquals(LauncherTab.VIDEO, controller.selectedTab)
            assertEquals(LauncherTab.VIDEO, controller.activeTab)
            assertEquals(LauncherTabPhase.ACTIVE, controller.phase)
        }

        // Even after the coalesce window elapses, activeTab remains VIDEO (does not navigate to HOME).
        compose.runOnIdle {
            effects.advanceTimeBy(TAB_RENDER_COALESCE_MS + 100L)
            effects.runCurrent()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(LauncherTab.VIDEO, controller.selectedTab)
            assertEquals(LauncherTab.VIDEO, controller.activeTab)
            assertEquals(0L, controller.generation)
        }
    }
}
