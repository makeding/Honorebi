package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CloudflareAccessDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSaveKeepsStableDialogAndRetryClosesIt() {
        var attempts = 0
        var dismissed = false
        compose.setContent {
            CloudflareAccessDialog("client", "secret", { dismissed = true }) { id, secret ->
                assertEquals("client", id)
                assertEquals("secret", secret)
                attempts++
                if (attempts == 1) throw IOException("disk")
            }
        }
        val statusBounds = compose.onNodeWithTag("access-status").getUnclippedBoundsInRoot()
        val fieldBounds = compose.onNodeWithText("Client ID", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val saveBounds = compose.onNodeWithText("保存").getUnclippedBoundsInRoot()
        compose.onNodeWithText("保存").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("端末に設定を書き込めませんでした。空き容量を確認して再試行してください。", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(statusBounds, compose.onNodeWithTag("access-status").getUnclippedBoundsInRoot())
        assertEquals(fieldBounds, compose.onNodeWithText("Client ID", useUnmergedTree = true).getUnclippedBoundsInRoot())
        assertEquals(saveBounds, compose.onNodeWithText("保存").getUnclippedBoundsInRoot())
        compose.onNodeWithText("保存").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(2, attempts)
        assertEquals(true, dismissed)
    }
}
