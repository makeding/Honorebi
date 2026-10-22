package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class HomeEntryFocusTest {
    private val effects = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(effects))

    @Test fun supersededTicketCannotMoveAttachedFocusAndCurrentTicketConsumesAfterSuccess() {
        val manager = FocusTicketManager()
        val tab = FocusRequester()
        val card = FocusRequester()
        compose.setContent {
            Row {
                Box(Modifier.size(48.dp).testTag("tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("card").focusRequester(card).focusable())
            }
        }
        compose.onNodeWithTag("card").requestFocus()
        compose.runOnIdle {
            manager.issue(FocusTicket.LIST_TOP)
            val old = manager.requestGeneration
            manager.issue(FocusTicket.LIST_TOP)
            runBlocking {
                assertFalse(manager.restore(FocusTicket.LIST_TOP, old, tab, "stale"))
            }
        }
        compose.onNodeWithTag("card").assertIsFocused()
        compose.runOnIdle {
            runBlocking {
                assertTrue(manager.restore(FocusTicket.LIST_TOP, manager.requestGeneration, tab, "current"))
            }
            assertEquals(FocusTicket.NONE, manager.currentTicket)
        }
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    @Test fun consumingPlayerReturnDoesNotStealCardFocusButLaterOverlayStillRestores() {
        val returning = mutableStateOf(true)
        val fullScreen = mutableStateOf(false)
        val tab = FocusRequester()
        val card = FocusRequester()
        compose.setContent {
            HomeOverlayReturnFocusEffect(fullScreen.value, returning.value) { tab.requestFocus() }
            Row {
                Box(Modifier.size(48.dp).testTag("tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("card").focusRequester(card).focusable())
            }
        }
        compose.onNodeWithTag("card").requestFocus()
        compose.runOnIdle { returning.value = false }
        compose.onNodeWithTag("card").assertIsFocused()

        compose.runOnIdle { fullScreen.value = true }
        compose.waitForIdle()
        compose.runOnIdle { fullScreen.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    @Test fun playerReturnDoesNotTriggerASecondInitialTabRestore() {
        val returning = mutableStateOf(false)
        val tab = FocusRequester()
        val card = FocusRequester()
        compose.setContent {
            HomeOverlayReturnFocusEffect(false, returning.value) { tab.requestFocus() }
            Row {
                Box(Modifier.size(48.dp).testTag("tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("card").focusRequester(card).focusable())
            }
        }
        compose.onNodeWithTag("card").requestFocus()
        compose.onNodeWithTag("card").assertIsFocused()
        compose.runOnIdle { returning.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("card").assertIsFocused()
    }

    @Test fun backCanReturnToSameTabRepeatedlyAndContentIsDistinct() {
        val memory = FocusMemory()
        val tab = FocusRequester()
        val content = FocusRequester()
        compose.setContent {
            HomeEntryFocusEffect(memory, { tab }, { content })
            Row {
                Box(Modifier.size(48.dp).testTag("tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("content").focusRequester(content).focusable())
            }
        }
        repeat(2) {
            compose.onNodeWithTag("content").requestFocus().assertIsFocused()
            compose.runOnIdle { memory.remember(0) }
            compose.onNodeWithTag("tab").assertIsFocused()
            compose.runOnIdle { assertNull(memory.request) }
        }
        compose.runOnIdle { memory.requestContent(0) }
        compose.onNodeWithTag("content").assertIsFocused()
        compose.runOnIdle { assertNull(memory.request) }
    }

    @Test fun contentEntryWaitsForRealTargetAndFocusesImmediatelyWhenReady() {
        val memory = FocusMemory()
        val ready = mutableStateOf(false)
        val tab = FocusRequester()
        val content = FocusRequester()
        compose.setContent {
            HomeEntryFocusEffect(
                memory = memory,
                tabRequester = { tab },
                contentRequester = { content },
                contentReadyGeneration = if (ready.value) 1L else -1L,
                isContentReady = { ready.value },
            )
            Row {
                Box(Modifier.size(48.dp).testTag("ready-tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("ready-content").focusRequester(content).focusable())
            }
        }
        compose.onNodeWithTag("ready-tab").requestFocus().assertIsFocused()
        compose.runOnIdle { memory.requestContent(0) }
        compose.onNodeWithTag("ready-tab").assertIsFocused()
        compose.runOnIdle { assertTrue(memory.request?.contentTop == true) }

        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag("ready-content").assertIsFocused()
        compose.runOnIdle { assertNull(memory.request) }
    }

    @Test fun rapidTabChangesCancelIntermediateRenderAndSameIndexKeyChangeResetsReady() {
        val requestedIndex = mutableIntStateOf(0)
        val requestedKey = mutableStateOf("home")
        lateinit var render: HomeTabRenderState
        compose.setContent {
            render = remember { HomeTabRenderState(0, "home") }
            HomeTabRenderEffect(requestedIndex.intValue, requestedKey.value, render)
        }
        compose.runOnIdle { assertTrue(render.markContentReady(render.generation)) }
        compose.runOnIdle {
            requestedIndex.intValue = 1
            requestedKey.value = "live"
        }
        compose.runOnIdle { effects.advanceTimeBy(30); effects.runCurrent() }
        compose.runOnIdle {
            requestedIndex.intValue = 2
            requestedKey.value = "apps"
        }
        compose.runOnIdle { effects.advanceTimeBy(TAB_RENDER_COALESCE_MS - 1); effects.runCurrent() }
        compose.runOnIdle { assertEquals("home", render.activeContentKey) }
        compose.runOnIdle { effects.advanceTimeBy(1); effects.runCurrent() }
        compose.runOnIdle {
            assertEquals(2, render.activeIndex)
            assertEquals("apps", render.activeContentKey)
            assertFalse(render.isContentReadyFor(2, "apps"))
            assertFalse(render.markContentReady(0L))
            assertTrue(render.markContentReady(render.generation))
            render.commit(2, "video")
            assertFalse(render.isContentReadyFor(2, "video"))
        }
    }
}
