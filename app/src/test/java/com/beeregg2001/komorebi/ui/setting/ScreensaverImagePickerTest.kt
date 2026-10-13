package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
class ScreensaverImagePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteSelectionAcrossFoldersKeepsFocusAndImportsOnlyOnConfirm() {
        val first = Uri.parse("content://images/1")
        val second = Uri.parse("content://images/2")
        var imported: List<Uri>? = null
        var dismissed = false
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    loadEntries = { directory, _ -> if (directory == null) listOf(
                        ScreensaverFileEntry("folder", directory = File("/storage/test")),
                        ScreensaverFileEntry("first", uri = first),
                    ) else listOf(ScreensaverFileEntry("second", uri = second)) },
                    onDismiss = { dismissed = true },
                    onImport = { imported = it },
                )
            }
        }
        compose.onNodeWithText("戻る").assertIsFocused()
        compose.onNodeWithText("first").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("✓  first").assertIsFocused()
        compose.onNodeWithText("▸  folder").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("second").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        assertNull(imported)
        compose.onNodeWithText("追加 (2)").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(listOf(first, second), imported)
            assertTrue(dismissed)
        }
    }

    @Test fun emptyAndFailureKeepActionsVisibleAndCancelDoesNotImport() {
        var fail = false
        var imported = false
        var dismissed = false
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    loadEntries = { _, _ -> if (fail) throw java.io.IOException("USBを読み取れません。再試行してください。") else emptyList() },
                    onDismiss = { dismissed = true }, onImport = { imported = true },
                )
            }
        }
        compose.onNodeWithText("追加 (0)").assertIsNotEnabled()
        val before = compose.onNodeWithText("キャンセル").fetchSemanticsNode().boundsInRoot
        fail = true
        compose.onNodeWithText("再読み込み").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("USBを読み取れません。再試行してください。").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("キャンセル").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("キャンセル").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(dismissed); assertFalse(imported) }
    }
    @Test fun failedImportPreservesSelectionAndAllowsRetry() {
        var attempts = 0
        var dismissed = false
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    loadEntries = { _, _ -> listOf(ScreensaverFileEntry("photo", uri = Uri.parse("content://images/3"))) },
                    onDismiss = { dismissed = true },
                    onImport = { attempts++; if (attempts == 1) throw java.io.IOException("画像を読み取れません。別の画像を選んでください。") },
                )
            }
        }
        compose.onNodeWithText("photo").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("追加 (1)").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("画像を保存できません: 画像を読み取れません。別の画像を選んでください。").assertIsDisplayed()
        compose.onNodeWithText("追加 (1)").assertIsFocused().assertIsEnabled()
        assertFalse(dismissed)
        compose.onNodeWithText("追加 (1)").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(dismissed); assertEquals(2, attempts) }
    }

}
