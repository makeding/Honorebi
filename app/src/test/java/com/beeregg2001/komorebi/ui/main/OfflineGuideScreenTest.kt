package com.beeregg2001.komorebi.ui.main

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class OfflineGuideScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun failureReasonKeepsTheActionLayoutStable() {
        val reason = mutableStateOf<String?>(null)
        compose.setContent {
            OfflineGuideScreen(
                hasOfflineCache = false,
                isDeviceOffline = false,
                onContinueOffline = {},
                onRetry = {},
                onOpenSettings = {},
                reason = reason.value,
            )
        }
        val slotWithoutReason = compose.onNodeWithTag("offline-guide-reason").getUnclippedBoundsInRoot()
        val retryWithoutReason = compose.onNodeWithText("再試行").getUnclippedBoundsInRoot()

        reason.value = "サーバーが要求を処理できませんでした。 [BACKEND_HTTP_503; HTTP 503] 接続設定を確認するか、再試行してください。"
        compose.waitForIdle()

        assertEquals(slotWithoutReason, compose.onNodeWithTag("offline-guide-reason").getUnclippedBoundsInRoot())
        assertEquals(retryWithoutReason, compose.onNodeWithText("再試行").getUnclippedBoundsInRoot())
        compose.onNodeWithText("BACKEND_HTTP_503", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }
}
