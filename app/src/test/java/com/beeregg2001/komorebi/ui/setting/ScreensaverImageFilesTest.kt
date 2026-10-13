package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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

}
