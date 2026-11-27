package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            deepTvSettingBounds = Rect(320f, 120f, 760f, 200f)
            commentEngineSettingBounds = Rect(320f, 360f, 760f, 440f)
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

        compose.onNodeWithText("次へ").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("2 / 5").assertExists()
        compose.onNodeWithText(steps[1].title).assertExists()

        compose.onNodeWithText("次へ").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("3 / 5").assertExists()
        compose.onNodeWithText(steps[2].title).assertExists()

        compose.onNodeWithText("次へ").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("4 / 5").assertExists()
        compose.onNodeWithText(steps[3].title).assertExists()

        compose.onNodeWithText("次へ").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithText(steps[4].title).assertExists()

        compose.onNodeWithText("はじめる").performSemanticsAction(SemanticsActions.OnClick)
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

        compose.onNodeWithText("スキップ").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, finishedCount)
    }

    @Test
    fun version121ProvidesAllFiveTourSteps() {
        val steps = UpgradeTourCatalog.tourForVersion(UpgradeTourCatalog.VERSION_CODE_1_2_1)
        assertEquals(5, steps.size)
        assertTrue(steps.all { it.badge.startsWith("Honorebi 1.2.1") })
    }

    @Test
    fun currentAndFuture12xVersionsProvideAllFiveTourSteps() {
        val currentSteps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE)
        assertEquals(5, currentSteps.size)
        assertTrue(currentSteps.all { it.badge.startsWith("Honorebi 1.2 ") })
        assertEquals(
            listOf(
                UpgradeTourTargetId.NAV_RAIL,
                UpgradeTourTargetId.CONTENT_STAGE,
                UpgradeTourTargetId.SYSTEM_STRIP,
                UpgradeTourTargetId.DEEP_TV_SETTING,
                UpgradeTourTargetId.COMMENT_ENGINE_SETTING,
            ),
            currentSteps.map { it.targetId },
        )
        assertTrue(currentSteps[1].description.contains("録画シーク秒数"))
        assertTrue(currentSteps[3].description.contains("画面オフ再生"))
        assertTrue(currentSteps[4].description.contains("実況ワードカウンター"))
        assertEquals(UpgradeTourCatalog.VERSION_CODE_1_2_0, UpgradeTourCatalog.tourIntroducedVersionFor(BuildConfig.VERSION_CODE))

        val nextSteps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE + 1)
        assertEquals(5, nextSteps.size)
        assertTrue(nextSteps.all { it.badge.startsWith("Honorebi 1.2 ") })

        assertTrue(UpgradeTourCatalog.tourForVersion(34).isEmpty())
        assertEquals(null, UpgradeTourCatalog.tourIntroducedVersionFor(34))
    }

    @Test
    fun upgradeFromPre120To127ShowsTourButPatchUpgradeFrom121DoesNotAutoPopup() {
        assertTrue(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = 34,
                currentVersionCode = 42,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = true,
                tourIntroducedVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
            ),
        )
        assertFalse(
            shouldShowUpgradeLandingTour(
                lastSeenVersionCode = 36,
                currentVersionCode = 42,
                isSettingsInitialized = true,
                hasTourForCurrentVersion = true,
                tourIntroducedVersionCode = UpgradeTourCatalog.VERSION_CODE_1_2_0,
            ),
        )
    }

    @Test
    fun manualOpenWithEmptyTourStepsNeverActivatesOverlay() {
        assertFalse(
            shouldActivateUpgradeTourOverlay(
                hasTourSteps = false,
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = false,
                isUpgradeTourControllingSettings = false,
                hasBlockingOverlay = false,
                isManualUpgradeTourOpen = true,
                shouldAutoShowUpgradeTour = false,
            ),
        )
        assertTrue(
            shouldActivateUpgradeTourOverlay(
                hasTourSteps = true,
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = false,
                isUpgradeTourControllingSettings = false,
                hasBlockingOverlay = false,
                isManualUpgradeTourOpen = true,
                shouldAutoShowUpgradeTour = false,
            ),
        )
    }

    @Test
    fun spotlightOverlayTrapsFocusWithDpadNavigationAndBlocksUnderlyingNavRail() {
        val steps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE)
        val controller = LauncherTabController(initialTab = LauncherTab.HOME)
        val upEdgeRequester = FocusRequester()
        val settingsRequester = FocusRequester()
        var showTour by mutableStateOf(true)

        compose.setContent {
            KomorebiTheme {
                CompositionLocalProvider(LocalUpgradeTourActive provides showTour) {
                    Box {
                        LauncherNavRail(
                            controller = controller,
                            isEpgJumping = false,
                            upEdgeFocusRequester = upEdgeRequester,
                            settingsItemFocusRequester = settingsRequester,
                            onTabFocused = { controller.selectTab(it) },
                            onOpenSettings = {},
                            onHasFocusChanged = {},
                            onBack = {},
                        )
                        if (showTour) {
                            UpgradeSpotlightOverlay(
                                steps = steps,
                                onFinish = { showTour = false },
                            )
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("次へ").assertIsFocused()

        // While the spotlight tour is active, underlying nav rail tabs must not accept focus.
        compose.onNodeWithTag("launcher-tab-home").assertIsNotEnabled()
        compose.onNodeWithTag("launcher-tab-home").assert(isNotFocusable())
        compose.runOnIdle {
            assertFalse(controller.tabRequester(LauncherTab.HOME).safeRequestFocus("Test_AttemptSteal"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("次へ").assertIsFocused()

        // Real D-pad navigation moves between Skip and Next, and Right on Next advances the step.
        compose.onNodeWithText("次へ").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("スキップ").assertIsFocused()
        compose.onNodeWithText("スキップ").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("次へ").assertIsFocused()
        compose.onNodeWithText("次へ").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("2 / 5").assertExists()
        compose.onNodeWithText("次へ").assertIsFocused()

        // Back key dismisses the tour and restores focusability to the underlying nav rail.
        compose.onNodeWithText("次へ").performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertFalse(showTour)
        compose.onNodeWithTag("upgrade-spotlight-overlay").assertDoesNotExist()
        compose.onNodeWithTag("launcher-tab-home").assertIsEnabled()
        compose.onNodeWithTag("launcher-tab-home").requestFocus().assertIsFocused()
    }

    @Test
    fun spotlightOverlayBackStepAndFinalStepFinishViaDpad() {
        val steps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE)
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

        compose.waitForIdle()
        repeat(4) {
            compose.onNodeWithText("次へ").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitForIdle()
        }
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithText("はじめる").assertIsFocused()

        // Move left to 戻る, confirm to step back to 4 / 5, then advance again and finish on はじめる.
        compose.onNodeWithText("はじめる").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("戻る").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("4 / 5").assertExists()
        compose.onNodeWithText("次へ").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("5 / 5").assertExists()
        compose.onNodeWithText("はじめる").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        assertEquals(1, finishedCount)
    }

    @Test
    fun spotlightOverlayRecoversFocusWhenClearedAndKeyPressed() {
        val steps = UpgradeTourCatalog.tourForVersion(BuildConfig.VERSION_CODE)
        lateinit var focusManager: FocusManager

        compose.setContent {
            KomorebiTheme {
                focusManager = LocalFocusManager.current
                UpgradeSpotlightOverlay(
                    steps = steps,
                    targets = UpgradeSpotlightTargets(),
                    onFinish = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("次へ").assertIsFocused()

        compose.runOnIdle { focusManager.clearFocus(force = true) }
        compose.onNodeWithTag("upgrade-spotlight-overlay").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("次へ").assertIsFocused()
    }

    @Test
    fun upgradeSpotlightTargetModifierPublishesBoundsOnlyWhenEnabledToCompositionLocal() {
        val targets = UpgradeSpotlightTargets()
        var provideTargets by mutableStateOf(true)
        var isSelected by mutableStateOf(false)

        compose.setContent {
            KomorebiTheme {
                val content: @Composable () -> Unit = {
                    Column {
                        Box(
                            modifier = Modifier
                                .size(68.dp, 48.dp)
                                .testTag("target-nav-rail")
                                .upgradeSpotlightTarget(
                                    targetId = UpgradeTourTargetId.NAV_RAIL,
                                    enabled = isSelected,
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .size(240.dp, 56.dp)
                                .testTag("target-comment-engine")
                                .upgradeSpotlightTarget(UpgradeTourTargetId.COMMENT_ENGINE_SETTING),
                        )
                    }
                }
                if (provideTargets) {
                    CompositionLocalProvider(LocalUpgradeSpotlightTargets provides targets) {
                        content()
                    }
                } else {
                    content()
                }
            }
        }

        compose.waitForIdle()
        assertEquals(Rect.Zero, targets.boundsFor(UpgradeTourTargetId.NAV_RAIL))
        val commentBounds = targets.boundsFor(UpgradeTourTargetId.COMMENT_ENGINE_SETTING)
        assertNotEquals(Rect.Zero, commentBounds)
        assertTrue(commentBounds.width > 0f && commentBounds.height > 0f)

        compose.runOnIdle { isSelected = true }
        compose.waitForIdle()
        val navBounds = targets.boundsFor(UpgradeTourTargetId.NAV_RAIL)
        assertNotEquals(Rect.Zero, navBounds)
        assertTrue(navBounds.width > 0f && navBounds.height > 0f)

        compose.runOnIdle {
            targets.updateBounds(UpgradeTourTargetId.NAV_RAIL, Rect.Zero)
            targets.updateBounds(UpgradeTourTargetId.COMMENT_ENGINE_SETTING, Rect.Zero)
            provideTargets = false
        }
        compose.waitForIdle()
        assertEquals(Rect.Zero, targets.boundsFor(UpgradeTourTargetId.NAV_RAIL))
        assertEquals(Rect.Zero, targets.boundsFor(UpgradeTourTargetId.COMMENT_ENGINE_SETTING))
        compose.onNodeWithTag("target-nav-rail").assertExists()
        compose.onNodeWithTag("target-comment-engine").assertExists()
    }
}
