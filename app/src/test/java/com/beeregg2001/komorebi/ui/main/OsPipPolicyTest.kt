package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsPipPolicyTest {

    @Test
    fun shouldEnter_requiresAllGates() {
        assertTrue(
            OsPipPolicy.shouldEnter(
                sdkInt = 26,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = null,
            ),
        )
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 25,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = null,
            ),
        )
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = false,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = null,
            ),
        )
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = true,
                eligible = false,
                alreadyInPip = false,
                msSinceInternalLaunch = null,
            ),
        )
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = true,
                msSinceInternalLaunch = null,
            ),
        )
    }

    @Test
    fun shouldEnter_respectsInternalLaunchGraceWindow() {
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = 0L,
            ),
        )
        assertFalse(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = OsPipPolicy.INTERNAL_LAUNCH_GRACE_MS,
            ),
        )
        assertTrue(
            OsPipPolicy.shouldEnter(
                sdkInt = 30,
                hasPipFeature = true,
                eligible = true,
                alreadyInPip = false,
                msSinceInternalLaunch = OsPipPolicy.INTERNAL_LAUNCH_GRACE_MS + 1L,
            ),
        )
    }

    @Test
    fun isDismissal_coversFullLifecycleMatrix() {
        // Still in PiP -> never a dismissal
        assertFalse(OsPipPolicy.isDismissal(stillInPip = true, atLeastStarted = true, stoppedWhileInPip = false))
        assertFalse(OsPipPolicy.isDismissal(stillInPip = true, atLeastStarted = true, stoppedWhileInPip = true))
        assertFalse(OsPipPolicy.isDismissal(stillInPip = true, atLeastStarted = false, stoppedWhileInPip = false))
        assertFalse(OsPipPolicy.isDismissal(stillInPip = true, atLeastStarted = false, stoppedWhileInPip = true))

        // Exited PiP while STARTED and not stopped -> expanded back to fullscreen (not dismissal)
        assertFalse(OsPipPolicy.isDismissal(stillInPip = false, atLeastStarted = true, stoppedWhileInPip = false))

        // Exited PiP when stopped or below STARTED -> dismissal
        assertTrue(OsPipPolicy.isDismissal(stillInPip = false, atLeastStarted = false, stoppedWhileInPip = false))
        assertTrue(OsPipPolicy.isDismissal(stillInPip = false, atLeastStarted = true, stoppedWhileInPip = true))
        assertTrue(OsPipPolicy.isDismissal(stillInPip = false, atLeastStarted = false, stoppedWhileInPip = true))
    }
}
