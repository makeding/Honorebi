package com.beeregg2001.komorebi.ui.live

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val TAG = "DataBroadcastingWebView"

data class DataBroadcastingRemoteCommand(
    val id: Long,
    val key: String
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DataBroadcastingWebViewOverlay(
    url: String,
    remoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { context ->
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
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString = settings.userAgentString + " KomorebiAndroidTV"

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = false

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "Loaded KonomiTV page: $url")
                    }

                    override fun onReceivedSslError(
                        view: WebView,
                        handler: SslErrorHandler,
                        error: SslError
                    ) {
                        Log.w(TAG, "KonomiTV WebView SSL error: ${error.primaryError}")
                        handler.proceed()
                    }
                }
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
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
        onRelease = { webView ->
            webViewRef.value = null
            webView.stopLoading()
            webView.destroy()
        },
        modifier = modifier
    )

    LaunchedEffect(remoteCommand) {
        val command = remoteCommand ?: return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val encodedKey = JSONObject.quote(command.key)
        webView.evaluateJavascript(
            """
            (async function() {
              const bridge = window.KomorebiDataBroadcastingRemote;
              if (!bridge || typeof bridge.press !== 'function') {
                console.debug('[Komorebi] data broadcasting bridge is not ready');
                return false;
              }
              return await bridge.press($encodedKey);
            })();
            """.trimIndent()
        ) { result ->
            Log.d(TAG, "Remote key ${command.key}: $result")
            onRemoteCommandConsumed(command.id)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value = null
        }
    }
}
