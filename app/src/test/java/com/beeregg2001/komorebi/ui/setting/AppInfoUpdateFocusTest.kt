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
import com.beeregg2001.komorebi.ui.main.RobustUpdateDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.util.UpdateState
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
                val rows = remember { List(7) { FocusRequester() } }
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                Box {
                    AppInfoContent(
                        receiveBetaUpdates = true, updateState = state,
                        onToggleBetaUpdates = {},
                        onCheckUpdates = { state = UpdateState.UpdateAvailable("test", "notes", "test") },
                        onScrollToTop = {}, onShowProject = {}, onShowLicenses = {}, onCapabilities = {},
                        versionR = rows[0], betaR = rows[1], checkR = rows[2],
                        capabilityR = rows[3], projectR = rows[4], licR = rows[5], sidebarR = rows[6],
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
}
