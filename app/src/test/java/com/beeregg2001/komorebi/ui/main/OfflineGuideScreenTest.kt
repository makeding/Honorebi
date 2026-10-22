package com.beeregg2001.komorebi.ui.main

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
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

    @Test
    fun longReasonOpensScrollableDetailsAndReturnsFocusToTheEntry() {
        val reason = buildString {
            repeat(18) { append("サーバーが要求を処理できませんでした。接続設定を確認して再試行してください。 ") }
            append("（BACKEND_HTTP_503 / HTTP 503）")
        }
        compose.setContent {
            OfflineGuideScreen(
                hasOfflineCache = false,
                isDeviceOffline = false,
                onContinueOffline = {},
                onRetry = {},
                onOpenSettings = {},
                reason = reason,
            )
        }

        val entry = compose.onNodeWithTag("offline-guide-reason-open")
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        entry.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("offline-guide-reason-details").assertIsDisplayed().assertIsFocused()
        compose.onNode(hasText(reason) and hasAnyAncestor(hasTestTag("offline-guide-reason-details")), useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("offline-guide-reason-close").requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("offline-guide-reason-details").assertDoesNotExist()
        entry.assertIsFocused()
    }

    @Test
    fun pendingRetryKeepsItsBoundsAndRejectsDuplicateClicks() {
        val retrying = mutableStateOf(false)
        var retries = 0
        compose.setContent {
            OfflineGuideScreen(
                hasOfflineCache = false,
                isDeviceOffline = false,
                onContinueOffline = {},
                onRetry = { retries++; retrying.value = true },
                onOpenSettings = {},
                reason = "接続できませんでした。（CHANNELS_LOAD_FAILED_NETWORK）",
                isRetrying = retrying.value,
            )
        }

        val retry = compose.onNodeWithText("再試行")
        val idleBounds = retry.getUnclippedBoundsInRoot()
        retry.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }

        val pending = compose.onNodeWithText("接続中…")
        pending.assertIsFocused()
        assertEquals(idleBounds, pending.getUnclippedBoundsInRoot())
        pending.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, retries)

        retrying.value = false
        compose.waitForIdle()
        compose.onNodeWithText("再試行").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(2, retries)
    }

    @Test
    fun allActionsFitInA960By540TvViewport() {
        compose.setContent {
            Box(Modifier.size(960.dp, 540.dp)) {
                OfflineGuideScreen(
                    hasOfflineCache = true,
                    isDeviceOffline = false,
                    onContinueOffline = {},
                    onRetry = {},
                    onOpenSettings = {},
                    reason = "サーバーが要求を処理できませんでした。（BACKEND_HTTP_503 / HTTP 503）",
                )
            }
        }

        compose.onNodeWithText("キャッシュで続行").assertIsDisplayed()
        compose.onNodeWithText("再試行").assertIsDisplayed()
        val settings = compose.onNodeWithText("接続設定を開く").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("settings action must fit the TV viewport: $settings", settings.bottom <= 540.dp)
    }
}
