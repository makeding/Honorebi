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
import com.beeregg2001.komorebi.data.DataBroadcastingSettings
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.util.mmts.B60ApplicationResource
import com.beeregg2001.komorebi.util.mmts.B60ApplicationStatus
import com.beeregg2001.komorebi.util.mmts.B60BroadcastClock
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore
import com.beeregg2001.komorebi.util.mmts.B60EventInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

enum class B60InputSource(val wireValue: String) {
    ON_AIR("onair"),
    RECORDED("recorded")
}

private const val TAG = "DataBroadcastingWebView"
private const val WEB_ORIGIN = "https://appassets.androidplatform.net"
private const val RUNTIME_PREFIX = "/databroadcast/"
private const val SHELL_PATH = "/databroadcast/b60.html"
private const val BROADCAST_PREFIX = "/data-broadcast/"
private const val NTP_UNIX_EPOCH_OFFSET_SECONDS = 2_208_988_800L

private class B60JavascriptBridge(
    private val currentMediaTimeSeconds: () -> Double,
    private val inputSource: () -> String,
    private val allowExternalNetwork: () -> Boolean,
    private val receiverInfoJson: () -> String,
    private val onStatus: (String) -> Unit,
    private val onMediaPlane: (B60MediaPlane?) -> Unit,
    private val onCaptionSubscription: (List<Int>) -> Unit,
    private val onBlankModeChanged: (Boolean) -> Unit,
    private val onApplicationExited: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun currentMediaTimeSeconds(): Double = currentMediaTimeSeconds.invoke()

    @JavascriptInterface
    fun inputSource(): String = inputSource.invoke()

    @JavascriptInterface
    fun allowExternalNetwork(): Boolean = allowExternalNetwork.invoke()

    @JavascriptInterface
    fun receiverInfoJson(): String = receiverInfoJson.invoke()

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
            Log.d(
                TAG,
                "B60 media plane: visible=${plane.visible} ${plane.width}x${plane.height} " +
                    "@ ${plane.x},${plane.y} / ${plane.screenWidth}x${plane.screenHeight}" +
                    if (!plane.visible) " raw=$value" else ""
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
    fun onCaptionSubscription(tagsJson: String) {
        val tags = runCatching {
            val array = JSONArray(tagsJson)
            List(array.length()) { index -> array.optInt(index, -1) }.filter { it >= 0 }
        }.getOrDefault(emptyList())
        Log.d(TAG, "B60 caption subscription: $tags")
        mainHandler.post { onCaptionSubscription.invoke(tags) }
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
    inputSource: B60InputSource,
    currentMediaTimeSeconds: () -> Double,
    remoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    onStatus: (String) -> Unit,
    onMediaPlane: (B60MediaPlane?) -> Unit,
    onBlankModeChanged: (Boolean) -> Unit,
    onApplicationExited: () -> Unit,
    modifier: Modifier = Modifier,
    settings: DataBroadcastingSettings = DataBroadcastingSettings(),
    onCaptionSubscription: (List<Int>) -> Unit = {},
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var shellReady by remember { mutableStateOf(false) }
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val latestCurrentMediaTimeSeconds by rememberUpdatedState(currentMediaTimeSeconds)
    val latestChannel by rememberUpdatedState(channel)
    val cachedCurrentMediaTimeMillis = remember { AtomicLong(0L) }
    val cachedInputSource = remember { AtomicReference(inputSource.wireValue) }
    val cachedAllowExternalNetwork = remember { AtomicBoolean(settings.allowExternalNetwork) }
    val cachedReceiverInfoJson = remember { AtomicReference(buildReceiverInfoJson(settings)) }
    cachedInputSource.set(inputSource.wireValue)
    cachedAllowExternalNetwork.set(settings.allowExternalNetwork)
    cachedReceiverInfoJson.set(buildReceiverInfoJson(settings))

    LaunchedEffect(Unit) {
        while (true) {
            cachedCurrentMediaTimeMillis.set(
                (latestCurrentMediaTimeSeconds.invoke() * 1000.0).roundToLong()
            )
            delay(500)
        }
    }

    key(rendererGeneration, settings.allowExternalNetwork, settings.zipcode, settings.prefectureCode) {
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

                this.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgentString + " HonorebiB60Receiver"
                }

                addJavascriptInterface(
                    B60JavascriptBridge(
                        // Javascript interfaces run on WebView's JavaBridge thread. ExoPlayer
                        // must only be queried from the main thread, so expose the cached value.
                        currentMediaTimeSeconds = {
                            cachedCurrentMediaTimeMillis.get() / 1000.0
                        },
                        inputSource = { cachedInputSource.get() },
                        allowExternalNetwork = { cachedAllowExternalNetwork.get() },
                        receiverInfoJson = { cachedReceiverInfoJson.get() },
                        onStatus = onStatus,
                        onMediaPlane = onMediaPlane,
                        onCaptionSubscription = onCaptionSubscription,
                        onBlankModeChanged = onBlankModeChanged,
                        onApplicationExited = onApplicationExited
                    ),
                    "ARIBNative"
                )
                    webViewClient = B60WebViewClient(
                        store = store,
                        allowExternalNetwork = { cachedAllowExternalNetwork.get() },
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

    // Collect store updates inside LaunchedEffect without triggering Compose recompositions
    // on every carousel resource or broadcast clock tick.
    LaunchedEffect(shellReady, store, rendererGeneration, settings.allowExternalNetwork, settings.zipcode, settings.prefectureCode) {
        if (!shellReady) return@LaunchedEffect
        var startedKey: Triple<Long, Boolean, String?>? = null
        var lastProgramPair: Pair<B60EventInfo?, B60EventInfo?>? = null
        var lastLctKey: Pair<Long, Int?>? = null
        var lastCaptionTagsKey: Pair<Long, List<Int>>? = null
        var lastClockSentAtMs = 0L

        store.status.collect { status ->
            val webView = webViewRef.value ?: return@collect
            val startKey = Triple(status.generation, status.entryReady, status.entryPath)
            if (startKey != startedKey && status.entryReady && !status.entryPath.isNullOrBlank()) {
                startedKey = startKey
                lastProgramPair = status.presentEvent to status.followingEvent
                lastLctKey = status.generation to status.lctBackgroundColorRgb
                lastCaptionTagsKey = status.generation to status.captionComponentTags
                lastClockSentAtMs = System.currentTimeMillis()
                val payload = buildStartPayload(status, latestChannel)
                Log.d(
                    TAG,
                    "B60 start: entry=${status.entryPath} captionTags=${status.captionComponentTags}"
                )
                webView.evaluateJavascript(
                    "window.KomorebiB60?.start?.(${JSONObject.quote(payload.toString())})",
                    null
                )
                return@collect
            }

            val captionTagsKey = status.generation to status.captionComponentTags
            if (captionTagsKey != lastCaptionTagsKey) {
                lastCaptionTagsKey = captionTagsKey
                val tagsJson = JSONArray(status.captionComponentTags).toString()
                webView.evaluateJavascript(
                    "window.KomorebiB60?.setCaptionTracks?.($tagsJson)",
                    null
                )
            }

            val lctKey = status.generation to status.lctBackgroundColorRgb
            if (lctKey != lastLctKey) {
                lastLctKey = lctKey
                val value = status.lctBackgroundColorRgb?.toString() ?: "null"
                webView.evaluateJavascript(
                    "window.KomorebiB60?.updateLctBackgroundColor?.($value)",
                    null
                )
            }

            if (!status.entryReady) return@collect

            val programPair = status.presentEvent to status.followingEvent
            if (programPair != lastProgramPair) {
                lastProgramPair = programPair
                val programInfo = buildProgramInfo(status, latestChannel)
                webView.evaluateJavascript(
                    "window.KomorebiB60?.updateProgramInfo?.(" +
                        "${JSONObject.quote(programInfo.toString())})",
                    null
                )
            }

            val clock = status.broadcastClock
            val nowMs = System.currentTimeMillis()
            if (clock != null && (clock.discontinuity || nowMs - lastClockSentAtMs >= 5_000L)) {
                val clockJson = buildBroadcastClock(clock)
                if (clockJson != null) {
                    lastClockSentAtMs = nowMs
                    webView.evaluateJavascript(
                        "window.KomorebiB60?.updateBroadcastClock?.(" +
                            "${JSONObject.quote(clockJson.toString())})",
                        null
                    )
                }
            }
        }
    }

    LaunchedEffect(shellReady, store, rendererGeneration) {
        if (!shellReady) return@LaunchedEffect
        store.resourceChanges.collect { change ->
            if (!change.updated || change.generation != store.status.value.generation) return@collect
            webViewRef.value?.evaluateJavascript(
                "window.KomorebiB60?.resourceChanged?.(" +
                    "${JSONObject.quote(change.path)}, true)",
                null
            )
        }
    }

    LaunchedEffect(shellReady, store, rendererGeneration) {
        if (!shellReady) return@LaunchedEffect
        store.captionPackets.collect { packet ->
            if (packet.generation != store.status.value.generation) return@collect
            val payload = JSONObject().apply {
                put("componentTag", packet.componentTag)
                put("dataType", packet.dataType)
                put("tmd", packet.tmd)
                put("data", packet.data)
            }
            webViewRef.value?.evaluateJavascript(
                "window.KomorebiB60?.pushCaption?.(${JSONObject.quote(payload.toString())})",
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
    private val allowExternalNetwork: () -> Boolean,
    private val openAsset: (String) -> java.io.InputStream,
    private val onShellReady: () -> Unit,
    private val onRendererGone: (didCrash: Boolean) -> Unit
) : WebViewClient() {
    private data class PreparedCacheKey(val contextId: Long, val path: String, val version: Int)
    private val preparedResourceCache = ConcurrentHashMap<PreparedCacheKey, ByteArray>()
    @Volatile
    private var cachedContextId: Long? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == Uri.parse(WEB_ORIGIN).host) return false
        return !(allowExternalNetwork() && url.scheme.equals("https", ignoreCase = true))
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url
        val externalAllowed = allowExternalNetwork()
        if (url.scheme != "https" || url.host != Uri.parse(WEB_ORIGIN).host) {
            return if (externalAllowed && url.scheme.equals("https", ignoreCase = true)) {
                null
            } else {
                forbiddenResponse(externalAllowed)
            }
        }
        val path = url.path.orEmpty()
        return when {
            path.startsWith(RUNTIME_PREFIX) -> {
                val rel = path.removePrefix(RUNTIME_PREFIX)
                if (rel.contains("..")) {
                    forbiddenResponse(externalAllowed)
                } else {
                    assetResponse("databroadcast/$rel", contentTypeForPath(rel), externalAllowed)
                }
            }
            path.startsWith(BROADCAST_PREFIX) -> {
                val broadcastPath = Uri.decode(url.encodedPath.orEmpty()).removePrefix(BROADCAST_PREFIX)
                val resource = store.waitForResource(broadcastPath)
                if (resource == null) {
                    Log.w(TAG, "B60 resource missing after wait: path=$broadcastPath, url=$url")
                    notFoundResponse(externalAllowed)
                } else {
                    broadcastResourceResponse(resource, externalAllowed)
                }
            }
            else -> forbiddenResponse(externalAllowed)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (url == "$WEB_ORIGIN$SHELL_PATH") onShellReady()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(detail.didCrash())
        return true
    }

    private fun assetResponse(path: String, mimeType: String, allowExternal: Boolean): WebResourceResponse {
        val bytes = STATIC_ASSET_CACHE.getOrPut(path) {
            openAsset(path).use { it.readBytes() }
        }
        return response(200, "OK", mimeType, bytes, allowExternal, immutable = true)
    }

    private fun broadcastResourceResponse(
        resource: B60ApplicationResource,
        allowExternalNetwork: Boolean,
    ): WebResourceResponse {
        val mimeType = resource.contentType.substringBefore(';').ifBlank {
            contentTypeForPath(resource.path)
        }
        val needsRewrite = mimeType.equals("text/html", ignoreCase = true) ||
            mimeType.equals("text/css", ignoreCase = true)
        val data = if (!needsRewrite) {
            resource.data
        } else {
            if (cachedContextId != resource.contextId) {
                preparedResourceCache.clear()
                cachedContextId = resource.contextId
            }
            val cacheKey = PreparedCacheKey(resource.contextId, resource.path, resource.version)
            preparedResourceCache.getOrPut(cacheKey) {
                if (mimeType.equals("text/html", ignoreCase = true)) {
                    prepareBroadcastHtml(
                        resource.data.toString(StandardCharsets.UTF_8),
                        broadcastBasePathFor(resource.path)
                    ).toByteArray(StandardCharsets.UTF_8)
                } else {
                    prepareBroadcastStylesheet(
                        resource.data.toString(StandardCharsets.UTF_8),
                        broadcastBasePathFor(resource.path)
                    ).toByteArray(StandardCharsets.UTF_8)
                }
            }
        }
        return response(200, "OK", mimeType, data, allowExternalNetwork)
    }

    companion object {
        private val STATIC_ASSET_CACHE = ConcurrentHashMap<String, ByteArray>()
    }
}

internal fun buildReceiverInfoJson(settings: DataBroadcastingSettings): String =
    JSONObject().apply {
        put("zipcode", settings.zipcode.takeIf { it.length == 7 } ?: JSONObject.NULL)
        put("prefecture", settings.prefectureCode ?: JSONObject.NULL)
        put("regioncode", settings.regionCode ?: JSONObject.NULL)
    }.toString()

private fun broadcastBasePathFor(resourcePath: String): String {
    val mount = resourcePath.substringBefore('/', missingDelimiterValue = "")
    return if (mount.isBlank()) BROADCAST_PREFIX else "$BROADCAST_PREFIX$mount/"
}

private fun prepareBroadcastHtml(source: String, basePath: String): String {
    var prepared = ROM_SOUND_SOURCE.replace(source) { match ->
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
    val bootstrap = createRuntimeBootstrap(basePath)
    val head = HEAD_TAG.find(prepared)
    if (head == null) return bootstrap + prepared
    val offset = head.range.last + 1
    return prepared.substring(0, offset) + bootstrap + prepared.substring(offset)
}

/**
 * Mirrors libaribhtml5's arib-vfs-sw.js `createRuntimeBootstrap`:
 * observes static and dynamically inserted `<object type="video/x-arib2-broadcast">`
 * elements (replacing them with `<video data-arib-object="video/x-arib2-broadcast">` so
 * CSS sizing and caption/media-plane APIs work), installs the parent runtime with retries,
 * and provides an early `navigator.receiverDevice` fallback.
 */
private fun createRuntimeBootstrap(basePath: String): String {
    val quotedBase = JSONObject.quote(basePath)
    return """<script>
(function installAribHtml5Runtime(attempt) {
  var broadcastType = 'video/x-arib2-broadcast';
  var scheduledObjects = new WeakSet();
  function replaceBroadcastObject(object) {
    if (!object || object.nodeType !== 1 ||
        String(object.getAttribute('type') || '').trim().toLowerCase() !== broadcastType) return;
    var replacement = document.createElement('video');
    for (var index = 0; index < object.attributes.length; index += 1) {
      var attribute = object.attributes[index];
      if (attribute.name.toLowerCase() === 'type') continue;
      replacement.setAttribute(attribute.name, attribute.value);
    }
    if (replacement.style.getPropertyValue('opacity') === '0' &&
        replacement.style.getPropertyPriority('opacity') === 'important') {
      replacement.style.removeProperty('opacity');
    }
    var ancestor = object.parentElement;
    while (ancestor && ancestor !== document.body && ancestor !== document.documentElement) {
      if (ancestor.style.getPropertyValue('opacity') === '0' &&
          ancestor.style.getPropertyPriority('opacity') === 'important') {
        ancestor.style.removeProperty('opacity');
      }
      ancestor = ancestor.parentElement;
    }
    replacement.setAttribute('data-arib-object', broadcastType);
    while (object.firstChild) replacement.appendChild(object.firstChild);
    object.replaceWith(replacement);
  }
  function scanBroadcastObjects(root) {
    if (!root) return;
    if (root.nodeType === 1 && root.matches && root.matches('object[type="video/x-arib2-broadcast"]')) {
      replaceBroadcastObject(root);
    }
    if (!root.querySelectorAll) return;
    var objects = root.querySelectorAll('object[type="video/x-arib2-broadcast"]');
    for (var index = 0; index < objects.length; index += 1) {
      replaceBroadcastObject(objects[index]);
    }
  }
  function observeBroadcastObjects() {
    scanBroadcastObjects(document);
    if (!document.documentElement) {
      queueMicrotask(observeBroadcastObjects);
      return;
    }
    var observer = new MutationObserver(function (records) {
      for (var recordIndex = 0; recordIndex < records.length; recordIndex += 1) {
        var record = records[recordIndex];
        for (var nodeIndex = 0; nodeIndex < record.addedNodes.length; nodeIndex += 1) {
          scanBroadcastObjects(record.addedNodes[nodeIndex]);
        }
      }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
  }
  if (typeof document !== 'undefined' && typeof MutationObserver === 'function') {
    observeBroadcastObjects();
  }
  var install;
  try {
    install = parent && parent.__ARIB_HTML5_INSTALL__;
  } catch (error) {
    install = null;
  }
  if (typeof install === 'function') {
    try {
      install(window);
      return;
    } catch (error) {
      console.error('ARIB HTML5 runtime installation failed', error);
    }
  }
  if (!navigator.receiverDevice) {
    Object.defineProperty(navigator, 'receiverDevice', {
      configurable: true,
      enumerable: true,
      value: {
        getSystemInformation: function () {
          return {
            browsername: 'Honorebi',
            browserversion: '0.2.1',
            makerid: 'Honorebi',
            modelname: 'Honorebi Android TV',
            baseurl: new URL($quotedBase, location.origin).href
          };
        },
        getDeviceIdentifier: function (kind, callback) {
          queueMicrotask(function () { callback(''); });
        }
      }
    });
  }
  if (attempt < 100) {
    setTimeout(function () { installAribHtml5Runtime(attempt + 1); }, 50);
  }
})(0);
</script>"""
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
        put("captionComponentTags", JSONArray(status.captionComponentTags))
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
    data: ByteArray,
    allowExternalNetwork: Boolean,
    immutable: Boolean = false,
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
        "Cache-Control" to if (immutable) "max-age=86400" else "no-store",
        "Content-Security-Policy" to
            // Broadcast applications use dynamic JavaScript compilation for
            // receiver capability checks and generated application code.
            if (allowExternalNetwork) {
                "default-src 'self' https: data: blob:; script-src 'self' https: 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' https: 'unsafe-inline'; connect-src 'self' https:; object-src 'none'; frame-src 'self' https:"
            } else {
                "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                    "style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; frame-src 'self'"
            },
        "X-Content-Type-Options" to "nosniff"
    ),
    ByteArrayInputStream(data)
)

private fun forbiddenResponse(allowExternalNetwork: Boolean = false): WebResourceResponse =
    response(403, "Forbidden", "text/plain", "Forbidden".toByteArray(), allowExternalNetwork)

private fun notFoundResponse(allowExternalNetwork: Boolean = false): WebResourceResponse =
    response(404, "Not Found", "text/plain", "Broadcast resource is not available".toByteArray(), allowExternalNetwork)

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
