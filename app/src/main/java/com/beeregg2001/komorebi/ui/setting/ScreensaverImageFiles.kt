package com.beeregg2001.komorebi.ui.setting

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class ScreensaverFileEntry(
    val name: String,
    val directory: File? = null,
    val uri: Uri? = null,
) {
    val key: String get() = directory?.absolutePath ?: uri.toString()
}

internal object ScreensaverImageFiles {
    private const val DIRECTORY = "screensaver-images"
    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    fun permissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun documentIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    fun hasDocumentPicker(context: Context): Boolean {
        val resolved = documentIntent().resolveActivity(context.packageManager) ?: return false
        // Android TV registers a stub that only displays "no app can perform this action".
        return resolved.packageName != "com.android.tv.frameworkpackagestubs"
    }

    fun roots(context: Context): List<ScreensaverFileEntry> {
        val manager = context.getSystemService(StorageManager::class.java)
        val candidates = mutableListOf(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= 30) {
            candidates += manager.storageVolumes.mapNotNull { it.directory }
        } else {
            // getExternalFilesDirs exposes mounted removable roots on older TV firmware.
            candidates += context.getExternalFilesDirs(null).filterNotNull().map {
                File(it.absolutePath.substringBefore("/Android/"))
            }
            candidates += File("/storage").listFiles().orEmpty().filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
        }
        return candidates.distinctBy { it.canonicalPath }.filter { it.isDirectory }.map { file ->
            val volume = manager.getStorageVolume(file)
            ScreensaverFileEntry(volume?.getDescription(context) ?: file.name, directory = file)
        }
    }

    fun listDirectory(directory: File): List<ScreensaverFileEntry> {
        val files = directory.listFiles() ?: throw IOException("このフォルダーを読み取れません。画像へのアクセスを許可して再試行してください。")
        return files.filter { !it.name.startsWith(".") && (it.isDirectory || it.extension.lowercase() in extensions) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { if (it.isDirectory) ScreensaverFileEntry(it.name, directory = it) else ScreensaverFileEntry(it.name, uri = Uri.fromFile(it)) }
    }

    /**
     * Enumerates image files below one user-selected folder without importing anything.
     * Symlinks are allowed only when their canonical target remains inside [directory].
     */
    suspend fun folderImages(directory: File): List<Uri> = withContext(Dispatchers.IO) {
        val collectionContext = coroutineContext
        collectionContext.ensureActive()
        val root = try {
            directory.canonicalFile
        } catch (failure: IOException) {
            throw IOException("選択したフォルダーを読み取れません。アクセスを確認して再試行してください。", failure)
        }
        if (!root.isDirectory || !root.canRead()) {
            throw IOException("選択したフォルダーを読み取れません。アクセスを確認して再試行してください。")
        }

        val rootPath = root.path
        fun isInsideRoot(file: File): Boolean = file.path == rootPath || file.path.startsWith("$rootPath${File.separator}")
        val visitedDirectories = mutableSetOf<String>()
        val imagePaths = linkedSetOf<String>()

        fun collect(folder: File) {
            collectionContext.ensureActive()
            val canonicalFolder = try {
                folder.canonicalFile
            } catch (failure: IOException) {
                throw IOException("フォルダーを読み取れません。アクセスを確認して再試行してください。", failure)
            }
            if (!isInsideRoot(canonicalFolder)) return
            if (!visitedDirectories.add(canonicalFolder.path)) return
            if (!canonicalFolder.canRead()) {
                throw IOException("フォルダーを読み取れません。アクセスを確認して再試行してください。")
            }
            val children = canonicalFolder.listFiles()
                ?: throw IOException("フォルダーを読み取れません。アクセスを確認して再試行してください。")
            children.sortedWith(compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }).forEach { child ->
                collectionContext.ensureActive()
                if (child.name.startsWith(".")) return@forEach
                val canonicalChild = try {
                    child.canonicalFile
                } catch (failure: IOException) {
                    throw IOException("ファイルを読み取れません。アクセスを確認して再試行してください。", failure)
                }
                if (!isInsideRoot(canonicalChild)) return@forEach
                if (canonicalChild.isDirectory) {
                    collect(canonicalChild)
                } else if (
                    canonicalChild.isFile && canonicalChild.canRead() &&
                    canonicalChild.extension.lowercase() in extensions
                ) {
                    imagePaths += canonicalChild.path
                }
            }
        }

        collect(root)
        imagePaths.sortedWith(String.CASE_INSENSITIVE_ORDER).map { Uri.fromFile(File(it)) }
    }

    fun images(context: Context): List<ScreensaverFileEntry> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return buildList {
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(ScreensaverFileEntry(cursor.getString(1) ?: "画像", uri = ContentUris.withAppendedId(collection, cursor.getLong(0))))
                }
            } ?: throw IOException("画像一覧を読み取れません。再試行してください。")
        }
    }

    /** A batch either yields every private copy or removes all copies it created. */
    suspend fun import(context: Context, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, DIRECTORY)
        check(directory.isDirectory || directory.mkdirs()) { "画像の保存先を作成できません。" }
        val created = mutableListOf<File>()
        try {
            uris.distinct().map { uri ->
                coroutineContext.ensureActive()
                val destination = File(directory, UUID.randomUUID().toString())
                created += destination
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("選択した画像を開けません。")
                input.use { source ->
                    destination.outputStream().use { target ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_IMAGE_BYTES) throw IOException("画像は1枚64 MiB以下にしてください。")
                            target.write(buffer, 0, count)
                        }
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(destination.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("選択したファイルを画像として読み取れません。別の画像を選んでください。")
                Uri.fromFile(destination).toString()
            }
        } catch (failure: Throwable) {
            created.forEach { it.delete() }
            throw failure
        }
    }

    fun clearOwnedImages(context: Context) {
        File(context.filesDir, DIRECTORY).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }
}
