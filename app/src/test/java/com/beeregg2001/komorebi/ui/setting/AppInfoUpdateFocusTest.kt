package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.components.LocalShowToast
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.ui.main.MainRootState
import com.beeregg2001.komorebi.ui.main.RobustUpdateDialog
import com.beeregg2001.komorebi.ui.main.UpgradeTourHostState
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h1080dp-land")
class AppInfoUpdateFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun laterAndBackReturnToActualCheckRowWithoutStealingSubsequentFocus() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            KomorebiTheme {
                var state: UpdateState by remember { mutableStateOf(UpdateState.Idle) }
                val rows = remember { List(8) { FocusRequester() } }
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                Box {
                    AppInfoContent(
                        receiveBetaUpdates = true, updateState = state,
                        onToggleBetaUpdates = {},
                        onCheckUpdates = { state = UpdateState.UpdateAvailable("test", "notes", "test") },
                        onScrollToTop = {}, onShowProject = {}, onShowLicenses = {}, onCapabilities = {},
                        versionR = rows[0], betaR = rows[1], checkR = rows[2],
                        capabilityR = rows[3], projectR = rows[4], licR = rows[5], sidebarR = rows[6],
                        whatsNewR = rows[7],
                        onClick = {},
                    )
                    if (state is UpdateState.UpdateAvailable) {
                        RobustUpdateDialog("test", "notes", {}, { state = UpdateState.Idle })
                    }
                }
            }
        }
        val check = compose.onNodeWithText("更新を確認")
        repeat(2) { attempt ->
            check.requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("ダウンロード").assertIsFocused()
            if (attempt == 0) {
                compose.onNodeWithText("後で").requestFocus().performKeyInput {
                    keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
                }
            } else {
                compose.runOnIdle { back.onBackPressed() }
            }
            compose.mainClock.advanceTimeBy(500)
            check.assertIsFocused()
            check.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
            compose.onNodeWithText("テレビ再生能力").assertIsFocused()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("テレビ再生能力").assertIsFocused()
        }
    }

    @Test fun installerReturnRestoresFocusEvenWhenUpdaterWasAlreadyIdle() {
        var state: UpdateState by mutableStateOf(UpdateState.UpdateAvailable("test", "notes", "test"))
        var windowFocused by mutableStateOf(true)
        lateinit var manager: FocusManager
        lateinit var check: FocusRequester
        compose.setContent {
            KomorebiTheme {
                check = remember { FocusRequester() }
                val other = remember { FocusRequester() }
                manager = LocalFocusManager.current
                AppInfoFocusReturnEffect(state, check, true, check, windowFocused)
                Column {
                    Button({}, Modifier.focusRequester(other)) { Text("other") }
                    Button({}, Modifier.focusRequester(check)) { Text("check") }
                }
            }
        }
        compose.runOnIdle { state = UpdateState.Downloading(0) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("check").assertIsFocused()
        // The updater resets to Idle as soon as it launches Android's installer.
        compose.runOnIdle { state = UpdateState.Idle }
        compose.runOnIdle { windowFocused = false; manager.clearFocus(force = true) }
        compose.onNodeWithText("check").assertIsNotFocused()
        compose.runOnIdle { windowFocused = true }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("check").assertIsFocused()
        compose.onNodeWithText("other").requestFocus()
        compose.runOnIdle { state = UpdateState.UpToDate }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("other").assertIsFocused()
    }

    @Test fun whatsNewRowIsReachableByDpadFromVersionRowAndInvokesCallback() {
        var whatsNewClicks = 0
        compose.setContent {
            KomorebiTheme {
                val rows = remember { List(8) { FocusRequester() } }
                AppInfoContent(
                    receiveBetaUpdates = true,
                    updateState = UpdateState.Idle,
                    onToggleBetaUpdates = {},
                    onCheckUpdates = {},
                    onScrollToTop = {},
                    onShowProject = {},
                    onShowLicenses = {},
                    onCapabilities = {},
                    onOpenWhatsNew = { whatsNewClicks++ },
                    versionR = rows[0],
                    betaR = rows[1],
                    checkR = rows[2],
                    capabilityR = rows[3],
                    projectR = rows[4],
                    licR = rows[5],
                    sidebarR = rows[6],
                    whatsNewR = rows[7],
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("Version").requestFocus().assertIsFocused()
        compose.onNodeWithText("Version").performKeyInput {
            keyDown(Key.DirectionDown)
            keyUp(Key.DirectionDown)
        }
        compose.onNodeWithText("What's New").assertIsFocused()
        compose.onNodeWithText("What's New").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals(1, whatsNewClicks)
        }
    }

    @Test fun settingsInitialFocusEffectWaitsUntilUpgradeTourOverlayDismissesAndRunsOnce() {
        var overlayActive by mutableStateOf(true)
        var requestCount = 0

        compose.setContent {
            CompositionLocalProvider(LocalUpgradeTourActive provides overlayActive) {
                SettingsInitialFocusEffect(
                    onRequestInitialFocus = { requestCount++ },
                )
            }
        }

        compose.runOnIdle { assertEquals(0, requestCount) }

        compose.runOnIdle { overlayActive = false }
        compose.runOnIdle { assertEquals(1, requestCount) }

        compose.runOnIdle { overlayActive = true }
        compose.runOnIdle { overlayActive = false }
        compose.runOnIdle { assertEquals(1, requestCount) }
    }

    @Test fun whatsNewUnavailableRoutesThroughUpgradeTourHostStateAndLocalShowToast() {
        val rootState = MainRootState()
        val tourState = UpgradeTourHostState(steps = emptyList(), introducedVersionCode = null)
        var closeSettingsCalls = 0

        compose.setContent {
            KomorebiTheme {
                CompositionLocalProvider(LocalShowToast provides rootState::showToast) {
                    val showToast = LocalShowToast.current
                    val rows = remember { List(8) { FocusRequester() } }
                    Box {
                        AppInfoContent(
                            receiveBetaUpdates = true,
                            updateState = UpdateState.Idle,
                            onToggleBetaUpdates = {},
                            onCheckUpdates = {},
                            onScrollToTop = {},
                            onShowProject = {},
                            onShowLicenses = {},
                            onCapabilities = {},
                            onOpenWhatsNew = {
                                tourState.openManually(
                                    onCloseSettings = { closeSettingsCalls++ },
                                    onUnavailable = {
                                        showToast("このバージョンの新機能ガイドはありません")
                                    },
                                )
                            },
                            versionR = rows[0],
                            betaR = rows[1],
                            checkR = rows[2],
                            capabilityR = rows[3],
                            projectR = rows[4],
                            licR = rows[5],
                            sidebarR = rows[6],
                            whatsNewR = rows[7],
                            onClick = {},
                        )
                        GlobalToast(message = rootState.toastMessage)
                    }
                }
            }
        }

        compose.onNodeWithText("What's New").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals(0, closeSettingsCalls)
        assertEquals("このバージョンの新機能ガイドはありません", rootState.toastMessage)
        compose.onNodeWithText("このバージョンの新機能ガイドはありません").assertExists()
    }
}
