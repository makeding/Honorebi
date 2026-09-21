package com.beeregg2001.komorebi.data.api.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Safe metadata only: never include query parameters, cookies, tokens or response bodies. */
class BackendApiException(
    val safeCode: String,
    val status: Int,
    val endpoint: String,
    val responseType: String?,
    val automaticallyRetryable: Boolean,
    reason: String,
) : IOException("$reason [$safeCode; HTTP $status] 接続設定を確認するか、再試行してください。")

/** Only installed on the JSON API client, never on media/image/SSE transports. */
class BackendApiResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val url = response.request.url
        val mediaType = response.body.contentType()
        val isJson = mediaType?.subtype?.let { it == "json" || it.endsWith("+json") } == true
        val accessLogin = generateSequence(response) { it.priorResponse }.any {
            it.request.url.encodedPath.startsWith("/cdn-cgi/access/") ||
                it.request.url.host.endsWith(".cloudflareaccess.com")
        }
        val failure = when {
            accessLogin -> "ACCESS_LOGIN_REQUIRED" to "サーバー API の代わりに Access 認証ページが返されました。"
            response.code == 401 || response.code == 403 ->
                "BACKEND_AUTH_DENIED" to "サーバーへのアクセスが拒否されました。"
            response.code == 429 || response.code >= 500 ->
                "BACKEND_HTTP_${response.code}" to "サーバーが要求を処理できませんでした。"
            response.isSuccessful && response.code !in listOf(204, 205) &&
                chain.request().method != "HEAD" && !isJson ->
                "BACKEND_NON_JSON" to "サーバー API から JSON 以外の応答が返されました。"
            else -> null
        } ?: return response
        val error = BackendApiException(
            failure.first, response.code,
            "${url.scheme}://${url.host}:${url.port}${url.encodedPath}",
            mediaType?.toString(),
            !accessLogin && (response.code == 429 || response.code >= 500),
            failure.second,
        )
        response.close()
        throw error
    }
}

fun Throwable.backendApiFailure(): BackendApiException? =
    generateSequence(this) { it.cause }.filterIsInstance<BackendApiException>().firstOrNull()
