package com.beeregg2001.komorebi.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.ui.setting.ScreensaverImageFiles
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal fun screensaverImageId(uri: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(uri.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

data class ScreensaverImageItem(
    val id: String,
    val index: Int,
    val byteSize: Long?,
    val owned: Boolean,
)

data class ScreensaverInventoryResponse(
    val images: List<ScreensaverImageItem>,
    val maxImageBytes: Long = ScreensaverImageStore.MAX_IMAGE_BYTES,
    val maxFilesPerUpload: Int = ScreensaverImageStore.MAX_FILES_PER_UPLOAD,
)

class ScreensaverImageStore(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        const val MAX_FILES_PER_UPLOAD = 20
        private const val DIRECTORY = "screensaver-images"
        private const val THUMBNAIL_MAX_SIDE = 480
        private const val THUMBNAIL_FALLBACK_MAX_BYTES = 8L * 1024 * 1024
        private val ID_PATTERN = Regex("[0-9a-f]{24}")
    }

    private val mutex = Mutex()
    private val gson = Gson()
    internal val cacheDir: File get() = context.cacheDir

    suspend fun readUris(): List<String> {
        val json = settingsRepository.screensaverImageUris.first()
        return runCatching {
            gson.fromJson(json, Array<String>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun inventory(): ScreensaverInventoryResponse = withContext(Dispatchers.IO) {
        val uris = readUris()
        val ownedDir = File(context.filesDir, DIRECTORY).canonicalFile
        val items = uris.mapIndexed { idx, uri ->
            val owned = uri.startsWith("file:")
            val byteSize = if (owned) {
                runCatching {
                    val parsed = Uri.parse(uri)
                    val file = parsed.path?.let(::File)?.canonicalFile
                    if (file?.parentFile == ownedDir && file.isFile) file.length() else null
                }.getOrNull()
            } else {
                null
            }
            ScreensaverImageItem(
                id = screensaverImageId(uri),
                index = idx,
                byteSize = byteSize,
                owned = owned,
            )
        }
        ScreensaverInventoryResponse(
            images = items,
            maxImageBytes = MAX_IMAGE_BYTES,
            maxFilesPerUpload = MAX_FILES_PER_UPLOAD,
        )
    }

    suspend fun importUris(uris: List<Uri>): List<String> {
        val imported = ScreensaverImageFiles.import(context, uris)
        return mutex.withLock {
            try {
                val current = readUris()
                settingsRepository.saveString(
                    SettingsRepository.SCREENSAVER_IMAGE_URIS,
                    gson.toJson((current + imported).distinct()),
                )
                imported
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { ScreensaverImageFiles.removeSavedImages(context, imported) }
                }
                throw failure
            }
        }
    }

    suspend fun removeUris(removed: List<String>) {
        if (removed.isEmpty()) return
        mutex.withLock {
            val removedSet = removed.toSet()
            val remaining = readUris().filterNot { it in removedSet }
            settingsRepository.saveString(
                SettingsRepository.SCREENSAVER_IMAGE_URIS,
                gson.toJson(remaining),
            )
            withContext(NonCancellable + Dispatchers.IO) {
                ScreensaverImageFiles.removeSavedImages(context, removed)
            }
        }
    }

    suspend fun removeById(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        return mutex.withLock {
            val current = readUris()
            val target = current.firstOrNull { screensaverImageId(it) == id } ?: return@withLock false
            val remaining = current.filterNot { it == target }
            settingsRepository.saveString(
                SettingsRepository.SCREENSAVER_IMAGE_URIS,
                gson.toJson(remaining),
            )
            withContext(NonCancellable + Dispatchers.IO) {
                ScreensaverImageFiles.removeSavedImages(context, listOf(target))
            }
            true
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            val current = readUris()
            settingsRepository.saveString(SettingsRepository.SCREENSAVER_IMAGE_URIS, "[]")
            withContext(NonCancellable + Dispatchers.IO) {
                ScreensaverImageFiles.removeSavedImages(context, current)
                ScreensaverImageFiles.clearOwnedImages(context)
            }
        }
    }

    suspend fun readImageBytes(id: String, thumbnail: Boolean): Pair<ByteArray, ContentType>? =
        withContext(Dispatchers.IO) {
            if (!ID_PATTERN.matches(id)) return@withContext null
            val uri = readUris().firstOrNull { screensaverImageId(it) == id } ?: return@withContext null
            val parsed = Uri.parse(uri)
            val rawBytes = when (parsed.scheme) {
                "file" -> {
                    val ownedDir = File(context.filesDir, DIRECTORY).canonicalFile
                    val canonicalFile = parsed.path?.let(::File)?.canonicalFile ?: return@withContext null
                    if (canonicalFile.parentFile != ownedDir || !canonicalFile.isFile) {
                        return@withContext null
                    }
                    canonicalFile.readBytes()
                }
                "content" -> {
                    context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                        ?: return@withContext null
                }
                else -> return@withContext null
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val detectedType = mimeToContentType(bounds.outMimeType)

            if (!thumbnail) {
                return@withContext rawBytes to detectedType
            }

            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                var sampleSize = 1
                val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxSide / (sampleSize * 2) >= THUMBNAIL_MAX_SIDE) {
                    sampleSize *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                if (bitmap != null) {
                    try {
                        val out = ByteArrayOutputStream()
                        if (bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)) {
                            return@withContext out.toByteArray() to ContentType.Image.JPEG
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

            if (rawBytes.size <= THUMBNAIL_FALLBACK_MAX_BYTES) {
                rawBytes to detectedType
            } else {
                null
            }
        }

    private fun mimeToContentType(mime: String?): ContentType = when (mime?.lowercase()) {
        "image/png" -> ContentType.Image.PNG
        "image/webp" -> ContentType("image", "webp")
        "image/gif" -> ContentType.Image.GIF
        else -> ContentType.Image.JPEG
    }
}

private class ScreensaverHttpException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : Exception(message)

private suspend fun ApplicationCall.screensaverExpired() {
    response.headers.append("Cache-Control", "no-store")
    respondText(
        """{"code":"SETUP_EXPIRED","message":"テレビで「スマホから画像を管理」を開き直し、表示されたQRコードからこのページを開いてください。"}""",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

private suspend fun ApplicationCall.screensaverError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respondText(
        Gson().toJson(mapOf("code" to code, "message" to message)),
        ContentType.Application.Json,
        status,
    )
}

private suspend fun ApplicationCall.handleScreensaverFailure(error: Exception, fallbackCode: String) {
    if (error is CancellationException) throw error
    when (error) {
        is SetupExpiredException -> screensaverExpired()
        is ScreensaverHttpException -> screensaverError(error.status, error.code, error.message)
        else -> screensaverError(
            HttpStatusCode.InternalServerError,
            fallbackCode,
            "テレビのスクリーンセーバー画像を更新できませんでした。接続を確認して再試行してください。",
        )
    }
}

fun Route.screensaverSetupRoutes(
    session: () -> ConnectionSetupSession?,
    store: ScreensaverImageStore,
    readAsset: (String) -> ByteArray,
) {
    val gson = GsonBuilder().serializeNulls().create()

    get("/screensaver") {
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.respondBytes(readAsset("connection-setup/index.html"), ContentType.Text.Html)
    }

    get("/screensaver/") {
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.respondBytes(readAsset("connection-setup/index.html"), ContentType.Text.Html)
    }

    get("/api/screensaver/images") {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        if (current == null) {
            call.screensaverExpired()
            return@get
        }
        try {
            val inventory = current.authorized(call.request.headers["X-Setup-Token"]) {
                store.inventory()
            }
            call.respondText(gson.toJson(inventory), ContentType.Application.Json)
        } catch (error: Exception) {
            call.handleScreensaverFailure(error, "INVENTORY_FAILED")
        }
    }

    get("/api/screensaver/images/{id}/content") {
        call.response.headers.append("Cache-Control", "private, no-store")
        val current = session()
        if (current == null) {
            call.screensaverExpired()
            return@get
        }
        try {
            val id = call.parameters["id"].orEmpty()
            val thumbnail = call.request.queryParameters["thumbnail"] == "1"
            val result = current.authorized(call.request.headers["X-Setup-Token"]) {
                store.readImageBytes(id, thumbnail)
            }
            if (result == null) {
                call.screensaverError(
                    HttpStatusCode.NotFound,
                    "IMAGE_NOT_FOUND",
                    "指定された画像は見つかりません。一覧を更新してください。",
                )
            } else {
                call.respondBytes(result.first, result.second)
            }
        } catch (error: Exception) {
            call.handleScreensaverFailure(error, "IMAGE_READ_FAILED")
        }
    }

    post("/api/screensaver/images") {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        val token = call.request.headers["X-Setup-Token"]
        if (current == null || !current.accepts(token)) {
            call.screensaverExpired()
            return@post
        }
        if (!call.request.contentType().withoutParameters().match(ContentType.MultiPart.FormData)) {
            call.screensaverError(
                HttpStatusCode.UnsupportedMediaType,
                "UNSUPPORTED_MEDIA_TYPE",
                "画像ファイルを選択して送信してください。",
            )
            return@post
        }

        val tempDir = File(store.cacheDir, "screensaver-upload-${UUID.randomUUID()}")
        if (!tempDir.mkdirs()) {
            call.screensaverError(
                HttpStatusCode.InternalServerError,
                "UPLOAD_FAILED",
                "一時保存先を作成できませんでした。空き容量を確認して再試行してください。",
            )
            return@post
        }

        try {
            val stagedFiles = mutableListOf<File>()
            val multipart = call.receiveMultipart(formFieldLimit = 65L * 1024 * 1024)
            var fileCount = 0
            var uploadFailure: ScreensaverHttpException? = null

            multipart.forEachPart { part ->
                try {
                    if (uploadFailure != null) return@forEachPart
                    if (part is PartData.FileItem) {
                        fileCount++
                        if (fileCount > ScreensaverImageStore.MAX_FILES_PER_UPLOAD) {
                            uploadFailure = ScreensaverHttpException(
                                HttpStatusCode.BadRequest,
                                "TOO_MANY_FILES",
                                "一度に送信できる画像は20枚までです。",
                            )
                            return@forEachPart
                        }

                        val declaredType = part.contentType
                        if (declaredType != null &&
                            !declaredType.contentType.equals("image", ignoreCase = true) &&
                            !declaredType.withoutParameters().match(ContentType.Application.OctetStream)
                        ) {
                            uploadFailure = ScreensaverHttpException(
                                HttpStatusCode.UnsupportedMediaType,
                                "UNSUPPORTED_IMAGE_TYPE",
                                "画像ファイル以外は追加できません。JPEG・PNG・WebPなどの画像を選んでください。",
                            )
                            return@forEachPart
                        }

                        val staged = File(tempDir, "upload-$fileCount")
                        val channel = part.provider()
                        val buffer = ByteArray(8192)
                        var total = 0L
                        withContext(Dispatchers.IO) {
                            staged.outputStream().use { output ->
                                while (true) {
                                    val count = channel.readAvailable(buffer, 0, buffer.size)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    total += count
                                    if (total > ScreensaverImageStore.MAX_IMAGE_BYTES) {
                                        uploadFailure = ScreensaverHttpException(
                                            HttpStatusCode.PayloadTooLarge,
                                            "IMAGE_TOO_LARGE",
                                            "画像は1枚64 MiB以下にしてください。",
                                        )
                                        break
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }

                        if (uploadFailure != null) return@forEachPart
                        if (total == 0L) {
                            uploadFailure = ScreensaverHttpException(
                                HttpStatusCode.BadRequest,
                                "EMPTY_IMAGE",
                                "空のファイルは追加できません。別の画像を選んでください。",
                            )
                            return@forEachPart
                        }
                        stagedFiles += staged
                    }
                } finally {
                    part.dispose()
                }
            }

            uploadFailure?.let { throw it }

            if (stagedFiles.isEmpty()) {
                throw ScreensaverHttpException(
                    HttpStatusCode.BadRequest,
                    "NO_IMAGES",
                    "追加する画像を選択してください。",
                )
            }

            val inventory = current.authorized(token) {
                try {
                    store.importUris(stagedFiles.map(Uri::fromFile))
                } catch (io: IOException) {
                    throw ScreensaverHttpException(
                        HttpStatusCode.BadRequest,
                        "INVALID_IMAGE",
                        io.message ?: "選択したファイルを画像として読み取れません。別の画像を選んでください。",
                    )
                }
                store.inventory()
            }
            call.respondText(gson.toJson(inventory), ContentType.Application.Json)
        } catch (error: Exception) {
            call.handleScreensaverFailure(error, "UPLOAD_FAILED")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    delete("/api/screensaver/images/{id}") {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        if (current == null) {
            call.screensaverExpired()
            return@delete
        }
        try {
            val id = call.parameters["id"].orEmpty()
            val inventory = current.authorized(call.request.headers["X-Setup-Token"]) {
                val removed = store.removeById(id)
                if (!removed) {
                    throw ScreensaverHttpException(
                        HttpStatusCode.NotFound,
                        "IMAGE_NOT_FOUND",
                        "指定された画像は見つかりません。一覧を更新してください。",
                    )
                }
                store.inventory()
            }
            call.respondText(gson.toJson(inventory), ContentType.Application.Json)
        } catch (error: Exception) {
            call.handleScreensaverFailure(error, "DELETE_FAILED")
        }
    }

    delete("/api/screensaver/images") {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        if (current == null) {
            call.screensaverExpired()
            return@delete
        }
        try {
            val inventory = current.authorized(call.request.headers["X-Setup-Token"]) {
                store.clearAll()
                store.inventory()
            }
            call.respondText(gson.toJson(inventory), ContentType.Application.Json)
        } catch (error: Exception) {
            call.handleScreensaverFailure(error, "DELETE_FAILED")
        }
    }
}
