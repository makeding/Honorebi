package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.LiveStreamSessionResponse
import com.beeregg2001.komorebi.data.repository.LiveStreamSessionLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackSlotControllerTest {
    @Test
    fun lateCreatorCannotInstallOrStopTheNewSlotLease() {
        val released = CountDownLatch(2)
        val controller = LivePlaybackSlotController("test")
        val oldRun = controller.begin()
        val newRun = controller.begin()
        val oldLease = lease("old", released)
        val newLease = lease("new", released)

        assertFalse(oldRun.installLease(oldLease))
        assertTrue(newRun.installLease(newLease))
        controller.stop("test")

        assertTrue("both the late and installed leases must be released", released.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun replacingASlotInvalidatesThePreviousRun() {
        val controller = LivePlaybackSlotController("test")
        val first = controller.begin()
        val second = controller.begin()

        assertFalse(first.isCurrent())
        assertTrue(second.isCurrent())
    }

    @Test
    fun exitDuringDelayedCreationReleasesTheReturnedLease() {
        val released = CountDownLatch(1)
        val controller = LivePlaybackSlotController("test")
        val delayedCreator = controller.begin()

        controller.stop("screen_exit")

        assertFalse(delayedCreator.installLease(lease("late-after-exit", released)))
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun retryRaceKeepsOnlyTheLatestGenerationCurrent() {
        val controller = LivePlaybackSlotController("test")
        val firstRetry = controller.begin()
        val secondRetry = controller.begin()
        val finalRetry = controller.begin()

        assertFalse(firstRetry.isCurrent())
        assertFalse(secondRetry.isCurrent())
        assertTrue(finalRetry.isCurrent())
    }

    private fun lease(id: String, released: CountDownLatch) = LiveStreamSessionLease(
        LiveStreamSessionResponse(id, "https://example.invalid/$id", "hls")
    ) { released.countDown() }
}
