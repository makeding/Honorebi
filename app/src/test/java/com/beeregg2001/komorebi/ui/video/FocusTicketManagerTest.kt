package com.beeregg2001.komorebi.ui.video

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class FocusTicketManagerTest {
    @Test fun repeatedSameDestinationHasDistinctGenerationAndStaleConsumptionCannotClearIt() {
        val manager = FocusTicketManager()
        manager.issue(FocusTicket.TARGET_ID, 42)
        val old = manager.requestGeneration
        manager.issue(FocusTicket.TARGET_ID, 42)
        assertTrue(manager.requestGeneration > old)
        manager.consume(FocusTicket.TARGET_ID, old)
        assertEquals(FocusTicket.TARGET_ID, manager.currentTicket)
        assertEquals(42, manager.targetProgramId)
        manager.consume(FocusTicket.TARGET_ID, manager.requestGeneration)
        assertEquals(FocusTicket.NONE, manager.currentTicket)
    }

    @Test fun unattachedDestinationKeepsItsRequestPending() = runTest {
        val manager = FocusTicketManager()
        manager.issue(FocusTicket.TARGET_ID, path = "video.ts")
        assertFalse(manager.restore(FocusTicket.TARGET_ID, manager.requestGeneration, FocusRequester(), "test"))
        assertEquals(FocusTicket.TARGET_ID, manager.currentTicket)
        assertEquals("video.ts", manager.targetPath)
    }

    @Test fun newerRequestRevokesAnOlderRetryBeforeItCanMoveFocus() = runTest {
        val manager = FocusTicketManager()
        manager.issue(FocusTicket.LIST_TOP)
        val old = manager.requestGeneration
        launch {
            kotlinx.coroutines.delay(50)
            manager.issue(FocusTicket.TARGET_ID, 9)
        }
        assertFalse(manager.restore(FocusTicket.LIST_TOP, old, FocusRequester(), "test"))
        assertEquals(FocusTicket.TARGET_ID, manager.currentTicket)
        assertEquals(9, manager.targetProgramId)
        assertEquals(100L, testScheduler.currentTime)
    }
}
