package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            CloudflareAccessDialog("client", "secret", { dismissed = true }) {
                attempts++
                if (attempts == 1) throw IOException("disk")
            }
        }
        val saveBounds = compose.onNodeWithText("保存").getUnclippedBoundsInRoot()
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("設定の保存に失敗しました。もう一度お試しください。", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(saveBounds, compose.onNodeWithText("保存").getUnclippedBoundsInRoot())
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals(2, attempts)
        assertEquals(true, dismissed)
    }
}
