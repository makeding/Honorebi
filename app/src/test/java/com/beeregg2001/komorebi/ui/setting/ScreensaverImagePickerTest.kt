package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import android.net.Uri
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

    @Test fun albumGridFlowFocusesFirstItemDrillsInSelectsAndRestoresFocusOnBack() {
        val cam1 = Uri.parse("content://images/cam1")
        val cam2 = Uri.parse("content://images/cam2")
        var imported: List<Uri>? = null
        var dismissed = false

        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    requireReadPermission = false,
                    loadAlbums = {
                        listOf(
                            ScreensaverFileEntry(
                                name = "すべての画像",
                                bucketId = ScreensaverImageFiles.ALL_IMAGES_BUCKET_ID,
                                coverUri = cam1,
                                itemCount = 2,
                            ),
                            ScreensaverFileEntry(
                                name = "カメラ",
                                bucketId = "cam",
                                coverUri = cam1,
                                itemCount = 2,
                            ),
                        )
                    },
                    loadAlbumImages = { bucketId ->
                        if (bucketId == "cam") {
                            listOf(
                                ScreensaverFileEntry("cam1.jpg", uri = cam1),
                                ScreensaverFileEntry("cam2.jpg", uri = cam2),
                            )
                        } else {
                            emptyList()
                        }
                    },
                    onDismiss = { dismissed = true },
                    onImport = { imported = it },
                )
            }
        }

        compose.waitForIdle()
        // Initial load focuses first album card (never the Back button).
        compose.onNodeWithText("すべての画像").assertIsFocused()

        // Open the second album ("カメラ") -> focus moves to first child image, NOT "戻る".
        compose.onNodeWithText("カメラ").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("アルバム / カメラ").assertIsDisplayed()
        compose.onNodeWithText("cam1.jpg").assertIsFocused()

        // Toggle selection -> focus stays on the clicked image card.
        compose.onNodeWithText("cam1.jpg").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("cam1.jpg").assertIsFocused()
        compose.onNodeWithContentDescription("選択済み").assertExists()

        // Navigate back -> focus is restored to the previously opened album ("カメラ").
        compose.onNodeWithText("戻る").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("アルバム一覧").assertIsDisplayed()
        compose.onNodeWithText("カメラ").assertIsFocused()

        // Confirm import.
        compose.onNodeWithText("追加 (1)").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals(listOf(cam1), imported)
            assertTrue(dismissed)
        }
    }

    @Test fun enteringEmptyAlbumFocusesBackButton() {
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    requireReadPermission = false,
                    loadAlbums = {
                        listOf(
                            ScreensaverFileEntry("空アルバム", bucketId = "empty", itemCount = 0),
                        )
                    },
                    loadAlbumImages = { emptyList() },
                    onDismiss = {},
                    onImport = {},
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("空アルバム").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("戻る").assertIsFocused()
    }

    @Test fun tabSwitchingKeepsFocusOnClickedTabAndSupportsBatchSelectAndClear() {
        val albumImg = Uri.parse("content://images/a1")
        val usbImg1 = Uri.parse("content://images/u1")
        val usbImg2 = Uri.parse("content://images/u2")

        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    requireReadPermission = false,
                    loadAlbums = {
                        listOf(ScreensaverFileEntry("旅行", bucketId = "trip", coverUri = albumImg, itemCount = 1))
                    },
                    loadAlbumImages = { listOf(ScreensaverFileEntry("trip.jpg", uri = albumImg)) },
                    loadStorageEntries = {
                        listOf(
                            ScreensaverFileEntry("usb1.jpg", uri = usbImg1),
                            ScreensaverFileEntry("usb2.jpg", uri = usbImg2),
                        )
                    },
                    onDismiss = {},
                    onImport = {},
                )
            }
        }

        compose.waitForIdle()
        // Select one image from Albums tab.
        compose.onNodeWithText("旅行").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("trip.jpg").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("追加 (1)").assertIsEnabled()

        // Switch to Storage tab -> focus stays on the "ストレージ" tab pill, selection preserved.
        compose.onNodeWithText("ストレージ").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("ストレージ").assertIsFocused()
        compose.onNodeWithText("usb1.jpg").assertIsDisplayed()
        compose.onNodeWithText("追加 (1)").assertIsEnabled()

        // Batch select all visible images in Storage view -> 1 + 2 = 3 selected.
        compose.onNodeWithText("すべて選択").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("追加 (3)").assertIsEnabled()

        // Clear selection.
        compose.onNodeWithText("選択解除").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("追加 (0)").assertIsNotEnabled()
    }

    @Test fun remoteSelectionAcrossFoldersKeepsFocusAndImportsOnlyOnConfirm() {
        val first = Uri.parse("content://images/1")
        val second = Uri.parse("content://images/2")
        var imported: List<Uri>? = null
        var dismissed = false
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    initialTab = ScreensaverPickerTab.STORAGE,
                    requireReadPermission = false,
                    loadStorageEntries = { directory ->
                        if (directory == null) {
                            listOf(
                                ScreensaverFileEntry("folder", directory = File("/storage/test")),
                                ScreensaverFileEntry("first", uri = first),
                            )
                        } else {
                            listOf(ScreensaverFileEntry("second", uri = second))
                        }
                    },
                    onDismiss = { dismissed = true },
                    onImport = { imported = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("folder").assertIsFocused()
        compose.onNodeWithText("first").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("first").assertIsFocused()
        compose.onNodeWithText("folder").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("second").assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
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
                    initialTab = ScreensaverPickerTab.STORAGE,
                    requireReadPermission = false,
                    loadStorageEntries = {
                        if (fail) throw java.io.IOException("USBを読み取れません。再試行してください。") else emptyList()
                    },
                    onDismiss = { dismissed = true },
                    onImport = { imported = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("追加 (0)").assertIsNotEnabled()
        val before = compose.onNodeWithText("キャンセル").fetchSemanticsNode().boundsInRoot
        fail = true
        compose.onNodeWithText("再読み込み").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("USBを読み取れません。再試行してください。").assertIsDisplayed()
        compose.onNodeWithText("再試行").assertIsDisplayed()
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
                    initialTab = ScreensaverPickerTab.STORAGE,
                    requireReadPermission = false,
                    loadStorageEntries = { listOf(ScreensaverFileEntry("photo", uri = Uri.parse("content://images/3"))) },
                    onDismiss = { dismissed = true },
                    onImport = {
                        attempts++
                        if (attempts == 1) throw java.io.IOException("画像を読み取れません。別の画像を選んでください。")
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("photo").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("追加 (1)").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("画像を保存できません: 画像を読み取れません。別の画像を選んでください。").assertIsDisplayed()
        compose.onNodeWithText("追加 (1)").assertIsFocused().assertIsEnabled()
        assertFalse(dismissed)
        compose.onNodeWithText("追加 (1)").performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertTrue(dismissed); assertEquals(2, attempts) }
    }

    @Test fun folderSelectionAddsAllImagesWithoutDuplicatesAndCanDeselectOne() {
        val first = Uri.parse("content://images/1")
        val second = Uri.parse("content://images/2")
        var imported: List<Uri>? = null
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    initialTab = ScreensaverPickerTab.STORAGE,
                    requireReadPermission = false,
                    loadStorageEntries = { directory ->
                        if (directory == null) {
                            listOf(ScreensaverFileEntry("photos", directory = File("/storage/photos")))
                        } else {
                            listOf(
                                ScreensaverFileEntry("first", uri = first),
                                ScreensaverFileEntry("second", uri = second),
                            )
                        }
                    },
                    loadFolderImages = { listOf(first, second, first) },
                    onDismiss = {},
                    onImport = { imported = it },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("photos").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("このフォルダーを選択").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("追加 (2)").assertIsEnabled()
        compose.onNodeWithText("first").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithText("追加 (1)").requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf(second), imported) }
    }

    @Test fun folderIconAndFocusBoundsStayInsideBrowser() {
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    initialTab = ScreensaverPickerTab.STORAGE,
                    requireReadPermission = false,
                    loadStorageEntries = { listOf(ScreensaverFileEntry("folder", directory = File("/storage/folder"))) },
                    onDismiss = {},
                    onImport = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("フォルダー").assertExists()
        compose.onNodeWithText("folder").requestFocus()
        compose.waitForIdle()
        val cardBounds = compose.onNodeWithTag("screensaver-folder-card").fetchSemanticsNode().boundsInRoot
        val browserBounds = compose.onNodeWithTag("screensaver-image-browser").fetchSemanticsNode().boundsInRoot
        assertTrue(cardBounds.left > browserBounds.left)
        assertTrue(cardBounds.right < browserBounds.right)
        assertTrue(cardBounds.top > browserBounds.top)
        assertTrue(cardBounds.bottom < browserBounds.bottom)
    }

    @Test fun missingPermissionNeverLoadsAlbumsOrStorageBeforeGrant() {
        var albumLoads = 0
        var storageLoads = 0
        compose.setContent {
            KomorebiTheme {
                ScreensaverImageBrowser(
                    loadAlbums = { albumLoads++; emptyList() },
                    loadStorageEntries = { storageLoads++; emptyList() },
                    onDismiss = {},
                    onImport = {},
                )
            }
        }
        compose.runOnIdle {
            assertEquals(0, albumLoads)
            assertEquals(0, storageLoads)
        }
        compose.onNodeWithText("アクセスを許可").assertIsDisplayed()
        compose.onNodeWithText("追加 (0)").assertIsNotEnabled()
    }
}
