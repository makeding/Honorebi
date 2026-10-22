package com.beeregg2001.komorebi.data.jikkyo

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object JikkyoNetworkResources {
    /** Shared only by Jikkyo sessions so permanent sockets cannot consume API/image dispatch slots. */
    val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/**
 * NX-Jikkyo / ニコニコ実況 とのWebSocket通信を管理するクライアントクラス。
 * ViewModelから渡された watchSessionUrl に接続し、コメントセッションへの接続とPing/Pongを一元管理します。
 */
class JikkyoClient(
    private val watchSessionUrl: String,
    private val webSocketFactory: WebSocket.Factory = JikkyoNetworkResources.client,
    private val reconnectDelayMillis: Long = 1_000L,
    private val delayForRetry: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var watchSocket: WebSocket? = null
    private var commentSocket: WebSocket? = null
    private var job: Job? = null
    private var reconnectJob: Job? = null
    private var historySocket: WebSocket? = null
    private var generation = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var stopped = false

    // コメント受信時のコールバック
    @Volatile
    private var onCommentReceived: ((String) -> Unit)? = null

    fun start(onComment: (String) -> Unit) {
        synchronized(lock) {
            if (stopped) return
            this.onCommentReceived = onComment
        }
        connect()
    }

    /** Starts a recorded-chase session with socket-level backpressure. */
    fun startChase(recordingStartUnixSeconds: Double, onComment: suspend (String) -> Unit) {
        synchronized(lock) {
            if (stopped) return
            // This is deliberately invoked outside [lock]. The consumer may suspend while its
            // bounded UI channel catches up, which makes the WebSocket callback apply backpressure.
            onCommentReceived = { raw -> runBlocking { onComment(raw) } }
        }
        connect(chase = ChaseConfig(recordingStartUnixSeconds))
    }

    fun stop() {
        job?.cancel()
        reconnectJob?.cancel()
        scope.cancel()

        val sockets = synchronized(lock) {
            if (stopped) return
            stopped = true

            // ゾンビメッセージがUIに送られるのを防ぐため即座にnull化
            onCommentReceived = null
            ++generation
            val ownedSockets = listOfNotNull(watchSocket, commentSocket, historySocket)
            watchSocket = null
            commentSocket = null
            historySocket = null
            ownedSockets
        }

        // close (1000) ではなく cancel() で即座に強制切断（終了時のモタつき防止）
        // ※これによって内部的に SocketException が発生しますが、onFailure で握りつぶします
        sockets.forEach(WebSocket::cancel)
    }

    private fun connect(chase: ChaseConfig? = null) {
        val connectionGeneration = synchronized(lock) { ++generation }
        job = scope.launch {
            try {
                Log.i("JikkyoClient", "Start Watch Session: $watchSessionUrl")
                val request = Request.Builder().url(watchSessionUrl).build()
                synchronized(lock) {
                    if (stopped || generation != connectionGeneration) return@launch
                    watchSocket = webSocketFactory.newWebSocket(request, createWatchListener(connectionGeneration, chase))
                }
            } catch (e: Exception) {
                if (!stopped && chase != null) scheduleReconnect(chase)
                else if (!stopped) Log.e("JikkyoClient", "Connection failed", e)
            }
        }
    }

    private fun createWatchListener(connectionGeneration: Long, chase: ChaseConfig?) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val accepted = synchronized(lock) {
                if (stopped || generation != connectionGeneration) false else {
                    Log.i("JikkyoClient", "Watch Socket Opened")
                    // 接続直後に startWatching を送信
                    webSocket.send("""{"type":"startWatching","data":{"reconnect":false}}""")
                    true
                }
            }
            if (!accepted) webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (stopped || generation != connectionGeneration) return
            try {
                val json = JSONObject(text)
                val type = json.optString("type")

                // Ping/Pong (接続維持)
                if (type == "ping") {
                    webSocket.send("""{"type":"pong"}""")
                    webSocket.send("""{"type":"keepSeat"}""")
                    return
                }

                // "room" イベントから threadId と yourPostKey を取得
                if (type == "room") {
                    val data = json.getJSONObject("data")
                    val threadId = data.getString("threadId")
                    val yourPostKey = data.getString("yourPostKey")
                    val messageServer = data.getJSONObject("messageServer")
                    val commentUri = messageServer.getString("uri")

                    Log.i("JikkyoClient", "Room info received. [thread=$threadId]")

                    // コメントサーバーへ接続開始
                    connectToCommentServer(commentUri, threadId, yourPostKey, connectionGeneration, chase)
                }
            } catch (e: Exception) {
                Log.e("JikkyoClient", "Watch Message Parse Error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped || generation != connectionGeneration) return
            if (chase != null) { scheduleReconnect(chase); return }
            // ★ 修正: cancel() による意図的な強制切断（SocketException等）はエラーとして出力しない
            if (t is java.net.SocketException || t is java.io.EOFException || t.message == "Canceled") {
                Log.i("JikkyoClient", "Watch Socket closed safely.")
                return
            }
            Log.e("JikkyoClient", "Watch Socket Error", t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped && generation == connectionGeneration && chase != null) scheduleReconnect(chase)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped && generation == connectionGeneration && chase != null) scheduleReconnect(chase)
        }
    }

    private fun connectToCommentServer(uri: String, threadId: String, yourPostKey: String, connectionGeneration: Long, chase: ChaseConfig?) {
        if (stopped || generation != connectionGeneration) return
        val initArray = JSONArray()
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rs:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "ps:0")))

        val threadObj = JSONObject()
        threadObj.put("version", "20061206")
        threadObj.put("thread", threadId)
        threadObj.put("threadkey", yourPostKey)
        threadObj.put("user_id", "")
        threadObj.put("res_from", -20) // 過去ログ取得件数
        initArray.put(JSONObject().put("thread", threadObj))

        initArray.put(JSONObject().put("ping", JSONObject().put("content", "pf:0")))
        initArray.put(JSONObject().put("ping", JSONObject().put("content", "rf:0")))

        val initMessage = initArray.toString()
        Log.i("JikkyoClient", "Connecting to Comment Server...")

        val request = Request.Builder()
            .url(uri)
            .addHeader("Sec-WebSocket-Protocol", "msg.nicovideo.jp#json")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                val accepted = synchronized(lock) {
                    if (stopped || generation != connectionGeneration) false else {
                        Log.i("JikkyoClient", "Comment Socket Connected! Sending init...")
                        ws.send(initMessage)
                        true
                    }
                }
                if (!accepted) ws.cancel()
            }

            override fun onMessage(ws: WebSocket, text: String) = deliver(connectionGeneration, text)

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                if (stopped || generation != connectionGeneration) return
                if (chase != null) { scheduleReconnect(chase); return }
                // ★ 修正: cancel() による意図的な強制切断（SocketException等）はエラーとして出力しない
                if (t is java.net.SocketException || t is java.io.EOFException || t.message == "Canceled") {
                    Log.i("JikkyoClient", "Comment Socket closed safely.")
                    return
                }
                Log.e("JikkyoClient", "Comment Socket Error", t)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (!stopped && generation == connectionGeneration && chase != null) scheduleReconnect(chase)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!stopped && generation == connectionGeneration && chase != null) scheduleReconnect(chase)
            }
        }
        synchronized(lock) {
            if (stopped || generation != connectionGeneration) return
            commentSocket = webSocketFactory.newWebSocket(request, listener)
        }
        if (chase != null) connectHistoryPage(uri, threadId, yourPostKey, connectionGeneration, chase, JikkyoHistoryBackfill(chase.lowerBoundUnixSeconds, initialWhenUnixSeconds = System.currentTimeMillis() / 1000.0))
    }

    private fun connectHistoryPage(uri: String, threadId: String, threadKey: String, connectionGeneration: Long, chase: ChaseConfig, backfill: JikkyoHistoryBackfill) {
        if (stopped || generation != connectionGeneration) return
        val request = Request.Builder().url(uri).addHeader("Sec-WebSocket-Protocol", "msg.nicovideo.jp#json").build()
        var completed = false
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (stopped || generation != connectionGeneration) { ws.cancel(); return }
                ws.send(backfill.request(threadId, threadKey))
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (stopped || generation != connectionGeneration) return
                backfill.acceptChat(text) { raw ->
                    deliver(connectionGeneration, raw)
                }
                if (isHistoryBatchEnd(text)) {
                    completed = true
                    ws.cancel()
                    if (backfill.advanceAfterPage()) connectHistoryPage(uri, threadId, threadKey, connectionGeneration, chase, backfill)
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!completed && !stopped && generation == connectionGeneration) scheduleReconnect(chase)
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (!completed && !stopped && generation == connectionGeneration) scheduleReconnect(chase)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!completed && !stopped && generation == connectionGeneration) scheduleReconnect(chase)
            }
        }
        synchronized(lock) {
            if (stopped || generation != connectionGeneration) return
            historySocket = webSocketFactory.newWebSocket(request, listener)
        }
    }

    /** Never let a closed downstream queue throw out of an OkHttp callback thread. */
    private fun deliver(connectionGeneration: Long, text: String) {
        val callback = synchronized(lock) {
            if (!stopped && generation == connectionGeneration) onCommentReceived else null
        } ?: return
        try {
            callback(text)
        } catch (e: Exception) {
            if (!stopped && generation == connectionGeneration) Log.w("JikkyoClient", "Chase comment consumer rejected a message", e)
        }
    }

    private fun scheduleReconnect(chase: ChaseConfig) {
        val previousSockets = synchronized(lock) {
            if (stopped || reconnectJob?.isActive == true) return
            ++generation
            val sockets = listOfNotNull(watchSocket, commentSocket, historySocket)
            watchSocket = null; commentSocket = null; historySocket = null
            sockets
        }
        previousSockets.forEach(WebSocket::cancel)
        reconnectJob = scope.launch {
            delayForRetry(reconnectDelayMillis)
            if (!stopped) connect(chase)
        }
    }

    private fun isHistoryBatchEnd(text: String): Boolean = try {
        JSONObject(text).optJSONObject("ping")?.optString("content") == "rf:0"
    } catch (_: Exception) { false }

    private data class ChaseConfig(val lowerBoundUnixSeconds: Double)

}
