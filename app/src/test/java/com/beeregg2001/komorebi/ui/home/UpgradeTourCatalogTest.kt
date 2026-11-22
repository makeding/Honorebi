package com.beeregg2001.komorebi.ui.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class UpgradeTourCatalogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun freshInstallDoesNotShowUpgradeTour() {
        assertFalse(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = null,
                currentVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
                isSettingsInitialized = false,
                hasTourForCurrentVersion = true,
            ),
        )
    }

    @Test
    fun upgradeFromPreVersionTrackingShowsUpgradeTour() {
        assertTrue(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = null,
                currentVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = true,
            ),
        )
    }

    @Test
    fun upgradeFromOlderVersionCodeShowsUpgradeTour() {
        assertTrue(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = 34,
                currentVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = true,
            ),
        )
    }

    @Test
    fun sameVersionDoesNotShowUpgradeTour() {
        assertFalse(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
                currentVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = true,
            ),
        )
    }

    @Test
    fun versionWithoutTourDoesNotShowUpgradeTour() {
        assertFalse(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = 35,
                currentVersionCode = 36,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = false,
            ),
        )
    }

    @Test
    fun spotlightOverlayStepsThroughAll120HighlightsAndCallsOnFinish() {
        val steps = UpgradeTourCatalog.tourForVersion(UpgradeTourCatalog.VERSION_CODE_1_2_0)
        assertEquals(5, steps.size)

        var finishedCount = 0
        val targets = UpgradeSpotlightTargets().apply {
            navRailBounds = Rect(0f, 0f, 68f, 540f)
            systemStripBounds = Rect(640f, 12f, 936f, 68f)
            contentStageBounds = Rect(68f, 0f, 960f, 540f)
            settingsButtonBounds = Rect(0f, 470f, 68f, 520f)
        }

        compose.setContent {
            KomorebiTheme {
                UpgradeSpotlightOverlay(
                    steps = steps,
                    targets = targets,
                    onFinish = { finishedCount++ },
                )
            }
        }

        compose.onNodeWithTag("upgrade-spotlight-overlay").assertExists()
        compose.onNodeWithText("1 / 5").assertExists()
        compose.onNodeWithText(steps[0].title).assertExists()

        compose.onNodeWithText("次へ").performClick()
        compose.onNodeWithText("2 / 5").assertExists()
        compose.onNodeWithText(steps[1].title).assertExists()

        compose.onNodeWithText("次へ").performClick()
        compose.onNodeWithText("3 / 5").assertExists()
        compose.onNodeWithText(steps[2].title).assertExists()

        compose.onNodeWithText("次へ").performClick()
        compose.onNodeWithText("4 / 5").assertExists()
        compose.onNodeWithText(steps[3].title).assertExists()

        compose.onNodeWithText("次へ").performClick()
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithText(steps[4].title).assertExists()

        compose.onNodeWithText("はじめる").performClick()
        assertEquals(1, finishedCount)
    }

    @Test
    fun spotlightOverlaySkipCallsOnFinishImmediately() {
        val steps = UpgradeTourCatalog.tourForVersion(UpgradeTourCatalog.VERSION_CODE_1_2_0)
        var finishedCount = 0

        compose.setContent {
            KomorebiTheme {
                UpgradeSpotlightOverlay(
                    steps = steps,
                    targets = UpgradeSpotlightTargets(),
                    onFinish = { finishedCount++ },
                )
            }
        }

        compose.onNodeWithText("スキップ").performClick()
        assertEquals(1, finishedCount)
    }
}
