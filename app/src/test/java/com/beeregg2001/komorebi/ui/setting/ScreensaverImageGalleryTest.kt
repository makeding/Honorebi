package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
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
        compose.setContent { KomorebiTheme { ScreensaverImageGallery(images, onDelete = { fail("Preview must not delete") }) { dismissed = true } } }
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
        compose.setContent { KomorebiTheme { ScreensaverImageGallery(emptyList(), onDelete = { fail("Empty gallery must not delete") }) { dismissed = true } } }
        compose.onNodeWithText("登録画像はありません。設定の「画像を追加」から追加してください").assertIsDisplayed()
        compose.onNodeWithText("閉じる").assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(dismissed) }
    }
    @Test fun cancelAndSingleDeletePreserveOthersAndRestoreNearestFocus() {
        val original = listOf("file:///saved/a.png", "file:///saved/b.png", "file:///saved/c.png")
        val images = mutableStateOf(original)
        val deleted = mutableListOf<List<String>>()
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageGallery(images.value, onDelete = { targets ->
                    deleted += targets
                    images.value = images.value - targets.toSet()
                }, onDismiss = {})
            }
        }
        val bounds = compose.onNodeWithTag("saved-image-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("delete-image-1").requestFocus().requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("画像 2を削除しますか？").assertIsDisplayed()
        compose.onNodeWithText("キャンセル").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("delete-image-1").assertIsFocused()
        compose.runOnIdle { assertEquals(original, images.value); assertTrue(deleted.isEmpty()) }
        assertEquals(bounds, compose.onNodeWithTag("saved-image-0").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("delete-image-1").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("confirm-image-delete").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("saved-image-1").assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf(original[1]), deleted.single())
            assertEquals(listOf(original[0], original[2]), images.value)
        }
        compose.onNodeWithTag("saved-image-1").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("削除").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("confirm-image-delete").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("saved-image-0").assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(original[0]), images.value) }
    }

    @Test fun deleteAllWaitsPreventsDuplicatesAndLeavesCloseFocused() {
        val images = mutableStateOf(listOf("file:///saved/a.png", "file:///saved/b.png"))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var dismissed = false
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageGallery(images.value, onDelete = { targets ->
                    calls++
                    gate.await()
                    images.value = images.value - targets.toSet()
                }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText("全件削除").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("confirm-image-delete").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("削除中…").assertIsDisplayed()
        compose.onNodeWithTag("confirm-image-delete").assertIsNotEnabled()
        compose.onNodeWithText("キャンセル").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls); assertFalse(dismissed); gate.complete(Unit) }
        compose.onNodeWithText("閉じる").assertIsFocused()
        compose.onNodeWithText("全件削除").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(images.value.isEmpty()) }
    }

    @Test fun failedDeletionRetainsReasonAndRetriesExactSelection() {
        val images = mutableStateOf(listOf("file:///saved/a.png"))
        var calls = 0
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageGallery(images.value, onDelete = { targets ->
                    calls++
                    if (calls == 1) throw java.io.IOException("保存先に書き込めません")
                    images.value = images.value - targets.toSet()
                }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("delete-image-0").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("confirm-image-delete").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("削除できません: 保存先に書き込めません").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, images.value.size) }
        compose.onNodeWithText("再試行").assertIsFocused().requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("閉じる").assertIsFocused()
        compose.runOnIdle { assertEquals(2, calls); assertTrue(images.value.isEmpty()) }
    }

}
