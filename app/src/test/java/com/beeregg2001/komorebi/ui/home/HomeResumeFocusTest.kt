package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTvMaterial3Api::class)
class HomeResumeFocusTest {
    private val effects = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(effects))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun upReturnThenLeftRightKeepsEveryTabAlignedInsteadOfReturningToInitialVideo() {
        val selected = mutableIntStateOf(HOME_TAB_TITLES.indexOf("ビデオ"))
        val memory = FocusMemory()
        val requesters = List(HOME_TAB_TITLES.size) { FocusRequester() }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            val currentIndex = selected.intValue
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeResumeFocusEffect { memory.remember(currentIndex) }
                HomeEntryFocusEffect(memory, { requesters[it] }, { requesters[it] })
                Column {
                    TabRow(selectedTabIndex = selected.intValue,
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
                                owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                                true
                            } else false
                        }) {
                        HOME_TAB_TITLES.forEachIndexed { index, title ->
                            Tab(selected = selected.intValue == index,
                                onFocus = { selected.intValue = index },
                                modifier = Modifier.testTag("tab-$index")
                                    .focusRequester(requesters[index])
                                    .focusProperties { up = FocusRequester.Cancel }) {
                                Text(title)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("tab-3").requestFocus()
        HOME_TAB_TITLES.indices.forEach { index ->
            compose.onNodeWithTag("tab-$index").requestFocus()
                .performKeyInput { pressKey(Key.DirectionUp) }
            compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
            compose.waitForIdle()
            compose.onNodeWithTag("tab-$index").assertIsFocused()
            compose.runOnIdle { assertEquals(index, selected.intValue) }
            // Exercise both directions from each tab, including row edges.
            val neighbor = if (index == HOME_TAB_TITLES.lastIndex) index - 1 else index + 1
            val firstKey = if (neighbor > index) Key.DirectionRight else Key.DirectionLeft
            val returnKey = if (neighbor > index) Key.DirectionLeft else Key.DirectionRight
            compose.onNodeWithTag("tab-$index").performKeyInput { pressKey(firstKey) }
            compose.onNodeWithTag("tab-$neighbor").assertIsFocused()
            compose.runOnIdle { assertEquals(neighbor, selected.intValue) }
            compose.onNodeWithTag("tab-$neighbor").performKeyInput { pressKey(returnKey) }
            compose.onNodeWithTag("tab-$index").assertIsFocused()
            compose.runOnIdle { assertEquals(index, selected.intValue) }
        }
    }

    @Test fun returnUsesLatestVisibleTabsAndHonorsPlayerOwnership() {
        val hideApps = mutableStateOf(false)
        val selected = mutableIntStateOf(3)
        val returning = mutableStateOf(false)
        val destinations = mutableListOf<Pair<Int, String>>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            val tabs = visibleHomeTabs(hideApps.value)
            val index = selected.intValue
            val playerOwnsFocus = returning.value
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HomeResumeFocusEffect {
                    if (!playerOwnsFocus) destinations += index to tabs[index]
                }
            }
        }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            hideApps.value = true
            selected.intValue = 2
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.runOnIdle { assertEquals(listOf(2 to "ビデオ"), destinations) }
        compose.runOnIdle {
            destinations.clear()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            returning.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.runOnIdle { assertTrue(destinations.isEmpty()) }
    }

    @Test fun backFromContentAndTabsReturnsDirectlyToCurrentConfiguredDefault() {
        val ui = HomeLauncherState(HOME_TAB_TITLES.indexOf("ビデオ"))
        val selected = mutableIntStateOf(ui.selectedTabIndex)
        val memory = FocusMemory()
        val defaultTitle = mutableStateOf("ビデオ")
        val focusedTabs = mutableListOf<Int>()
        compose.setContent {
            val defaultIndex = HOME_TAB_TITLES.indexOf(defaultTitle.value).coerceAtLeast(0)
            HomeEntryFocusEffect(memory, { ui.tabFocusRequesters[it] }, { ui.contentFirstItemRequesters[it] })
            Column(Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Back) {
                    ui.handleBackNavigation(
                        defaultTabIndex = defaultIndex,
                        onTabChange = { target -> selected.intValue = target },
                        onBackTriggered = {},
                        requestTopNavFocus = { target -> memory.remember(target) },
                        escapeToSafeHouse = { ui.safeHouseRequester.requestFocus() },
                    )
                    true
                } else false
            }) {
                TabRow(selectedTabIndex = selected.intValue) {
                    HOME_TAB_TITLES.forEachIndexed { index, title ->
                        Tab(selected = selected.intValue == index,
                            onFocus = {
                                focusedTabs += index
                                ui.selectedTabIndex = index
                                selected.intValue = index
                                ui.topNavHasFocus = true
                            },
                            modifier = Modifier.testTag("tab-$index")
                                .focusRequester(ui.tabFocusRequesters[index])) {
                            Text(title)
                        }
                    }
                }
                Box(Modifier.size(48.dp).testTag("video-card").focusable())
                Box(Modifier.size(1.dp).focusRequester(ui.safeHouseRequester).focusable())
            }
        }
        compose.onNodeWithTag("tab-3").requestFocus()
        compose.onNodeWithTag("video-card").requestFocus()
        compose.runOnIdle { ui.topNavHasFocus = false }
        compose.onNodeWithTag("video-card").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("tab-3").assertIsFocused()
        compose.runOnIdle { assertEquals(3, selected.intValue) }
        compose.runOnIdle { focusedTabs.clear() }
        compose.onNodeWithTag("tab-3").performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        compose.onNodeWithTag("tab-3").assertIsFocused()
        compose.runOnIdle {
            assertEquals(3, selected.intValue)
            assertEquals(3, ui.selectedTabIndex)
            assertEquals(listOf(3), focusedTabs)
        }
        compose.onNodeWithTag("tab-0").requestFocus()
        compose.runOnIdle { focusedTabs.clear() }
        compose.onNodeWithTag("tab-0").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("tab-3").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(3), focusedTabs) }
        compose.runOnIdle { defaultTitle.value = "ライブ" }
        compose.onNodeWithTag("tab-3").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("tab-1").assertIsFocused()
        compose.onNodeWithTag("tab-1").performKeyInput { pressKey(Key.Back) }
        compose.onNodeWithTag("tab-1").assertIsFocused()
        compose.runOnIdle { assertEquals(1, selected.intValue) }
    }
}
