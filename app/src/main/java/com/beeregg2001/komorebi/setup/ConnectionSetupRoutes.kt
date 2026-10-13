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

// A QR session grants access only while its TV setup surface is open.
data class ConnectionSetupSession(val token: String, val source: String? = null)
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
        if (current == null || call.request.headers["X-Setup-Token"] != current.token) {
            call.respondText(gson.toJson(mapOf("code" to "SETUP_EXPIRED", "message" to "テレビで「スマホで設定」を開き直し、表示されたQRコードからこのページを開いてください。")),
                ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@get
        }
        call.respondText(gson.toJson(read().copy(scope = current.source)), ContentType.Application.Json)
    }
    post("/api/connections") {
        val current = session()
        call.response.headers.append("Cache-Control", "no-store")
        if (current == null || call.request.headers["X-Setup-Token"] != current.token) {
            call.respondText(gson.toJson(mapOf("code" to "SETUP_EXPIRED", "message" to "テレビで「スマホで設定」を開き直し、表示されたQRコードからこのページを開いてください。")),
                ContentType.Application.Json, HttpStatusCode.Unauthorized)
            return@post
        }
        try {
            val body = call.receiveText()
            require(body.length <= 16384) { "入力内容が長すぎます。" }
            val payload = requireNotNull(gson.fromJson(body, ConnectionSettingsSnapshot::class.java)) {
                "入力内容を確認してください。"
            }
            if (current.source != null) {
                val before = read()
                require(before.sources.filter { it.id != current.source }.map { it.copy(label = "") } ==
                    payload.sources.filter { it.id != current.source }.map { it.copy(label = "") }) {
                    "この画面では選択した接続先だけを変更できます。"
                }
            }
            val saved = save(payload).copy(scope = current.source)
            call.respondText(gson.toJson(saved), ContentType.Application.Json)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val invalid = error is IllegalArgumentException || error is com.google.gson.JsonParseException || error is NullPointerException
            val code = if (invalid) "INVALID_CONNECTION" else "SAVE_FAILED"
            val message = if (error is IllegalArgumentException && error !is com.google.gson.JsonParseException)
                error.message ?: "入力内容を確認して再送信してください。"
                else if (invalid) "入力内容を確認して再送信してください。"
                else "テレビに保存できませんでした。接続を確認して再送信してください。"
            call.respondText(gson.toJson(mapOf("code" to code, "message" to message)), ContentType.Application.Json,
                if (invalid) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError)
        }
    }
}
