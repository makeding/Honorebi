package com.beeregg2001.komorebi.ui.setting

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ScreensaverImageFilesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val fixtures = File(context.filesDir, "screensaver-image-fixtures").apply { mkdirs() }

    @After fun clean() {
        fixtures.deleteRecursively()
        ScreensaverImageFiles.clearOwnedImages(context)
    }

    private fun image(name: String): File = File(fixtures, name).also { file ->
        file.parentFile?.mkdirs()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun tvStubIsNotAUsableDocumentPicker() {
        val manager = shadowOf(context.packageManager)
        assertFalse(ScreensaverImageFiles.hasDocumentPicker(context))
        val stub = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.tv.frameworkpackagestubs"
                name = "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub"
            }
        }
        manager.addResolveInfoForIntent(ScreensaverImageFiles.documentIntent(), stub)
        assertFalse(ScreensaverImageFiles.hasDocumentPicker(context))
        stub.activityInfo.packageName = "com.android.documentsui"
        stub.activityInfo.name = "com.android.documentsui.picker.PickActivity"
        manager.removeResolveInfosForIntent(ScreensaverImageFiles.documentIntent(), "com.android.tv.frameworkpackagestubs")
        manager.addResolveInfoForIntent(ScreensaverImageFiles.documentIntent(), stub)
        assertTrue(ScreensaverImageFiles.hasDocumentPicker(context))
    }

    @Test fun privateCopiesSurviveOriginalRemovalAndClearPreservesUnrelatedFiles() = runBlocking {
        val source = image("usb.png")
        val unrelated = File(context.filesDir, "unrelated-test.txt").apply { writeText("keep") }
        try {
            val copied = ScreensaverImageFiles.import(context, listOf(Uri.fromFile(source), Uri.fromFile(source)))
            assertEquals(1, copied.size)
            val owned = File(Uri.parse(copied.single()).path!!)
            assertArrayEquals(source.readBytes(), owned.readBytes())
            source.delete()
            assertTrue(owned.isFile)
            ScreensaverImageFiles.clearOwnedImages(context)
            assertFalse(owned.exists())
            assertEquals("keep", unrelated.readText())
        } finally { unrelated.delete() }
    }

    @Test fun invalidBatchRemovesNewCopiesAndKeepsPreviousImports() = runBlocking {
        val good = image("good.png")
        val previous = ScreensaverImageFiles.import(context, listOf(Uri.fromFile(good))).single()
        val bad = File(fixtures, "bad.jpg").apply { writeText("not an image") }
        try {
            ScreensaverImageFiles.import(context, listOf(Uri.fromFile(good), Uri.fromFile(bad)))
            fail("Invalid image should fail the entire batch")
        } catch (_: java.io.IOException) { }
        val owned = File(context.filesDir, "screensaver-images").listFiles()!!.toList()
        assertEquals(listOf(File(Uri.parse(previous).path!!)), owned)
    }

    @Test fun browserShowsFoldersFirstAndFiltersNonImages() {
        File(fixtures, "folder").mkdir()
        image("B.png")
        image("a.jpg")
        File(fixtures, "note.txt").writeText("not selectable")
        File(fixtures, ".hidden.png").writeText("hidden")
        val entries = ScreensaverImageFiles.listDirectory(fixtures)
        assertEquals(listOf("folder", "a.jpg", "B.png"), entries.map { it.name })
        assertNotNull(entries.first().directory)
        assertNotNull(entries.last().uri)
    }

    @Test fun folderImagesCollectsNestedReadableImagesInStableOrder() = runBlocking {
        val nested = File(fixtures, "nested").apply { mkdirs() }
        image("z.png")
        image("nested/a.jpg")
        File(nested, "note.txt").writeText("not an image")
        File(nested, ".hidden.png").writeText("hidden")
        File(fixtures, ".hidden-folder").mkdirs()
        image(".hidden-folder/hidden.jpg")

        val images = ScreensaverImageFiles.folderImages(fixtures)

        assertEquals(
            listOf("nested/a.jpg", "z.png"),
            images.map { File(it.path!!).relativeTo(fixtures.canonicalFile).invariantSeparatorsPath },
        )
    }

    @Test fun folderImagesDeduplicatesCanonicalTargetsAndSkipsSymlinkEscapesWhenAvailable() = runBlocking {
        val actual = image("actual.png")
        val duplicate = File(fixtures, "duplicate.png")
        val outside = File(context.filesDir, "screensaver-outside.png").apply { writeText("outside") }
        try {
            try {
                java.nio.file.Files.createSymbolicLink(duplicate.toPath(), actual.toPath())
                java.nio.file.Files.createSymbolicLink(File(fixtures, "escape.png").toPath(), outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.io.IOException) {
                return@runBlocking
            } catch (_: SecurityException) {
                return@runBlocking
            }

            val images = ScreensaverImageFiles.folderImages(fixtures)

            assertEquals(listOf(actual.canonicalPath), images.map { File(it.path!!).canonicalPath })
        } finally {
            outside.delete()
        }
    }
    @Test fun singleRemovalDeletesOnlySelectedOwnedCopyAndNeverOriginals() = runBlocking {
        val source = image("source.png")
        val copied = ScreensaverImageFiles.import(context, listOf(Uri.fromFile(source), Uri.fromFile(image("other.png"))))
        ScreensaverImageFiles.removeSavedImages(context, listOf(copied[0], Uri.fromFile(source).toString()))
        assertFalse(File(Uri.parse(copied[0]).path!!).exists())
        assertTrue(File(Uri.parse(copied[1]).path!!).isFile)
        assertTrue(source.isFile)
        // Repeating cleanup after persistence is safe.
        ScreensaverImageFiles.removeSavedImages(context, listOf(copied[0]))
    }

    private data class FakeMediaRow(
        val id: Long,
        val displayName: String,
        val bucketId: String?,
        val bucketName: String?,
        val dateAdded: Long,
    )

    private class FakeMediaStoreProvider(private val rows: List<FakeMediaRow>) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
            )
            val filtered = rows.filter { row ->
                when {
                    selection == null -> true
                    selection.contains("IS NULL") -> row.bucketId.isNullOrBlank()
                    selection.contains("= ?") && !selectionArgs.isNullOrEmpty() -> row.bucketId == selectionArgs[0]
                    else -> true
                }
            }.sortedWith(compareByDescending<FakeMediaRow> { it.dateAdded }.thenByDescending { it.id })

            val cursor = MatrixCursor(cols)
            for (row in filtered) {
                val values = Array<Any?>(cols.size) { index ->
                    when (cols[index]) {
                        MediaStore.Images.Media._ID -> row.id
                        MediaStore.Images.Media.DISPLAY_NAME -> row.displayName
                        MediaStore.Images.Media.BUCKET_ID -> row.bucketId
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME -> row.bucketName
                        MediaStore.Images.Media.DATE_ADDED -> row.dateAdded
                        else -> null
                    }
                }
                cursor.addRow(values)
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun registerFakeMediaStore(rows: List<FakeMediaRow>) {
        val provider = FakeMediaStoreProvider(rows)
        val info = android.content.pm.ProviderInfo().apply { authority = MediaStore.AUTHORITY }
        provider.attachInfo(context, info)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @Test fun albumsGroupsByBucketNewestFirstAndPrependsAllImages() {
        registerFakeMediaStore(
            listOf(
                FakeMediaRow(1L, "old_cam.jpg", "cam", "Camera", 100L),
                FakeMediaRow(2L, "usb_shot.png", "usb", "USB Photos", 300L),
                FakeMediaRow(3L, "new_cam.jpg", "cam", "Camera", 250L),
                FakeMediaRow(4L, "orphan.webp", null, null, 200L),
            ),
        )

        val albums = ScreensaverImageFiles.albums(context)
        assertEquals(4, albums.size)

        val all = albums[0]
        assertTrue(all.isAlbum)
        assertFalse(all.isFolder)
        assertFalse(all.isImage)
        assertEquals("すべての画像", all.name)
        assertEquals(ScreensaverImageFiles.ALL_IMAGES_BUCKET_ID, all.bucketId)
        assertEquals("bucket:${ScreensaverImageFiles.ALL_IMAGES_BUCKET_ID}", all.key)
        assertEquals(4, all.itemCount)
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2L), all.coverUri)

        assertEquals(listOf("USB Photos", "Camera", "その他の画像"), albums.drop(1).map { it.name })
        assertEquals(listOf(1, 2, 1), albums.drop(1).map { it.itemCount })
        assertEquals(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 3L), albums[2].coverUri)
    }

    @Test fun albumsReturnsEmptyListWhenMediaStoreHasNoImages() {
        registerFakeMediaStore(emptyList())
        assertTrue(ScreensaverImageFiles.albums(context).isEmpty())
    }

    @Test fun albumImagesFiltersByBucketAndDelegatesAllImages() {
        registerFakeMediaStore(
            listOf(
                FakeMediaRow(1L, "cam_old.jpg", "cam", "Camera", 100L),
                FakeMediaRow(2L, "usb_new.png", "usb", "USB Photos", 300L),
                FakeMediaRow(3L, "cam_new.jpg", "cam", "Camera", 250L),
                FakeMediaRow(4L, "orphan.webp", "", "", 200L),
            ),
        )

        val all = ScreensaverImageFiles.albumImages(context, ScreensaverImageFiles.ALL_IMAGES_BUCKET_ID)
        assertEquals(listOf("usb_new.png", "cam_new.jpg", "orphan.webp", "cam_old.jpg"), all.map { it.name })
        assertTrue(all.all { it.isImage })

        val cam = ScreensaverImageFiles.albumImages(context, "cam")
        assertEquals(listOf("cam_new.jpg", "cam_old.jpg"), cam.map { it.name })

        val other = ScreensaverImageFiles.albumImages(context, "__DEFAULT__")
        assertEquals(listOf("orphan.webp"), other.map { it.name })
    }

    @Test fun hasFullAccessDistinguishesPartialPhotoAccessOnApi34() {
        val shadowApp = shadowOf(context)
        shadowApp.denyPermissions(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        assertFalse(ScreensaverImageFiles.hasFullAccess(context, sdk = 34))

        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertFalse(ScreensaverImageFiles.hasFullAccess(context, sdk = 34))

        shadowApp.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertTrue(ScreensaverImageFiles.hasFullAccess(context, sdk = 34))
    }

}
