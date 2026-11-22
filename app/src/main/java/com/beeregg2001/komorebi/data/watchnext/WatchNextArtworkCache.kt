package com.beeregg2001.komorebi.data.watchnext

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class PreviewArtworkKind(val segment: String) {
    RECORDING("rec"),
    LIVE("live"),
}

@Singleton
class WatchNextArtworkCache @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("image") private val imageHttpClient: dagger.Lazy<OkHttpClient>,
) {
    suspend fun ensure(id: Int, urls: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (id <= 0) return@withContext false
        val dir = directory(context)
        val target = file(context, id)
        val tmp = File(dir, "$id.tmp")
        download(dir = dir, target = target, tmp = tmp, urls = urls, logLabel = "WatchNext artwork id=$id")
    }

    suspend fun ensurePreview(
        kind: PreviewArtworkKind,
        key: String,
        urls: List<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!PREVIEW_KEY_REGEX.matches(key)) return@withContext false
        val dir = previewDirectory(context, kind)
        val target = previewFile(context, kind, key)
        val tmp = File(dir, "$key.tmp")
        download(
            dir = dir,
            target = target,
            tmp = tmp,
            urls = urls,
            logLabel = "Preview artwork kind=${kind.segment} key=$key",
        )
    }

    private fun download(
        dir: File,
        target: File,
        tmp: File,
        urls: List<String>,
        logLabel: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (target.isFile && target.length() > 0L) {
            val ageMillis = now - target.lastModified()
            if (ageMillis in 0L until CACHE_TTL_MILLIS) {
                return true
            }
        }
        if (urls.isEmpty()) {
            return target.isFile && target.length() > 0L
        }

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val client = imageHttpClient.get()
            .newBuilder()
            .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        for (url in urls) {
            val request = runCatching {
                Request.Builder().url(url).get().build()
            }.getOrNull() ?: continue

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body
                    val contentType = body.contentType()
                    val headerType = response.header("Content-Type")
                    val isImage = contentType?.type.equals("image", ignoreCase = true) ||
                        (contentType == null && headerType?.trim()?.startsWith("image/", ignoreCase = true) == true)
                    if (!isImage) return@use

                    val contentLength = body.contentLength()
                    if (contentLength > MAX_IMAGE_BYTES) return@use

                    var totalBytes = 0L
                    var exceededLimit = false
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > MAX_IMAGE_BYTES) {
                                    exceededLimit = true
                                    break
                                }
                                output.write(buffer, 0, read)
                            }
                            if (!exceededLimit) {
                                output.flush()
                            }
                        }
                    }

                    if (exceededLimit || totalBytes <= 0L) {
                        tmp.delete()
                        return@use
                    }

                    val renamed = tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
                    if (renamed) {
                        target.setLastModified(System.currentTimeMillis())
                        return true
                    } else {
                        tmp.delete()
                    }
                }
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (t: Throwable) {
                tmp.delete()
                Log.w(TAG, "Failed to fetch $logLabel: ${t.message}")
            }
        }

        return target.isFile && target.length() > 0L
    }

    fun prune(keep: Set<Int>) {
        val dir = directory(context)
        val files = dir.listFiles() ?: return
        for (entry in files) {
            val name = entry.name
            val keptId = if (name.endsWith(IMAGE_EXTENSION)) {
                name.removeSuffix(IMAGE_EXTENSION).toIntOrNull()
            } else {
                null
            }
            if (keptId == null || keptId !in keep) {
                entry.delete()
            }
        }
    }

    fun prunePreview(kind: PreviewArtworkKind, keep: Set<String>) {
        val dir = previewDirectory(context, kind)
        val files = dir.listFiles() ?: return
        for (entry in files) {
            val name = entry.name
            val keptKey = if (name.endsWith(IMAGE_EXTENSION)) {
                name.removeSuffix(IMAGE_EXTENSION)
            } else {
                null
            }
            if (keptKey == null || keptKey !in keep) {
                entry.delete()
            }
        }
    }

    companion object {
        private const val TAG = "WatchNext"
        internal const val ART_DIR_NAME = "watch_next_art"
        internal const val PREVIEW_ART_DIR_NAME = "preview_channel_art"
        internal val PREVIEW_KEY_REGEX = Regex("[0-9a-z]{1,40}")
        private const val IMAGE_EXTENSION = ".img"
        private const val CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val FETCH_TIMEOUT_SECONDS = 10L
        private const val MAX_IMAGE_BYTES = 2L * 1024L * 1024L

        internal fun directory(context: Context): File = File(context.filesDir, ART_DIR_NAME)

        fun file(context: Context, id: Int): File = File(directory(context), "$id$IMAGE_EXTENSION")

        fun posterUri(context: Context, id: Int): Uri =
            Uri.parse("content://${context.packageName}.watchnext.art/rec/$id")

        internal fun previewDirectory(context: Context, kind: PreviewArtworkKind): File =
            File(File(context.filesDir, PREVIEW_ART_DIR_NAME), kind.segment)

        fun previewFile(context: Context, kind: PreviewArtworkKind, key: String): File =
            File(previewDirectory(context, kind), "$key$IMAGE_EXTENSION")

        fun previewPosterUri(context: Context, kind: PreviewArtworkKind, key: String): Uri =
            Uri.parse("content://${context.packageName}.watchnext.art/preview/${kind.segment}/$key")
    }
}
