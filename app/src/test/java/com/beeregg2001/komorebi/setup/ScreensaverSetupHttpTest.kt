package com.beeregg2001.komorebi.setup

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot
import com.beeregg2001.komorebi.data.SettingsRepository
import com.google.gson.Gson
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ScreensaverSetupHttpTest {

    private fun validPngBytes(width: Int = 8, height: Int = 6, color: Int = android.graphics.Color.RED): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Test
    fun uploadReadbackThumbnailsDeletionAndAuthWorkEndToEndAlongsideConnectionRoutes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)
        val store = ScreensaverImageStore(context, repository)
        store.clearAll()

        val gson = Gson()
        var session: ConnectionSetupSession? = ConnectionSetupSession("screensaver-token", source = "SCREENSAVER")
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                connectionSetupRoutes(
                    session = { session },
                    read = { repository.connectionSettings() },
                    save = { it: ConnectionSettingsSnapshot -> repository.saveConnectionSettings(it) },
                    readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
                )
                screensaverSetupRoutes(
                    session = { session },
                    store = store,
                    readAsset = { path -> context.assets.open(path).use { it.readBytes() } },
                )
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = OkHttpClient()

            // 1. HTML entry & shared assets served without Ktor route conflicts
            for (path in listOf("/screensaver", "/screensaver/")) {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute().use { res ->
                    val body = res.body!!.string()
                    assertEquals(body, 200, res.code)
                    assertEquals("no-store", res.header("Cache-Control"))
                    assertEquals("no-referrer", res.header("Referrer-Policy"))
                    assertTrue(body.contains("/connections/assets/"))
                    val assets = Regex("(?:src|href)=\"(/connections/assets/[^\"]+)\"")
                        .findAll(body).map { it.groupValues[1] }.toList()
                    assertTrue(assets.isNotEmpty())
                    assets.forEach { assetPath ->
                        client.newCall(Request.Builder().url("http://127.0.0.1:$port$assetPath").build()).execute().use { assetRes ->
                            assertEquals(assetPath, 200, assetRes.code)
                        }
                    }
                }
            }

            // 2. Auth enforcement (missing, wrong, query-string token rejected)
            fun get(path: String, token: String? = "screensaver-token"): Pair<Int, String> {
                val builder = Request.Builder().url("http://127.0.0.1:$port$path")
                if (token != null) builder.header("X-Setup-Token", token)
                return client.newCall(builder.build()).execute().use { it.code to it.body!!.string() }
            }

            assertEquals(401, get("/api/screensaver/images", token = null).first)
            assertEquals(401, get("/api/screensaver/images", token = "wrong").first)
            assertEquals(401, get("/api/screensaver/images?token=screensaver-token", token = null).first)

            val initial = get("/api/screensaver/images")
            assertEquals(initial.second, 200, initial.first)
            assertTrue(gson.fromJson(initial.second, ScreensaverInventoryResponse::class.java).images.isEmpty())

            // 3. Upload 2 valid PNGs via multipart/form-data
            val png1 = validPngBytes(16, 12, android.graphics.Color.BLUE)
            val png2 = validPngBytes(20, 10, android.graphics.Color.GREEN)
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("images", "first.png", png1.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("images", "second.png", png2.toRequestBody("image/png".toMediaType()))
                .build()
            val uploadRes = client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images")
                    .header("X-Setup-Token", "screensaver-token")
                    .post(uploadBody)
                    .build(),
            ).execute().use { it.code to it.body!!.string() }
            assertEquals(uploadRes.second, 200, uploadRes.first)
            assertFalse("Response must not leak filesystem paths", uploadRes.second.contains("screensaver-images"))

            val uploadedInventory = gson.fromJson(uploadRes.second, ScreensaverInventoryResponse::class.java)
            assertEquals(2, uploadedInventory.images.size)
            uploadedInventory.images.forEach { item ->
                assertTrue(item.id.matches(Regex("[0-9a-f]{24}")))
                assertTrue(item.owned)
                assertNotNull(item.byteSize)
                assertTrue(item.byteSize!! > 0L)
            }
            assertEquals(2, store.readUris().size)

            // 4. Fetch thumbnail & full content
            val firstId = uploadedInventory.images[0].id
            val secondId = uploadedInventory.images[1].id
            client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images/$firstId/content?thumbnail=1")
                    .header("X-Setup-Token", "screensaver-token")
                    .build(),
            ).execute().use { res ->
                assertEquals(200, res.code)
                assertTrue(res.header("Content-Type").orEmpty().startsWith("image/"))
                assertTrue(res.body!!.bytes().isNotEmpty())
            }
            client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images/$firstId/content")
                    .header("X-Setup-Token", "screensaver-token")
                    .build(),
            ).execute().use { res ->
                assertEquals(200, res.code)
                assertTrue(res.header("Content-Type").orEmpty().startsWith("image/"))
                assertTrue(res.body!!.bytes().isNotEmpty())
            }
            assertEquals(404, get("/api/screensaver/images/000000000000000000000000/content").first)

            // 5. Delete single image removes file and updates DataStore
            val savedDir = File(context.filesDir, "screensaver-images")
            assertEquals(2, savedDir.listFiles()?.size)
            val delOne = client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images/$firstId")
                    .header("X-Setup-Token", "screensaver-token")
                    .delete()
                    .build(),
            ).execute().use { it.code to it.body!!.string() }
            assertEquals(delOne.second, 200, delOne.first)
            val afterOne = gson.fromJson(delOne.second, ScreensaverInventoryResponse::class.java)
            assertEquals(listOf(secondId), afterOne.images.map { it.id })
            assertEquals(1, store.readUris().size)
            assertEquals(1, savedDir.listFiles()?.size)

            // Deleting already-deleted ID returns 404 IMAGE_NOT_FOUND
            val delMissing = client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images/$firstId")
                    .header("X-Setup-Token", "screensaver-token")
                    .delete()
                    .build(),
            ).execute().use { it.code to it.body!!.string() }
            assertEquals(404, delMissing.first)
            assertTrue(delMissing.second.contains("IMAGE_NOT_FOUND"))

            // 6. Delete all clears remaining images
            val delAll = client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/screensaver/images")
                    .header("X-Setup-Token", "screensaver-token")
                    .delete()
                    .build(),
            ).execute().use { it.code to it.body!!.string() }
            assertEquals(delAll.second, 200, delAll.first)
            assertTrue(gson.fromJson(delAll.second, ScreensaverInventoryResponse::class.java).images.isEmpty())
            assertTrue(store.readUris().isEmpty())
            assertEquals(0, savedDir.listFiles()?.size ?: 0)

            // 7. Revoking or clearing session invalidates all API endpoints
            session!!.revoke()
            assertEquals(401, get("/api/screensaver/images").first)
            session = null
            assertEquals(401, get("/api/screensaver/images").first)
        } finally {
            session?.revoke()
            server.stop(0, 1000)
        }
    }

    @Test
    fun uploadValidationRejectsInvalidRequestsCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)
        val store = ScreensaverImageStore(context, repository)
        store.clearAll()

        val session = ConnectionSetupSession("validate-token", source = "SCREENSAVER")
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                screensaverSetupRoutes(
                    session = { session },
                    store = store,
                    readAsset = { ByteArray(0) },
                )
            }
        }.start(wait = false)

        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = OkHttpClient()

            fun post(body: okhttp3.RequestBody): Pair<Int, String> =
                client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/api/screensaver/images")
                        .header("X-Setup-Token", "validate-token")
                        .post(body)
                        .build(),
                ).execute().use { it.code to it.body!!.string() }

            // Non-multipart -> 415 UNSUPPORTED_MEDIA_TYPE
            val jsonPost = post("{}".toRequestBody("application/json".toMediaType()))
            assertEquals(415, jsonPost.first)
            assertTrue(jsonPost.second.contains("UNSUPPORTED_MEDIA_TYPE"))

            // Multipart with no file items -> 400 NO_IMAGES
            val noFiles = post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("note", "hello")
                    .build(),
            )
            assertEquals(400, noFiles.first)
            assertTrue(noFiles.second.contains("NO_IMAGES"))

            // Non-image MIME -> 415 UNSUPPORTED_IMAGE_TYPE
            val textPart = post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("images", "note.txt", "hello".toRequestBody("text/plain".toMediaType()))
                    .build(),
            )
            assertEquals(415, textPart.first)
            assertTrue(textPart.second.contains("UNSUPPORTED_IMAGE_TYPE"))

            // Empty 0-byte image -> 400 EMPTY_IMAGE
            val emptyPart = post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("images", "empty.png", ByteArray(0).toRequestBody("image/png".toMediaType()))
                    .build(),
            )
            assertEquals(400, emptyPart.first)
            assertTrue(emptyPart.second.contains("EMPTY_IMAGE"))

            // > 20 files -> 400 TOO_MANY_FILES
            val png = validPngBytes()
            val tooManyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            repeat(21) { i ->
                tooManyBuilder.addFormDataPart("images", "img-$i.png", png.toRequestBody("image/png".toMediaType()))
            }
            val tooMany = post(tooManyBuilder.build())
            assertEquals(400, tooMany.first)
            assertTrue(tooMany.second.contains("TOO_MANY_FILES"))

            assertTrue(store.readUris().isEmpty())
        } finally {
            session.revoke()
            server.stop(0, 1000)
        }
    }
}
