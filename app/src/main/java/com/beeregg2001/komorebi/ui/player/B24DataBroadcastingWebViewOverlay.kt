package com.beeregg2001.komorebi.ui.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.data.DataBroadcastingSettings
import com.beeregg2001.komorebi.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val B24_TAG = "B24DataBroadcasting"
private const val B24_WEB_ORIGIN = "https://appassets.androidplatform.net"
private const val B24_BASE_PATH = "/databroadcast/"
private const val B24_SHELL_PATH = "/databroadcast/b24.html"
private const val B24_FONTS_PREFIX = "/databroadcast/fonts/"
private const val B24_CHUNK_FLUSH_BYTES = 16 * 1024
private const val B24_CHUNK_FLUSH_MS = 60L

internal fun buildB24StreamUrl(
    konomiBaseUrl: String,
    displayChannelId: String,
    apiQuality: String,
    isStandaloneDataChannel: Boolean = false,
): String {
    val base = konomiBaseUrl.trimEnd('/')
    val encodedChannel = Uri.encode(displayChannelId)
    return if (isStandaloneDataChannel) {
        "$base/api/streams/live/$encodedChannel/data-broadcasting"
    } else {
        val encodedQuality = Uri.encode(apiQuality.ifBlank { "1080p" })
        "$base/api/streams/live/$encodedChannel/$encodedQuality/psi-archived-data"
    }
}

private class B24JavascriptBridge(
    private val httpClient: OkHttpClient,
    private val konomiBaseUrl: () -> String,
    private val allowExternalNetwork: () -> Boolean,
    private val receiverInfoJson: () -> String,
    private val onStatus: (String) -> Unit,
    private val onMediaPlane: (B60MediaPlane?) -> Unit,
    private val onBlankModeChanged: (Boolean) -> Unit,
    private val onNumericKeyUsageChanged: (Boolean) -> Unit,
    private val onApplicationExited: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun allowExternalNetwork(): Boolean = allowExternalNetwork.invoke()

    @JavascriptInterface
    fun receiverInfoJson(): String = receiverInfoJson.invoke()

    @JavascriptInterface
    fun onStatus(value: String) {
        Log.d(B24_TAG, "B24 runtime: $value")
        mainHandler.post { onStatus.invoke(value) }
    }

    @JavascriptInterface
    fun onMediaPlane(value: String) {
        runCatching {
            val json = JSONObject(value)
            val plane = B60MediaPlane(
                visible = json.optBoolean("visible", false),
                x = json.optDouble("x", 0.0).toFloat(),
                y = json.optDouble("y", 0.0).toFloat(),
                width = json.optDouble("width", 0.0).toFloat(),
                height = json.optDouble("height", 0.0).toFloat(),
                screenWidth = json.optDouble("screenWidth", 960.0).toFloat(),
                screenHeight = json.optDouble("screenHeight", 540.0).toFloat(),
            )
            mainHandler.post { onMediaPlane.invoke(plane) }
        }.onFailure { Log.w(B24_TAG, "Invalid B24 media plane: $value", it) }
    }

    @JavascriptInterface
    fun onMediaPlaneUnmounted(reason: String) {
        Log.d(B24_TAG, "B24 media plane unmounted: $reason")
        mainHandler.post { onMediaPlane.invoke(null) }
    }

    @JavascriptInterface
    fun onBlankModeChanged(isBlank: Boolean) {
        mainHandler.post { onBlankModeChanged.invoke(isBlank) }
    }

    @JavascriptInterface
    fun onDataAvailabilityChanged(available: Boolean) {
        Log.d(B24_TAG, "B24 PMT data broadcasting available=$available")
    }

    @JavascriptInterface
    fun onNumericKeyUsageChanged(active: Boolean) {
        mainHandler.post { onNumericKeyUsageChanged.invoke(active) }
    }

    @JavascriptInterface
    fun onApplicationExited() {
        mainHandler.post { onApplicationExited.invoke() }
    }

    @JavascriptInterface
    fun internetGet(uri: String): String {
        if (!allowExternalNetwork()) return JSONObject().put("ok", false).toString()
        val base = konomiBaseUrl().trimEnd('/')
        if (base.isBlank()) return JSONObject().put("ok", false).toString()
        return runCatching {
            val request = Request.Builder()
                .url("$base/api/data-broadcasting/request/$uri")
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val headersObj = JSONObject()
                for (i in 0 until resp.headers.size) {
                    headersObj.put(resp.headers.name(i), resp.headers.value(i))
                }
                JSONObject().apply {
                    put("ok", true)
                    put("statusCode", resp.code)
                    put("headers", headersObj)
                    put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.toString()
            }
        }.getOrElse {
            Log.w(B24_TAG, "internetGet failed for $uri", it)
            JSONObject().put("ok", false).toString()
        }
    }

    @JavascriptInterface
    fun internetPost(uri: String, bodyBase64: String): String {
        if (!allowExternalNetwork()) return JSONObject().put("ok", false).toString()
        val base = konomiBaseUrl().trimEnd('/')
        if (base.isBlank()) return JSONObject().put("ok", false).toString()
        return runCatching {
            val payload = Base64.decode(bodyBase64, Base64.DEFAULT)
            val request = Request.Builder()
                .url("$base/api/data-broadcasting/request/$uri")
                .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                JSONObject().apply {
                    put("ok", true)
                    put("statusCode", resp.code.toString())
                    put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }.toString()
            }
        }.getOrElse {
            Log.w(B24_TAG, "internetPost failed for $uri", it)
            JSONObject().put("ok", false).toString()
        }
    }

    @JavascriptInterface
    fun confirmIPNetwork(destination: String, isIcmp: Boolean, timeoutMillis: Int): String {
        if (!allowExternalNetwork()) return ""
        val base = konomiBaseUrl().trimEnd('/')
        if (base.isBlank()) return ""
        return runCatching {
            val encodedDest = URLEncoder.encode(destination, "UTF-8")
            val url = "$base/api/data-broadcasting/internet-status" +
                "?destination=$encodedDest&is_icmp=$isIcmp&timeout_milliseconds=$timeoutMillis"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val raw = resp.body?.string().orEmpty()
                val json = JSONObject(raw)
                JSONObject().apply {
                    put("success", json.optBoolean("success", false))
                    put("ipAddress", json.opt("ip_address") ?: JSONObject.NULL)
                    put("responseTimeMillis", json.opt("response_time_milliseconds") ?: JSONObject.NULL)
                }.toString()
            }
        }.getOrElse {
            Log.w(B24_TAG, "confirmIPNetwork failed for $destination", it)
            ""
        }
    }
}

/**
 * ARIB STD-B24 (2K) data broadcasting overlay powered by `web-bml` and HonomiTV's
 * `/api/streams/live/{channel}/{quality}/psi-archived-data` endpoint.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun B24DataBroadcastingWebViewOverlay(
    httpClient: OkHttpClient,
    konomiBaseUrl: String,
    channel: Channel,
    apiQuality: String,
    remoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    onStatus: (String) -> Unit,
    onMediaPlane: (B60MediaPlane?) -> Unit,
    onBlankModeChanged: (Boolean) -> Unit,
    onNumericKeyUsageChanged: (Boolean) -> Unit = {},
    onApplicationExited: () -> Unit,
    modifier: Modifier = Modifier,
    settings: DataBroadcastingSettings = DataBroadcastingSettings(),
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val dataPortRef = remember { AtomicReference<WebMessagePort?>(null) }
    var shellReady by remember { mutableStateOf(false) }
    var rendererGeneration by remember { mutableIntStateOf(0) }

    val cachedBaseUrl = remember { AtomicReference(konomiBaseUrl) }
    val cachedAllowExternalNetwork = remember { AtomicBoolean(settings.allowExternalNetwork) }
    val cachedReceiverInfoJson = remember { AtomicReference(buildReceiverInfoJson(settings)) }
    cachedBaseUrl.set(konomiBaseUrl)
    cachedAllowExternalNetwork.set(settings.allowExternalNetwork)
    cachedReceiverInfoJson.set(buildReceiverInfoJson(settings))

    key(rendererGeneration, settings.allowExternalNetwork, settings.zipcode, settings.prefectureCode) {
        AndroidView(
            factory = { context ->
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false

                    this.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        userAgentString = "$userAgentString HonorebiB24Receiver"
                    }

                    addJavascriptInterface(
                        B24JavascriptBridge(
                            httpClient = httpClient,
                            konomiBaseUrl = { cachedBaseUrl.get() },
                            allowExternalNetwork = { cachedAllowExternalNetwork.get() },
                            receiverInfoJson = { cachedReceiverInfoJson.get() },
                            onStatus = onStatus,
                            onMediaPlane = onMediaPlane,
                            onBlankModeChanged = onBlankModeChanged,
                            onNumericKeyUsageChanged = onNumericKeyUsageChanged,
                            onApplicationExited = onApplicationExited,
                        ),
                        "ARIBNative",
                    )

                    webViewClient = B24WebViewClient(
                        openAsset = { path -> context.assets.open(path) },
                        onShellReady = {
                            runCatching {
                                val ports = createWebMessageChannel()
                                dataPortRef.getAndSet(ports[0])?.close()
                                postWebMessage(
                                    WebMessage("honorebi-b24-port", arrayOf(ports[1])),
                                    Uri.parse(B24_WEB_ORIGIN),
                                )
                            }.onFailure {
                                Log.w(B24_TAG, "WebMessageChannel setup failed; falling back to evaluateJavascript", it)
                            }
                            shellReady = true
                        },
                        onRendererGone = { didCrash ->
                            Log.e(B24_TAG, "B24 renderer process gone; recreating WebView (crash=$didCrash)")
                            shellReady = false
                            dataPortRef.getAndSet(null)?.close()
                            webViewRef.value = null
                            onMediaPlane(null)
                            onBlankModeChanged(false)
                            rendererGeneration += 1
                        },
                    )
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d(
                                B24_TAG,
                                "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                            )
                            return true
                        }
                    }
                    webViewRef.value = this
                    loadUrl("$B24_WEB_ORIGIN$B24_SHELL_PATH")
                }
            },
            onRelease = { webView ->
                shellReady = false
                dataPortRef.getAndSet(null)?.close()
                if (webViewRef.value === webView) webViewRef.value = null
                webView.stopLoading()
                webView.removeJavascriptInterface("ARIBNative")
                webView.destroy()
            },
            modifier = modifier,
        )
    }

    LaunchedEffect(
        shellReady,
        rendererGeneration,
        konomiBaseUrl,
        channel.displayChannelId,
        channel.serviceId,
        apiQuality,
        settings.allowExternalNetwork,
        settings.zipcode,
        settings.prefectureCode,
    ) {
        if (!shellReady) return@LaunchedEffect
        val serviceId = channel.serviceId ?: return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        if (konomiBaseUrl.isBlank()) return@LaunchedEffect

        val startPayload = JSONObject().apply {
            put("serviceId", serviceId)
            put("networkId", channel.networkId ?: JSONObject.NULL)
            put("transportStreamId", channel.transportStreamId ?: JSONObject.NULL)
            put("rawTs", false)
        }
        webView.evaluateJavascript(
            "window.HonorebiB24?.start?.(${JSONObject.quote(startPayload.toString())})",
            null,
        )

        val streamUrl = buildB24StreamUrl(
            konomiBaseUrl = konomiBaseUrl,
            displayChannelId = channel.displayChannelId,
            apiQuality = apiQuality,
        )
        val mainHandler = Handler(Looper.getMainLooper())
        val call = httpClient.newCall(Request.Builder().url(streamUrl).get().build())

        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(B24_TAG, "PSI stream returned HTTP ${response.code} for $streamUrl")
                        return@use
                    }
                    val input = response.body?.byteStream() ?: return@use
                    val readBuf = ByteArray(8 * 1024)
                    val pending = ByteArrayOutputStream(B24_CHUNK_FLUSH_BYTES)
                    var lastFlushMs = System.currentTimeMillis()

                    fun flushPending() {
                        if (pending.size() == 0) return
                        val encoded = Base64.encodeToString(pending.toByteArray(), Base64.NO_WRAP)
                        pending.reset()
                        lastFlushMs = System.currentTimeMillis()
                        val port = dataPortRef.get()
                        if (port != null) {
                            runCatching { port.postMessage(WebMessage(encoded)) }
                                .onFailure { Log.w(B24_TAG, "WebMessagePort post failed", it) }
                        } else {
                            mainHandler.post {
                                webViewRef.value?.evaluateJavascript(
                                    "window.HonorebiB24?.pushChunkBase64?.(${JSONObject.quote(encoded)})",
                                    null,
                                )
                            }
                        }
                    }

                    while (isActive) {
                        val read = input.read(readBuf)
                        if (read <= 0) break
                        pending.write(readBuf, 0, read)
                        val now = System.currentTimeMillis()
                        if (pending.size() >= B24_CHUNK_FLUSH_BYTES || now - lastFlushMs >= B24_CHUNK_FLUSH_MS) {
                            flushPending()
                        }
                    }
                    flushPending()
                }
            }
        } catch (ex: Exception) {
            if (isActive) {
                Log.w(B24_TAG, "PSI stream interrupted for ${channel.displayChannelId}: ${ex.message}")
            }
        } finally {
            call.cancel()
        }
    }

    LaunchedEffect(remoteCommand, shellReady) {
        val command = remoteCommand ?: return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        if (!shellReady) return@LaunchedEffect
        webView.evaluateJavascript(
            "window.KomorebiDataBroadcastingRemote?.press?.(${JSONObject.quote(command.key)})",
        ) { result ->
            Log.d(B24_TAG, "Remote key ${command.key}: $result")
            onRemoteCommandConsumed(command.id)
        }
    }
}

private class B24WebViewClient(
    private val openAsset: (String) -> java.io.InputStream,
    private val onShellReady: () -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.host != Uri.parse(B24_WEB_ORIGIN).host

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != Uri.parse(B24_WEB_ORIGIN).host) {
            return b24Response(403, "Forbidden", "text/plain", "Forbidden".toByteArray())
        }
        val path = url.path.orEmpty()
        val assetPath = when {
            path.startsWith(B24_FONTS_PREFIX) -> {
                val fontName = path.removePrefix(B24_FONTS_PREFIX)
                if (fontName.contains('/') || fontName.contains("..")) return b24NotFound()
                "fonts/$fontName"
            }
            path.startsWith(B24_BASE_PATH) -> {
                val rel = path.removePrefix(B24_BASE_PATH)
                if (rel.contains("..")) return b24NotFound()
                "databroadcast/$rel"
            }
            else -> return b24NotFound()
        }
        val bytes = ASSET_CACHE.getOrPut(assetPath) {
            runCatching { openAsset(assetPath).use { it.readBytes() } }.getOrNull() ?: return b24NotFound()
        }
        return b24Response(200, "OK", b24MimeType(assetPath), bytes)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (url == "$B24_WEB_ORIGIN$B24_SHELL_PATH") onShellReady()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private fun b24NotFound(): WebResourceResponse =
        b24Response(404, "Not Found", "text/plain", "Not Found".toByteArray())

    private fun b24Response(
        statusCode: Int,
        reason: String,
        mimeType: String,
        data: ByteArray,
    ): WebResourceResponse = WebResourceResponse(
        mimeType,
        if (mimeType.startsWith("text/") || mimeType.contains("javascript") || mimeType.contains("json")) {
            "UTF-8"
        } else {
            null
        },
        statusCode,
        reason,
        mapOf(
            "Cache-Control" to "max-age=86400",
            "Content-Security-Policy" to
                "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "worker-src 'self' blob:; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'",
            "X-Content-Type-Options" to "nosniff",
        ),
        ByteArrayInputStream(data),
    )

    private fun b24MimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "ttf" -> "font/ttf"
        "woff2" -> "font/woff2"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    companion object {
        private val ASSET_CACHE = ConcurrentHashMap<String, ByteArray>()
    }
}
