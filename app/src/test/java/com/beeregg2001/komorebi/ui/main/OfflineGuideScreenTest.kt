package com.beeregg2001.komorebi.ui.main

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
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
                onOpenSettings = {},
                reason = reason.value,
            )
        }
        val slotWithoutReason = compose.onNodeWithTag("offline-guide-reason").getUnclippedBoundsInRoot()
        val settingsWithoutReason = compose.onNodeWithText("接続設定を開く").getUnclippedBoundsInRoot()

        reason.value = "サーバーが要求を処理できませんでした。 [BACKEND_HTTP_503; HTTP 503] 接続設定を確認するか、再試行してください。"
        compose.waitForIdle()

        assertEquals(slotWithoutReason, compose.onNodeWithTag("offline-guide-reason").getUnclippedBoundsInRoot())
        assertEquals(settingsWithoutReason, compose.onNodeWithText("接続設定を開く").getUnclippedBoundsInRoot())
        compose.onNodeWithText("BACKEND_HTTP_503", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun reasonIsInlineAndOnlyUsefulActionsRemain() {
        var settingsOpens = 0
        compose.setContent {
            OfflineGuideScreen(
                hasOfflineCache = false,
                isDeviceOffline = true,
                onContinueOffline = {},
                onOpenSettings = { settingsOpens++ },
                reason = "接続がありません。（DEVICE_OFFLINE）",
            )
        }
        compose.onNodeWithText("DEVICE_OFFLINE", substring = true).assertIsDisplayed()
        compose.onNodeWithText("詳細を表示").assertDoesNotExist()
        compose.onNodeWithTag("offline-guide-reason-open").assertDoesNotExist()
        compose.onNodeWithText("再試行").assertDoesNotExist()
        compose.onNodeWithText("接続設定を開く").requestFocus()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, settingsOpens)
    }

    @Test
    fun longReasonScrollsInPlaceWithTheRemote() {
        val reason = "サーバーが応答しません。（HTTP_503）\n".repeat(24)
        compose.setContent {
            OfflineGuideScreen(
                hasOfflineCache = false,
                isDeviceOffline = false,
                onContinueOffline = {},
                onOpenSettings = {},
                reason = reason,
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val settingsBounds = compose.onNodeWithText("接続設定を開く").getUnclippedBoundsInRoot()
        val scroll = compose.onNode(hasScrollAction())
        scroll.requestFocus().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals(settingsBounds, compose.onNodeWithText("接続設定を開く").getUnclippedBoundsInRoot())
        compose.onNodeWithText("詳細を表示").assertDoesNotExist()
    }

    @Test
    fun allActionsFitInA960By540TvViewport() {
        compose.setContent {
            Box(Modifier.size(960.dp, 540.dp)) {
                OfflineGuideScreen(
                    hasOfflineCache = true,
                    isDeviceOffline = false,
                    onContinueOffline = {},
                    onOpenSettings = {},
                    reason = "サーバーが要求を処理できませんでした。（BACKEND_HTTP_503 / HTTP 503）",
                )
            }
        }

        compose.onNodeWithText("キャッシュで続行").assertIsDisplayed()
        compose.onNodeWithText("再試行").assertDoesNotExist()
        val settings = compose.onNodeWithText("接続設定を開く").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("settings action must fit the TV viewport: $settings", settings.bottom <= 540.dp)
    }
}
