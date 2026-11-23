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
    val bucketId: String? = null,
    val coverUri: Uri? = null,
    val itemCount: Int? = null,
) {
    val isAlbum: Boolean get() = bucketId != null
    val isFolder: Boolean get() = directory != null
    val isImage: Boolean get() = uri != null && bucketId == null && directory == null
    val key: String get() = when {
        bucketId != null -> "bucket:$bucketId"
        directory != null -> directory.absolutePath
        else -> uri.toString()
    }
}

internal object ScreensaverImageFiles {
    private const val DIRECTORY = "screensaver-images"
    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    const val ALL_IMAGES_BUCKET_ID = "__ALL__"
    private const val DEFAULT_BUCKET_ID = "__DEFAULT__"
    private const val DEFAULT_ALBUM_LABEL = "その他の画像"
    private const val ALL_IMAGES_LABEL = "すべての画像"
    private val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    private data class MediaImageRow(
        val id: Long,
        val displayName: String,
        val bucketId: String,
        val bucketName: String,
        val dateAdded: Long,
        val uri: Uri,
    )

    fun permissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
        sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasFullAccess(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val required = if (sdk >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, required) == PackageManager.PERMISSION_GRANTED
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

    private fun imageCollections(context: Context): List<Uri> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volumes = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull().orEmpty()
            if (volumes.isNotEmpty()) {
                return volumes.map { MediaStore.Images.Media.getContentUri(it) }
            }
        }
        return listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    }

    private fun queryMediaRows(
        context: Context,
        includeBuckets: Boolean,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<MediaImageRow> {
        val projection = if (includeBuckets) {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
            )
        } else {
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
            )
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val rows = mutableListOf<MediaImageRow>()
        val seenUris = mutableSetOf<Uri>()
        var anySucceeded = false

        for (collection in imageCollections(context)) {
            val cursor = context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?: continue
            anySucceeded = true
            cursor.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val bucketIdIdx = if (includeBuckets) c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID) else -1
                val bucketNameIdx = if (includeBuckets) c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    if (!seenUris.add(uri)) continue
                    val displayName = (if (nameIdx >= 0) c.getString(nameIdx) else null)?.takeIf { it.isNotBlank() } ?: "画像"
                    val dateAdded = if (dateIdx >= 0 && !c.isNull(dateIdx)) c.getLong(dateIdx) else 0L
                    val rawBucketId = if (bucketIdIdx >= 0 && !c.isNull(bucketIdIdx)) c.getString(bucketIdIdx) else null
                    val rawBucketName = if (bucketNameIdx >= 0 && !c.isNull(bucketNameIdx)) c.getString(bucketNameIdx) else null
                    val bucketId = rawBucketId?.takeIf { it.isNotBlank() } ?: DEFAULT_BUCKET_ID
                    val bucketName = rawBucketName?.takeIf { it.isNotBlank() } ?: DEFAULT_ALBUM_LABEL
                    rows += MediaImageRow(
                        id = id,
                        displayName = displayName,
                        bucketId = bucketId,
                        bucketName = bucketName,
                        dateAdded = dateAdded,
                        uri = uri,
                    )
                }
            }
        }

        if (!anySucceeded) {
            throw IOException("画像一覧を読み取れません。再試行してください。")
        }

        return rows.sortedWith(compareByDescending<MediaImageRow> { it.dateAdded }.thenByDescending { it.id })
    }

    fun images(context: Context): List<ScreensaverFileEntry> =
        queryMediaRows(context, includeBuckets = false).map { row ->
            ScreensaverFileEntry(name = row.displayName, uri = row.uri)
        }

    fun albums(context: Context): List<ScreensaverFileEntry> {
        val rows = queryMediaRows(context, includeBuckets = true)
        if (rows.isEmpty()) return emptyList()

        data class BucketAcc(
            val bucketId: String,
            var name: String,
            val coverUri: Uri,
            var count: Int,
        )

        val buckets = LinkedHashMap<String, BucketAcc>()
        for (row in rows) {
            val existing = buckets[row.bucketId]
            if (existing == null) {
                buckets[row.bucketId] = BucketAcc(
                    bucketId = row.bucketId,
                    name = row.bucketName,
                    coverUri = row.uri,
                    count = 1,
                )
            } else {
                existing.count += 1
                if (existing.name == DEFAULT_ALBUM_LABEL && row.bucketName != DEFAULT_ALBUM_LABEL) {
                    existing.name = row.bucketName
                }
            }
        }

        return buildList(buckets.size + 1) {
            add(
                ScreensaverFileEntry(
                    name = ALL_IMAGES_LABEL,
                    bucketId = ALL_IMAGES_BUCKET_ID,
                    coverUri = rows.first().uri,
                    itemCount = rows.size,
                ),
            )
            buckets.values.forEach { acc ->
                add(
                    ScreensaverFileEntry(
                        name = acc.name,
                        bucketId = acc.bucketId,
                        coverUri = acc.coverUri,
                        itemCount = acc.count,
                    ),
                )
            }
        }
    }

    fun albumImages(context: Context, bucketId: String): List<ScreensaverFileEntry> {
        if (bucketId == ALL_IMAGES_BUCKET_ID) return images(context)
        val (selection, selectionArgs) = if (bucketId == DEFAULT_BUCKET_ID) {
            "${MediaStore.Images.Media.BUCKET_ID} IS NULL OR ${MediaStore.Images.Media.BUCKET_ID} = ''" to null
        } else {
            "${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId)
        }
        return queryMediaRows(
            context = context,
            includeBuckets = false,
            selection = selection,
            selectionArgs = selectionArgs,
        ).map { row ->
            ScreensaverFileEntry(name = row.displayName, uri = row.uri)
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

    /** Remove private copies only; originals outside our owned directory are never deleted. */
    fun removeSavedImages(context: Context, uris: List<String>) {
        val ownedDirectory = File(context.filesDir, DIRECTORY).canonicalFile
        uris.distinct().forEach { raw ->
            val uri = Uri.parse(raw)
            if (uri.scheme == "file") {
                val file = uri.path?.let(::File)?.canonicalFile
                if (file?.parentFile == ownedDirectory && file.isFile && !file.delete()) {
                    throw IOException("保存した画像ファイルを削除できません。再試行してください。")
                }
            } else if (uri.scheme == "content") {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    fun clearOwnedImages(context: Context) {
        File(context.filesDir, DIRECTORY).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
    }
}
