package com.beeregg2001.komorebi.data.remote

import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.media3.common.util.Log
import com.beeregg2001.komorebi.data.auth.HonomiSessionStore
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.google.gson.Gson
import com.google.gson.JsonArray
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

/** チャプター送り・戻しの方向。HonomiTV 側の direction フィールドと 1 対 1 で対応する。 */
enum class HonomiRemoteSkipDirection { NEXT, PREVIOUS }

/**
 * CM 判定から導かれたチャプター区間を HonomiTV の進捗バーへ通知するための最小表現。
 *
 * UI レイヤーの ChapterInfo をそのまま送らないのは、data レイヤーが ui レイヤーへ依存しないようにするため。
 */
data class HonomiRemoteChapter(
    val startSeconds: Double,
    val endSeconds: Double,
    val isCm: Boolean,
    val label: String,
)

sealed interface HonomiRemoteCommand {
    data class OpenLive(val displayChannelId: String) : HonomiRemoteCommand
    data class OpenRecording(val recordedProgramId: Int, val positionSeconds: Double) : HonomiRemoteCommand
    data object Play : HonomiRemoteCommand
    data object Pause : HonomiRemoteCommand
    data object Stop : HonomiRemoteCommand
    data class SeekRelative(val deltaSeconds: Double) : HonomiRemoteCommand
    data class SeekTo(val positionSeconds: Double) : HonomiRemoteCommand
    data class SkipChapter(val direction: HonomiRemoteSkipDirection) : HonomiRemoteCommand
    data object SkipCM : HonomiRemoteCommand
    data class SetCMSkipMode(val mode: CmSkipMode) : HonomiRemoteCommand
    data object VolumeUp : HonomiRemoteCommand
    data object VolumeDown : HonomiRemoteCommand
    data object VolumeMute : HonomiRemoteCommand
}

internal sealed interface HonomiRemoteServerEvent {
    data object RequestState : HonomiRemoteServerEvent
    data class Command(val command: HonomiRemoteCommand) : HonomiRemoteServerEvent
}

/** CmSkipMode を HonomiTV の mode フィールド表記 (UpperCamelCase) へ変換する。 */
internal fun CmSkipMode.toRemoteValue(): String = when (this) {
    CmSkipMode.OFF -> "Off"
    CmSkipMode.MANUAL -> "Manual"
    CmSkipMode.AUTO -> "Auto"
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
        "Play" -> HonomiRemoteCommand.Play
        "Pause" -> HonomiRemoteCommand.Pause
        "Stop" -> HonomiRemoteCommand.Stop
        "SeekRelative" -> HonomiRemoteCommand.SeekRelative(command.get("delta_seconds").asDouble)
        "SeekTo" -> HonomiRemoteCommand.SeekTo(command.get("position_seconds").asDouble.coerceAtLeast(0.0))
        // 未知の direction / mode は黙って落とす。壊れた値で意図しないシークを起こすより無視する方が安全。
        "SkipChapter" -> when (command.get("direction")?.asString) {
            "Next" -> HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.NEXT)
            "Previous" -> HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.PREVIOUS)
            else -> null
        }
        "SkipCM" -> HonomiRemoteCommand.SkipCM
        "SetCMSkipMode" -> when (command.get("mode")?.asString) {
            "Off" -> HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.OFF)
            "Manual" -> HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.MANUAL)
            "Auto" -> HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.AUTO)
            else -> null
        }
        "VolumeUp" -> HonomiRemoteCommand.VolumeUp
        "VolumeDown" -> HonomiRemoteCommand.VolumeDown
        "VolumeMute" -> HonomiRemoteCommand.VolumeMute
        else -> null
    }
}.getOrNull()

internal fun parseHonomiRemoteServerEvent(gson: Gson, text: String): HonomiRemoteServerEvent? = runCatching {
    val envelope = gson.fromJson(text, JsonObject::class.java)
    when (envelope.get("type")?.asString) {
        "RequestState" -> HonomiRemoteServerEvent.RequestState
        "Command" -> parseHonomiRemoteCommand(gson, text)?.let(HonomiRemoteServerEvent::Command)
        else -> null
    }
}.getOrNull()

/** HonomiTV Server と接続し、選択されたこのテレビ宛ての操作だけを配信する。 */
@Singleton
class HonomiRemoteControlClient @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val sessionStore: HonomiSessionStore,
) {
    private val webSocketClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val _commands = MutableSharedFlow<HonomiRemoteCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<HonomiRemoteCommand> = _commands.asSharedFlow()
    private var connectionJob: Job? = null
    @Volatile private var activeWebSocket: WebSocket? = null
    @Volatile private var latestStateJson: String? = null
    // sendState() は常にメインスレッドから呼ばれるが、再接続時の再送と競合しないよう連番も @Volatile で保持する。
    @Volatile private var stateSequence: Long = 0L

    /** 現在の再生状態を HonomiTV の接続先選択・操作メニューへ通知する。 */
    fun sendState(
        contentType: String,
        title: String? = null,
        subtitle: String? = null,
        artworkUrl: String? = null,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        positionSeconds: Double? = null,
        durationSeconds: Double? = null,
        canSeek: Boolean = false,
        playbackRate: Double? = null,
        isChasePlayback: Boolean = false,
        chapters: List<HonomiRemoteChapter> = emptyList(),
        cmSkipMode: CmSkipMode? = null,
    ) {
        val state = JsonObject().apply {
            addProperty("type", "State")
            addProperty("content_type", contentType)
            title?.let { addProperty("title", it) }
            subtitle?.let { addProperty("subtitle", it) }
            artworkUrl?.let { addProperty("artwork_url", it) }
            addProperty("is_playing", isPlaying)
            addProperty("is_buffering", isBuffering)
            addProperty("can_seek", canSeek)
            addProperty(
                "can_adjust_volume",
                !context.getSystemService(AudioManager::class.java).isVolumeFixed,
            )
            positionSeconds?.let { addProperty("position_seconds", it) }
            durationSeconds?.let { addProperty("duration_seconds", it) }
            playbackRate?.let { addProperty("playback_rate", it) }
            addProperty("is_chase_playback", isChasePlayback)
            cmSkipMode?.let { addProperty("cm_skip_mode", it.toRemoteValue()) }
            // チャプターが空の録画 (CM 判定なし) では、進捗バー側で「チャプターあり」と誤認しないよう配列ごと省く。
            if (chapters.isNotEmpty()) {
                add("chapters", JsonArray().apply {
                    chapters.forEach { chapter ->
                        add(JsonObject().apply {
                            addProperty("start_seconds", chapter.startSeconds)
                            addProperty("end_seconds", chapter.endSeconds)
                            addProperty("is_cm", chapter.isCm)
                            addProperty("label", chapter.label)
                        })
                    }
                })
            }
            // HonomiTV 側は State を受け取った時刻を基点に再生位置を補間する。
            // サーバーはデバイス一覧の変化でも同じ state を再ブロードキャストするため、
            // 「本当に新しい state か」を連番で見分けられないと進捗バーが巻き戻ってしまう。
            addProperty("state_sequence", ++stateSequence)
        }
        val stateJson = gson.toJson(state)
        latestStateJson = stateJson
        activeWebSocket?.send(stateJson)
    }

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
                val configuredDeviceName = Settings.Global.getString(context.contentResolver, "device_name")
                    ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                val deviceName = configuredDeviceName?.takeIf { it.isNotBlank() } ?: listOf(Build.MANUFACTURER, Build.MODEL)
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
                        activeWebSocket = webSocket
                        // 再接続時は一時的な Idle で再生中の状態を上書きせず、直前の完全な状態を即座に再送する。
                        latestStateJson?.let(webSocket::send) ?: sendState(contentType = "Idle")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (val event = parseHonomiRemoteServerEvent(gson, text)) {
                            HonomiRemoteServerEvent.RequestState -> latestStateJson?.let(webSocket::send)
                            is HonomiRemoteServerEvent.Command -> _commands.tryEmit(event.command)
                            null -> Unit
                        }
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
                    if (activeWebSocket === webSocket) activeWebSocket = null
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
