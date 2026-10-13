package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class HonomiLogoutDialogTest {
    @get:Rule val compose = createComposeRule()
    private var logoutCount = 0

    private fun openFromAccount() {
        compose.setContent {
            KomorebiTheme {
                var visible by remember { mutableStateOf(false) }
                var loggedIn by remember { mutableStateOf(true) }
                val origin = remember { FocusRequester() }
                LaunchedEffect(Unit) { origin.requestFocus() }
                Button(
                    onClick = { visible = true },
                    modifier = Modifier.testTag("account").focusRequester(origin),
                ) { Text(if (loggedIn) "ログアウト" else "ログイン") }
                if (visible) HonomiLogoutDialog(
                    onConfirm = { logoutCount++; loggedIn = false; visible = false },
                    onDismiss = { visible = false },
                )
            }
        }
        compose.onNodeWithTag("account").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("HonomiTV からログアウトしますか？").assertIsDisplayed()
        compose.onNodeWithText("キャンセル").assertIsFocused()
        assertEquals(0, logoutCount)
    }

    private fun assertCancelled() {
        compose.waitForIdle()
        compose.onNodeWithText("HonomiTV からログアウトしますか？").assertDoesNotExist()
        compose.onNodeWithTag("account").assertIsFocused()
        compose.onNodeWithText("ログアウト").assertIsDisplayed()
        assertEquals(0, logoutCount)
    }

    @Test fun initialCenterCancelsAndReturnsFocusWithoutLogout() {
        openFromAccount()
        compose.onNodeWithText("キャンセル").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        assertCancelled()
    }

    @Test fun backRetainsSessionAndReturnsFocus() {
        openFromAccount()
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        assertCancelled()
    }

    @Test fun dpadConfirmationLogsOutAndKeepsDialogBoundsStable() {
        openFromAccount()
        val title = compose.onNodeWithText("HonomiTV からログアウトしますか？")
        val cancel = compose.onNodeWithText("キャンセル")
        val before = title.getUnclippedBoundsInRoot()
        val cancelBefore = cancel.getUnclippedBoundsInRoot()
        cancel.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.onNodeWithTag("honomi-logout-confirm").assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        cancel.assertIsFocused()
        assertEquals(before, title.getUnclippedBoundsInRoot())
        assertEquals(cancelBefore, cancel.getUnclippedBoundsInRoot())
        cancel.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.onNodeWithTag("honomi-logout-confirm").assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, logoutCount)
        compose.onNodeWithText("HonomiTV からログアウトしますか？").assertDoesNotExist()
        compose.onNodeWithText("ログイン").assertIsDisplayed().assertIsFocused()
    }

    @Test fun repeatedConfirmationCallsLogoutOnlyOnce() {
        compose.setContent { KomorebiTheme { HonomiLogoutDialog({ logoutCount++ }, {}) } }
        repeat(2) {
            compose.onNodeWithTag("honomi-logout-confirm").performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        assertEquals(1, logoutCount)
    }
}
