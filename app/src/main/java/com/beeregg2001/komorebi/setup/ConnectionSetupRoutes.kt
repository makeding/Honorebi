package com.beeregg2001.komorebi.setup

import com.beeregg2001.komorebi.data.ConnectionSettingsSnapshot
import com.google.gson.GsonBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import io.ktor.server.application.ApplicationCall

internal suspend fun ApplicationCall.setupExpired() {
    response.headers.append("Cache-Control", "no-store")
    respondText("""{"code":"SETUP_EXPIRED","message":"テレビで「スマホで設定」を開き直し、表示されたQRコードからこのページを開いてください。"}""",
        ContentType.Application.Json, HttpStatusCode.Unauthorized)
}

internal suspend fun ApplicationCall.setupFailure(error: Exception, code: String = "SAVE_FAILED") {
    if (error is CancellationException) throw error
    if (error is SetupExpiredException) { setupExpired(); return }
    val invalid = error is IllegalArgumentException || error is com.google.gson.JsonParseException || error is NullPointerException
    val message = if (error is IllegalArgumentException && error !is com.google.gson.JsonParseException)
        error.message ?: "入力内容を確認してください。"
        else if (invalid) "入力内容を確認して再送信してください。"
        else error.message?.takeIf { it.matches(Regex(".*\\(HTTP [0-9]{3}\\).*")) }
            ?: "テレビに設定を反映できませんでした。接続を確認して再試行してください。"
    respondText(com.google.gson.Gson().toJson(mapOf("code" to if (invalid) "INVALID_CONNECTION" else code, "message" to message)),
        ContentType.Application.Json, if (invalid) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError)
}


// A QR session grants access only while its TV setup surface is open.
class ConnectionSetupSession(
    val token: String = newSetupSecret(), val source: String? = null,
) {
    @Volatile private var open = true
    private val operations = kotlinx.coroutines.sync.Mutex()
    fun revoke() { open = false }
    fun accepts(candidate: String?): Boolean = open && candidate != null &&
        java.security.MessageDigest.isEqual(token.toByteArray(), candidate.toByteArray())
    suspend fun <T> authorized(candidate: String?, action: suspend () -> T): T = operations.withLock {
        if (!accepts(candidate)) throw SetupExpiredException()
        action()
    }
}
private fun newSetupSecret(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
    ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
class SetupExpiredException : IllegalStateException()

data class ConnectionSetupUiState(val session: ConnectionSetupSession? = null, val error: String? = null)

fun Route.connectionSetupRoutes(
    session: () -> ConnectionSetupSession?,
    read: suspend () -> ConnectionSettingsSnapshot,
    save: suspend (ConnectionSettingsSnapshot) -> ConnectionSettingsSnapshot,
    readAsset: (String) -> ByteArray,
) {
    val gson = GsonBuilder().serializeNulls().create()
    get("/connections") {
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.respondBytes(readAsset("connection-setup/index.html"), ContentType.Text.Html)
    }
    get("/connections/") {
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("Referrer-Policy", "no-referrer")
        call.respondBytes(readAsset("connection-setup/index.html"), ContentType.Text.Html)
    }
    get("/connections/assets/{file}") {
        val file = call.parameters["file"].orEmpty()
        if (!file.matches(Regex("[A-Za-z0-9_.-]+\\.(js|css|png|svg|webp)"))) {
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val type = when (file.substringAfterLast('.')) {
            "js" -> ContentType.Application.JavaScript
            "css" -> ContentType.Text.CSS
            "png" -> ContentType.Image.PNG
            "svg" -> ContentType.Image.SVG
            else -> ContentType("image", "webp")
        }
        try { call.respondBytes(readAsset("connection-setup/assets/$file"), type) }
        catch (_: java.io.FileNotFoundException) { call.respondText("Not found", status = HttpStatusCode.NotFound) }
    }
    get("/api/connections") {
        val current = session()
        call.response.headers.append("Cache-Control", "no-store")
        if (current == null) { call.setupExpired(); return@get }
        try {
            val snapshot = current.authorized(call.request.headers["X-Setup-Token"]) { read() }
            call.respondText(gson.toJson(snapshot), ContentType.Application.Json)
        } catch (error: Exception) { call.setupFailure(error) }
    }
    post("/api/connections") {
        val current = session()
        call.response.headers.append("Cache-Control", "no-store")
        if (current == null) { call.setupExpired(); return@post }
        try {
            val saved = current.authorized(call.request.headers["X-Setup-Token"]) {
                val body = call.receiveText()
                require(body.length <= 65536) { "入力内容が長すぎます。" }
                val payload = requireNotNull(gson.fromJson(body, ConnectionSettingsSnapshot::class.java)) {
                    "入力内容を確認してください。"
                }
                save(payload)
            }
            call.respondText(gson.toJson(saved), ContentType.Application.Json)
        } catch (error: Exception) { call.setupFailure(error) }
    }
}
