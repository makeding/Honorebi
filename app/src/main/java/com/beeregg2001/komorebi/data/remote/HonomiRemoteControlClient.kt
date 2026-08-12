package com.beeregg2001.komorebi.data.remote

import android.os.Build
import androidx.media3.common.util.Log
import com.beeregg2001.komorebi.data.auth.HonomiSessionStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

sealed interface HonomiRemoteCommand {
    data class OpenLive(val displayChannelId: String) : HonomiRemoteCommand
    data class OpenRecording(val recordedProgramId: Int, val positionSeconds: Double) : HonomiRemoteCommand
}

internal fun parseHonomiRemoteCommand(gson: Gson, text: String): HonomiRemoteCommand? = runCatching {
    val envelope = gson.fromJson(text, JsonObject::class.java)
    if (envelope.get("type")?.asString != "Command") return@runCatching null
    val command = envelope.getAsJsonObject("command") ?: return@runCatching null
    when (command.get("type")?.asString) {
        "OpenLive" -> HonomiRemoteCommand.OpenLive(command.get("display_channel_id").asString)
        "OpenRecording" -> HonomiRemoteCommand.OpenRecording(
            recordedProgramId = command.get("recorded_program_id").asInt,
            positionSeconds = command.get("position_seconds")?.asDouble ?: 0.0,
        )
        else -> null
    }
}.getOrNull()

/** HonomiTV Server と接続し、選択されたこのテレビ宛ての操作だけを配信する。 */
@Singleton
class HonomiRemoteControlClient @Inject constructor(
    okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val sessionStore: HonomiSessionStore,
) {
    private val webSocketClient = okHttpClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val _commands = MutableSharedFlow<HonomiRemoteCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<HonomiRemoteCommand> = _commands.asSharedFlow()
    private var connectionJob: Job? = null

    /** ルート画面の寿命に合わせて接続を開始する。同じ画面からの重複開始は無視する。 */
    fun start(scope: CoroutineScope) {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                val session = sessionStore.current()
                if (session == null) {
                    delay(RECONNECT_DELAY_MILLISECONDS)
                    continue
                }

                val completed = CompletableDeferred<Unit>()
                val deviceId = sessionStore.remoteDeviceId()
                val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "Komorebi TV" }
                val url = session.origin.toHttpUrl().newBuilder()
                    .addPathSegments("api/remote/receiver")
                    .addPathSegment(deviceId)
                    .addQueryParameter("device_name", deviceName)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${session.token}")
                    .build()
                val webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("{\"type\":\"State\",\"content_type\":\"Idle\"}")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        parseHonomiRemoteCommand(gson, text)?.let(_commands::tryEmit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        completed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "Remote control connection failed; reconnecting.", t)
                        completed.complete(Unit)
                    }
                })
                try {
                    completed.await()
                } finally {
                    webSocket.cancel()
                }
                delay(RECONNECT_DELAY_MILLISECONDS)
            }
        }
    }

    private companion object {
        const val TAG = "HonomiRemoteControl"
        const val RECONNECT_DELAY_MILLISECONDS = 3_000L
    }
}
