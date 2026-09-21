package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.LiveStreamSessionResponse
import com.beeregg2001.komorebi.data.repository.LiveStreamSessionLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackSlotControllerTest {
    @Test fun duplicateErrorsDoNotCancelStartupOrRecovery() = runBlocking {
        val controller = LivePlaybackSlotController("recovery")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val run = controller.begin()
        val startupGate = CompletableDeferred<Unit>()
        val recoveryGate = CompletableDeferred<Unit>()
        var recovered = false
        try {
            val startup = scope.launch { startupGate.await() }
            assertTrue(run.attachStartupJob(startup))
            assertTrue(run.launchRecovery(scope) { recoveryGate.await(); recovered = true })
            assertFalse(run.launchRecovery(scope) { fail("duplicate recovery") })
            assertTrue(startup.isActive)
            controller.releasePlayback("retry")
            assertTrue(run.isCurrent())
            recoveryGate.complete(Unit)
            assertTrue(recovered)
            assertFalse(run.isRecovering())
            assertTrue(run.launchRecovery(scope) { })
        } finally { controller.stop("test"); scope.cancel() }
    }

    @Test fun switchingOrExitingCancelsRecoveryAndRejectsOldErrors() = runBlocking {
        val controller = LivePlaybackSlotController("recovery")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = controller.begin()
        var cancelled = false
        try {
            old.launchRecovery(scope) { try { awaitCancellation() } finally { cancelled = true } }
            val current = controller.begin()
            assertTrue(cancelled)
            assertFalse(old.launchRecovery(scope) { fail("stale recovery") })
            assertTrue(current.launchRecovery(scope) { awaitCancellation() })
            controller.stop("exit")
            assertFalse(current.isCurrent())
            assertFalse(current.launchRecovery(scope) { fail("exit recovery") })
        } finally { scope.cancel() }
    }

    @Test fun singleSideRecoveryDoesNotInvalidateTheOtherSlot() = runBlocking {
        val main = LivePlaybackSlotController("main")
        val dual = LivePlaybackSlotController("dual")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mainRun = main.begin()
        val dualRun = dual.begin()
        try {
            mainRun.launchRecovery(scope) { awaitCancellation() }
            main.releasePlayback("retry")
            main.begin()
            assertTrue(dualRun.isCurrent())
            assertFalse(dualRun.isRecovering())
        } finally { main.stop("test"); dual.stop("test"); scope.cancel() }
    }

    @Test fun recoveryCanHandOffToNewStartupWhileItsOwnJobIsCancelled() = runBlocking {
        val controller = LivePlaybackSlotController("handoff")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val released = CountDownLatch(1)
        val installed = CompletableDeferred<Boolean>()
        try {
            val first = controller.begin()
            assertTrue(first.launchRecovery(scope) {
                val replacement = controller.begin() // Cancels this recovery, like playMainChannel.
                val startup = scope.launch(start = CoroutineStart.LAZY) {
                    installed.complete(replacement.installLease(lease("replacement", released)))
                }
                replacement.attachStartupJob(startup)
                startup.start()
            })
            assertTrue(withTimeout(2000) { installed.await() })
            assertEquals("replacement", controller.currentLease()?.id)
            controller.stop("exit")
            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally { controller.stop("test"); scope.cancel() }
    }

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
    fun exitDuringDelayedCreationReleasesTheReturnedLease() = runBlocking {
        val released = CountDownLatch(1)
        val controller = LivePlaybackSlotController("test")
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val creationGate = CompletableDeferred<Unit>()
        val delayedCreator = controller.begin()
        try {
            delayedCreator.launch(ownerScope) {
                delayedCreator.withCreatedResource(
                    create = { entered.complete(Unit); creationGate.await(); lease("late-after-exit", released) },
                    leaseOf = { it }
                ) { _, commit -> commit() }
            }
            entered.await()
            controller.stop("screen_exit")
            creationGate.complete(Unit)
            assertTrue(released.await(2, TimeUnit.SECONDS))
        } finally { ownerScope.cancel() }
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

    @Test
    fun cancelledOwnerReleasesLateResponseWithoutInstallingIt() = runBlocking {
        val released = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<LiveStreamSessionLease>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = LivePlaybackSlotController("cancel-test")
        var installs = 0
        val run = controller.begin()
        run.launch(owner) {
            run.withCreatedResource(
                create = { entered.complete(Unit); response.await() }, leaseOf = { it }
            ) { _, commit -> installs++; commit() }
        }
        entered.await()
        owner.cancel() // ViewModel destruction while the backend is creating the session.
        controller.stop("view_model_cleared")
        response.complete(lease("late-response", released))
        assertTrue(released.await(2, TimeUnit.SECONDS))
        assertEquals(0, installs)
    }

    @Test
    fun channelSwitchDuringCreationDoesNotReleaseNewSession() = runBlocking {
        val oldClosed = CountDownLatch(1)
        val newClosed = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<LiveStreamSessionLease>()
        val controller = LivePlaybackSlotController("switch-test")
        val oldRun = controller.begin()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            oldRun.launch(owner) {
                oldRun.withCreatedResource(create = { entered.complete(Unit); response.await() }, leaseOf = { it }) {
                    _, commit -> commit()
                }
            }
            entered.await()
            val newRun = controller.begin()
            newRun.withCreatedResource(create = { lease("new", newClosed) }, leaseOf = { it }) {
                _, commit -> assertTrue(commit())
            }
            response.complete(lease("old", oldClosed))
            assertTrue(oldClosed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, newClosed.count)
            assertTrue(newRun.isCurrent())
            controller.stop("exit")
            assertTrue(newClosed.await(2, TimeUnit.SECONDS))
        } finally { owner.cancel() }
    }

    @Test
    fun failedAndUncommittedInstallationsReleaseTheirSession() = runBlocking {
        for (fail in listOf(false, true)) {
            val closed = CountDownLatch(1)
            val run = LivePlaybackSlotController("install-test").begin()
            try {
                run.withCreatedResource(create = { lease("unclaimed", closed) }, leaseOf = { it }) { _, _ ->
                    if (fail) throw java.io.IOException("media source failed")
                    // Early return, without transferring ownership.
                }
            } catch (_: java.io.IOException) {}
            assertTrue(closed.await(2, TimeUnit.SECONDS))
        }
    }

    private fun lease(id: String, released: CountDownLatch) = LiveStreamSessionLease(
        LiveStreamSessionResponse(id, "https://example.invalid/$id", "hls")
    ) { released.countDown() }
}
