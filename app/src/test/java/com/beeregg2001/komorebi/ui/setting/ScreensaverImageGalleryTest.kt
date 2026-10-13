package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
class ScreensaverImageGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remotePreviewReturnsToSelectedThumbnailWithoutChangingSavedImages() {
        val images = listOf("file:///saved/first.png", "file:///saved/second.png", "file:///saved/third.png")
        var dismissed = false
        compose.setContent { KomorebiTheme { ScreensaverImageGallery(images) { dismissed = true } } }
        compose.onNodeWithTag("saved-image-0").assertIsFocused()
        compose.onNodeWithTag("saved-image-1").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("saved-image-preview").assertExists()
        compose.onNodeWithText("画像 2 / 3").assertIsDisplayed()
        compose.onNodeWithText("次へ").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("画像 3 / 3").assertIsDisplayed()
        compose.onNodeWithText("次へ").assertIsNotEnabled()
        compose.onNodeWithText("一覧に戻る").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("saved-image-2").assertIsFocused()
        compose.onNodeWithText("閉じる").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(listOf("file:///saved/first.png", "file:///saved/second.png", "file:///saved/third.png"), images)
        }
    }

    @Test fun emptyInventoryKeepsCloseAvailable() {
        var dismissed = false
        compose.setContent { KomorebiTheme { ScreensaverImageGallery(emptyList()) { dismissed = true } } }
        compose.onNodeWithText("登録画像はありません。設定の「画像を追加」から追加してください").assertIsDisplayed()
        compose.onNodeWithText("閉じる").assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
