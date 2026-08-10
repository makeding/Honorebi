package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRenderPolicyTest {

    @Test
    fun waitingForSystemReadiness_showsOnlyTheLightweightSplash() {
        val plan = startupRenderPlan(
            isSystemReady = false,
            isSettingsInitialized = true,
            hasConnectionError = false,
            isSyncingInitial = false,
            isPlaybackActive = false,
        )

        assertTrue(plan.showLoading)
        assertFalse(plan.showMainContent)
        assertFalse(plan.showSeasonalDecor)
    }

    @Test
    fun initialSync_keepsHomeAndSeasonalCanvasOutOfTheComposition() {
        val plan = startupRenderPlan(
            isSystemReady = true,
            isSettingsInitialized = true,
            hasConnectionError = false,
            isSyncingInitial = true,
            isPlaybackActive = false,
        )

        assertTrue(plan.showLoading)
        assertFalse(plan.showMainContent)
        assertFalse(plan.showSeasonalDecor)
    }

    @Test
    fun systemReady_entersMainContentWithoutWaitingForHomeContentsCallback() {
        val plan = startupRenderPlan(
            isSystemReady = true,
            isSettingsInitialized = true,
            hasConnectionError = false,
            isSyncingInitial = false,
            isPlaybackActive = false,
        )

        assertFalse(plan.showLoading)
        assertTrue(plan.showMainContent)
        assertTrue(plan.showSeasonalDecor)
    }

    @Test
    fun connectionError_doesNotPlaceTheSplashOverTheOfflineGuide() {
        val plan = startupRenderPlan(
            isSystemReady = false,
            isSettingsInitialized = true,
            hasConnectionError = true,
            isSyncingInitial = false,
            isPlaybackActive = false,
        )

        assertFalse(plan.showLoading)
        assertFalse(plan.showMainContent)
        assertTrue(plan.showSeasonalDecor)
    }
}
