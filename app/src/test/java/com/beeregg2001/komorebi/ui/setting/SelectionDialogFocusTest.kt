package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.input.key.Key
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.ui.components.ExitDialog
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SelectionDialogFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timeFormatStartsOn24AndUpSelects28DownReaches12() {
        var selected: String? = null
        compose.setContent {
            KomorebiTheme {
                SelectionDialog(
                    title = "時刻の表示形式",
                    options = TIME_FORMAT_OPTIONS,
                    current = "24H",
                    onDismiss = {},
                    onSelect = { selected = it }
                )
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val row24 = compose.onNodeWithText("24時間表記")
        val row28 = compose.onNodeWithText("28時間表記 (24:00–27:59)")
        val row12 = compose.onNodeWithText("12時間表記 (AM/PM)")
        row24.assertIsFocused().performKeyInput {
            keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
        }
        row12.assertIsFocused().assertIsDisplayed().performKeyInput {
            keyDown(Key.DirectionUp); keyUp(Key.DirectionUp)
        }
        row24.assertIsFocused().performKeyInput {
            keyDown(Key.DirectionUp); keyUp(Key.DirectionUp)
        }
        row28.assertIsFocused().assertIsDisplayed().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        assertEquals("28H", selected)
    }

    @Test fun selectionRowKeepsMeasuredBoundsAcrossFocusAndSelection() {
        compose.setContent {
            KomorebiTheme {
                var selected by remember { mutableStateOf(false) }
                SelectionDialogItem(label = "選択済み", isSelected = selected, onClick = { selected = !selected })
            }
        }
        val row = compose.onNodeWithTag("selection-item-選択済み")
        val before = row.getUnclippedBoundsInRoot()
        row.requestFocus().assertIsFocused()
        compose.waitForIdle()
        assertEquals(before, row.getUnclippedBoundsInRoot())
        row.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        row.assertIsFocused()
        assertEquals(before, row.getUnclippedBoundsInRoot())
    }

    @Test fun exitBackDismissesAndReturnsFocusToOrigin() {
        compose.setContent {
            KomorebiTheme {
                var visible by remember { mutableStateOf(false) }
                val origin = remember { FocusRequester() }
                LaunchedEffect(Unit) { origin.requestFocus() }
                Button(onClick = { visible = true }, modifier = Modifier.testTag("exit-origin").focusRequester(origin)) {
                    Text("終了を開く")
                }
                if (visible) ExitDialog(onConfirm = {}, onDismiss = { visible = false })
            }
        }
        compose.onNodeWithTag("exit-origin").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("終了").assertIsFocused()
        // Compose key injection targets a View; Back dismissal belongs to the native Dialog window.
        compose.runOnIdle {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
        compose.onNodeWithText("終了").assertDoesNotExist()
        compose.onNodeWithTag("exit-origin").assertIsFocused()
    }
}
