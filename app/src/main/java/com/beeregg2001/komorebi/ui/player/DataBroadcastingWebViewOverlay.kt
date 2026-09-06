package com.beeregg2001.komorebi.ui.player

// Shared BML runtime used by both live and recorded playback surfaces.

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.util.mmts.B60ApplicationResource
import com.beeregg2001.komorebi.util.mmts.B60ApplicationStatus
import com.beeregg2001.komorebi.util.mmts.B60BroadcastClock
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore
import com.beeregg2001.komorebi.util.mmts.B60EventInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

private const val TAG = "DataBroadcastingWebView"
private const val WEB_ORIGIN = "https://appassets.androidplatform.net"
private const val SHELL_PATH = "/libaribhtml5/index.html"
private const val SDK_PATH = "/libaribhtml5/libaribhtml5.js"
private const val BROADCAST_PREFIX = "/data-broadcast/"
private const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L

private class B60JavascriptBridge(
    private val currentMediaTimeSeconds: () -> Double,
    private val onStatus: (String) -> Unit,
    private val onMediaPlane: (B60MediaPlane?) -> Unit,
    private val onBlankModeChanged: (Boolean) -> Unit,
    private val onApplicationExited: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun currentMediaTimeSeconds(): Double = currentMediaTimeSeconds.invoke()

    @JavascriptInterface
    fun onStatus(value: String) {
        Log.d(TAG, "B60 runtime: $value")
        mainHandler.post { onStatus.invoke(value) }
    }

    @JavascriptInterface
    fun onUrlChanged(value: String) {
        Log.d(TAG, "B60 URL: $value")
        val isBlank = runCatching {
            Uri.parse(value).pathSegments.any { it.equals("startup", ignoreCase = true) }
        }.getOrDefault(false)
        mainHandler.post { onBlankModeChanged.invoke(isBlank) }
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
                    screenWidth = json.optDouble("screenWidth", 3840.0).toFloat(),
                    screenHeight = json.optDouble("screenHeight", 2160.0).toFloat()
                )
            mainHandler.post {
                onMediaPlane.invoke(plane)
            }
        }.onFailure { Log.w(TAG, "Invalid B60 media plane: $value", it) }
    }

    @JavascriptInterface
    fun onMediaPlaneUnmounted(reason: String) {
        Log.d(TAG, "B60 media plane unmounted: $reason")
        mainHandler.post { onMediaPlane(null) }
    }

    @JavascriptInterface
    fun onApplicationExited() {
        Log.d(TAG, "B60 application exited")
        mainHandler.post { onApplicationExited.invoke() }
    }

    @JavascriptInterface
    fun openProgramGuide(@Suppress("UNUSED_PARAMETER") request: String): Boolean = false
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun DataBroadcastingWebViewOverlay(
    store: B60DataBroadcastingStore,
    channel: Channel,
    currentMediaTimeSeconds: () -> Double,
    remoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    onStatus: (String) -> Unit,
    onMediaPlane: (B60MediaPlane?) -> Unit,
    onBlankModeChanged: (Boolean) -> Unit,
    onApplicationExited: () -> Unit,
    modifier: Modifier = Modifier
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val session by store.status.collectAsState()
    var shellReady by remember { mutableStateOf(false) }
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val latestCurrentMediaTimeSeconds by rememberUpdatedState(currentMediaTimeSeconds)
    val cachedCurrentMediaTimeMillis = remember { AtomicLong(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            cachedCurrentMediaTimeMillis.set(
                (latestCurrentMediaTimeSeconds.invoke() * 1000.0).roundToLong()
            )
            delay(250)
        }
    }

    key(rendererGeneration) {
        AndroidView(
            factory = { context ->
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.userAgentString = settings.userAgentString + " KomorebiB60Receiver"

                addJavascriptInterface(
                    B60JavascriptBridge(
                        // Javascript interfaces run on WebView's JavaBridge thread. ExoPlayer
                        // must only be queried from the main thread, so expose the cached value.
                        currentMediaTimeSeconds = {
                            cachedCurrentMediaTimeMillis.get() / 1000.0
                        },
                        onStatus = onStatus,
                        onMediaPlane = onMediaPlane,
                        onBlankModeChanged = onBlankModeChanged,
                        onApplicationExited = onApplicationExited
                    ),
                    "ARIBNative"
                )
                    webViewClient = B60WebViewClient(
                        store = store,
                        openAsset = { path -> context.assets.open(path) },
                        onShellReady = { shellReady = true },
                        onRendererGone = { didCrash ->
                            Log.e(TAG, "B60 renderer process gone; recreating WebView (crash=$didCrash)")
                            shellReady = false
                            webViewRef.value = null
                            onMediaPlane(null)
                            onBlankModeChanged(false)
                            rendererGeneration += 1
                        }
                    )
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            TAG,
                            "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                        return true
                    }
                }
                webViewRef.value = this
                loadUrl("$WEB_ORIGIN$SHELL_PATH")
                }
            },
            onRelease = { webView ->
                shellReady = false
                if (webViewRef.value === webView) webViewRef.value = null
                webView.stopLoading()
                webView.removeJavascriptInterface("ARIBNative")
                webView.destroy()
            },
            modifier = modifier
        )
    }

    LaunchedEffect(shellReady, session.generation, session.entryReady, session.entryPath) {
        if (!shellReady) return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val entry = session.entryPath
        if (!session.entryReady || entry.isNullOrBlank()) {
            return@LaunchedEffect
        }
        val payload = buildStartPayload(session, channel)
        webView.evaluateJavascript(
            "window.KomorebiB60?.start?.(${JSONObject.quote(payload.toString())})",
            null
        )
    }

    LaunchedEffect(shellReady, session.presentEvent, session.followingEvent) {
        if (!shellReady || !session.entryReady) return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val programInfo = buildProgramInfo(session, channel)
        webView.evaluateJavascript(
            "window.KomorebiB60?.updateProgramInfo?.(" +
                "${JSONObject.quote(programInfo.toString())})",
            null
        )
    }

    LaunchedEffect(shellReady, session.broadcastClock) {
        if (!shellReady || !session.entryReady) return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val clock = session.broadcastClock?.let(::buildBroadcastClock) ?: return@LaunchedEffect
        webView.evaluateJavascript(
            "window.KomorebiB60?.updateBroadcastClock?.(" +
                "${JSONObject.quote(clock.toString())})",
            null
        )
    }

    LaunchedEffect(shellReady, session.generation, session.lctBackgroundColorRgb) {
        if (!shellReady) return@LaunchedEffect
        val value = session.lctBackgroundColorRgb?.toString() ?: "null"
        webViewRef.value?.evaluateJavascript(
            "window.KomorebiB60?.updateLctBackgroundColor?.($value)",
            null
        )
    }

    LaunchedEffect(shellReady, session.generation) {
        if (!shellReady) return@LaunchedEffect
        store.resourceChanges.collectLatest { change ->
            if (change.generation != session.generation || !change.updated) return@collectLatest
            webViewRef.value?.evaluateJavascript(
                "window.KomorebiB60?.resourceChanged?.(" +
                    "${JSONObject.quote(change.path)}, true)",
                null
            )
        }
    }

    LaunchedEffect(remoteCommand, shellReady) {
        val command = remoteCommand ?: return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        if (!shellReady) return@LaunchedEffect
        webView.evaluateJavascript(
            "window.KomorebiDataBroadcastingRemote?.press?.(${JSONObject.quote(command.key)})"
        ) { result ->
            Log.d(TAG, "Remote key ${command.key}: $result")
            onRemoteCommandConsumed(command.id)
        }
    }
}

private class B60WebViewClient(
    private val store: B60DataBroadcastingStore,
    private val openAsset: (String) -> java.io.InputStream,
    private val onShellReady: () -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return request.url.host != Uri.parse(WEB_ORIGIN).host
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != Uri.parse(WEB_ORIGIN).host) {
            return forbiddenResponse()
        }
        return when (url.path) {
            SHELL_PATH -> assetResponse("libaribhtml5/index.html", "text/html")
            SDK_PATH -> assetResponse("libaribhtml5/libaribhtml5.js", "text/javascript")
            else -> if (url.path?.startsWith(BROADCAST_PREFIX) == true) {
                val path = Uri.decode(url.encodedPath.orEmpty()).removePrefix(BROADCAST_PREFIX)
                val resource = store.waitForResource(path)
                if (resource == null) {
                    Log.w(TAG, "B60 resource missing after wait: path=$path, url=$url")
                    notFoundResponse()
                } else {
                    broadcastResourceResponse(resource)
                }
            } else {
                forbiddenResponse()
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (url == "$WEB_ORIGIN$SHELL_PATH") onShellReady()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private fun assetResponse(path: String, mimeType: String): WebResourceResponse =
        response(200, "OK", mimeType, openAsset(path).use { it.readBytes() })
}

private fun broadcastResourceResponse(resource: B60ApplicationResource): WebResourceResponse {
    val mimeType = resource.contentType.substringBefore(';').ifBlank {
        contentTypeForPath(resource.path)
    }
    val data = when {
        mimeType.equals("text/html", ignoreCase = true) ->
            prepareBroadcastHtml(
                resource.data.toString(StandardCharsets.UTF_8),
                broadcastBasePathFor(resource.path)
            )
                .toByteArray(StandardCharsets.UTF_8)
        mimeType.equals("text/css", ignoreCase = true) ->
            prepareBroadcastStylesheet(
                resource.data.toString(StandardCharsets.UTF_8),
                broadcastBasePathFor(resource.path)
            )
                .toByteArray(StandardCharsets.UTF_8)
        else -> resource.data
    }
    return response(200, "OK", mimeType, data)
}

private fun broadcastBasePathFor(resourcePath: String): String {
    val mount = resourcePath.substringBefore('/', missingDelimiterValue = "")
    return if (mount.isBlank()) BROADCAST_PREFIX else "$BROADCAST_PREFIX$mount/"
}

private fun prepareBroadcastHtml(source: String, basePath: String): String {
    var prepared = OBJECT_TAG.replace(source) { match ->
        val withType = match.value.replace(
            OBJECT_TYPE,
            " data-arib-type=\"video/x-arib2-broadcast\""
        )
        OBJECT_DATA.replace(withType) { dataMatch ->
            val quote = dataMatch.groups[1]?.value.orEmpty()
            " data-arib-data=$quote${dataMatch.groups[2]?.value.orEmpty()}$quote"
        }
    }
    prepared = ROM_SOUND_SOURCE.replace(prepared) { match ->
        " data-arib-romsound=\"${match.groups[2]?.value ?: match.groups[3]?.value.orEmpty()}\""
    }
    prepared = ROOT_ATTRIBUTE.replace(prepared) { match ->
        val quote = match.groups[2]?.value
        val path = match.groups[3]?.value ?: match.groups[4]?.value.orEmpty()
        if (path.startsWith(basePath)) {
            match.value
        } else {
            val value = receiverPath(path, basePath)
            if (quote != null) {
                "${match.groups[1]?.value}$quote$value$quote"
            } else {
                "${match.groups[1]?.value}$value"
            }
        }
    }
    val bootstrap = "<script>parent.__ARIB_HTML5_INSTALL__?.(window)</script>"
    val head = HEAD_TAG.find(prepared)
    if (head == null) return bootstrap + prepared
    val offset = head.range.last + 1
    return prepared.substring(0, offset) + bootstrap + prepared.substring(offset)
}

private fun prepareBroadcastStylesheet(source: String, basePath: String): String =
    ROOT_CSS_IMPORT.replace(ROOT_CSS_URL.replace(source) { match ->
        val path = match.groups[2]?.value.orEmpty()
        if (path.startsWith(basePath)) {
            match.value
        } else {
            val quote = match.groups[1]?.value.orEmpty()
            "url($quote${receiverPath(path, basePath)}$quote)"
        }
    }) { match ->
        val path = match.groups[3]?.value.orEmpty()
        if (path.startsWith(basePath)) {
            match.value
        } else {
            "${match.groups[1]?.value}${match.groups[2]?.value}" +
                receiverPath(path, basePath) + match.groups[2]?.value
        }
    }

private fun receiverPath(path: String, basePath: String): String {
    if (path.startsWith(BROADCAST_PREFIX)) return path
    val mount = basePath.removePrefix(BROADCAST_PREFIX).trim('/')
    val requested = path.trimStart('/')
    return if (mount.isNotBlank() && (requested == mount || requested.startsWith("$mount/"))) {
        "$BROADCAST_PREFIX$requested"
    } else {
        "$basePath$requested"
    }
}

private fun buildStartPayload(status: B60ApplicationStatus, channel: Channel): JSONObject =
    JSONObject().apply {
        put("entryPath", status.entryPath.orEmpty())
        put(
            "autoEnterData",
            status.entryPath.orEmpty().split('/').any { it.equals("startup", ignoreCase = true) }
        )
        put("programInfo", buildProgramInfo(status, channel))
        put("lctBackgroundColorRgb", status.lctBackgroundColorRgb ?: JSONObject.NULL)
        status.broadcastClock?.let(::buildBroadcastClock)?.let { put("broadcastClock", it) }
        status.application?.let { application ->
            put(
                "application",
                JSONObject().apply {
                    put("type", "0x${application.applicationType.toString(16).padStart(4, '0')}")
                    put("organizationId", application.organizationId)
                    put("applicationId", application.applicationId)
                    put("controlCode", applicationControlCodeName(application.controlCode))
                    put("autostartPriority", application.autostartPriority)
                    put("rawControlCode", application.controlCode)
                }
            )
        }
    }

private fun applicationControlCodeName(controlCode: Int): String = when (controlCode) {
    0x01 -> "AUTOSTART"
    0x02 -> "PRESENT"
    0x04 -> "KILL"
    0x05 -> "PREFETCH"
    else -> "0x${controlCode.toString(16).padStart(2, '0')}"
}

private fun buildBroadcastClock(clock: B60BroadcastClock): JSONObject? {
    if (clock.broadcastTimeTimescale <= 0L || clock.mediaTimeTimescale <= 0L) return null
    val ntpMilliseconds = clock.broadcastTimeValue.toDouble() * 1000.0 /
        clock.broadcastTimeTimescale.toDouble()
    return JSONObject().apply {
        put(
            "epochMilliseconds",
            (ntpMilliseconds - NTP_UNIX_EPOCH_OFFSET_SECONDS * 1000.0).roundToLong()
        )
        put(
            "mediaTimeSeconds",
            clock.mediaTimeValue.toDouble() / clock.mediaTimeTimescale.toDouble()
        )
    }
}

private fun buildProgramInfo(status: B60ApplicationStatus, channel: Channel): JSONObject {
    val present = status.presentEvent
    val following = status.followingEvent
    val fallbackStart = runCatching {
        channel.programPresent?.startTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    }.getOrNull()
    val fallbackDuration = channel.programPresent?.duration?.toLong()?.times(1000L)
    return JSONObject().apply {
        put("original_network_id", present?.originalNetworkId ?: channel.networkId)
        put("transport_stream_id", present?.tlvStreamId ?: channel.transportStreamId)
        put("service_id", present?.serviceId ?: channel.serviceId)
        put("event_id", present?.eventId ?: channel.programPresent?.id?.toIntOrNull() ?: 0)
        put("name", present?.title ?: channel.programPresent?.title.orEmpty())
        put("event_name", present?.title ?: channel.programPresent?.title.orEmpty())
        put("start_time", present?.startTimeUnixMilliseconds ?: fallbackStart ?: System.currentTimeMillis())
        put("duration", present?.durationSeconds?.times(1000L) ?: fallbackDuration ?: 0L)
        put("desc", present?.description ?: channel.programPresent?.description.orEmpty())
        put("event_text", present?.description ?: channel.programPresent?.description.orEmpty())
        put("running_status", present?.runningStatus ?: 0)
        put("free_ca_mode", present?.freeCaMode ?: false)
        following?.let { putFollowingEvent(it) }
    }
}

private fun JSONObject.putFollowingEvent(event: B60EventInfo) {
    put("f_event_id", event.eventId)
    put("f_name", event.title)
    event.startTimeUnixMilliseconds?.let { put("f_start_time", it) }
    event.durationSeconds?.let { put("f_duration", it * 1000L) }
    put("f_desc", event.description)
}

private fun response(
    statusCode: Int,
    reason: String,
    mimeType: String,
    data: ByteArray
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
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to
            // Broadcast applications use dynamic JavaScript compilation for
            // receiver capability checks and generated application code.
            "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; frame-src 'self'",
        "X-Content-Type-Options" to "nosniff"
    ),
    ByteArrayInputStream(data)
)

private fun forbiddenResponse(): WebResourceResponse =
    response(403, "Forbidden", "text/plain", "Forbidden".toByteArray())

private fun notFoundResponse(): WebResourceResponse =
    response(404, "Not Found", "text/plain", "Broadcast resource is not available".toByteArray())

private fun contentTypeForPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js" -> "text/javascript"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private val OBJECT_TAG = Regex("<object\\b[^>]*>", RegexOption.IGNORE_CASE)
private val OBJECT_TYPE = Regex(
    "\\s+type\\s*=\\s*(?:[\"']video/x-arib2-broadcast[\"']|video/x-arib2-broadcast)",
    RegexOption.IGNORE_CASE
)
private val OBJECT_DATA = Regex("\\s+data\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
private val ROM_SOUND_SOURCE = Regex(
    "\\s+src\\s*=\\s*(?:([\"'])(romsound://\\d+)\\1|(romsound://\\d+)(?=[\\s/>]))",
    RegexOption.IGNORE_CASE
)
private val ROOT_ATTRIBUTE = Regex(
    "(\\b(?:href|src|action|poster)\\s*=\\s*)(?:([\"'])(/[^/][^\"']*)\\2|(/[^\\s>]*))",
    RegexOption.IGNORE_CASE
)
private val ROOT_CSS_URL = Regex(
    "url\\(\\s*([\"']?)(/[^/)][^)]*)\\1\\s*\\)",
    RegexOption.IGNORE_CASE
)
private val ROOT_CSS_IMPORT = Regex(
    "(@import\\s+)([\"'])(/[^/][^\"']*)\\2",
    RegexOption.IGNORE_CASE
)
private val HEAD_TAG = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
