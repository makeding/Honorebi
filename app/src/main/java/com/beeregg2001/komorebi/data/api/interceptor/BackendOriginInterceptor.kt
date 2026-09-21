package com.beeregg2001.komorebi.data.api.interceptor

import com.beeregg2001.komorebi.data.api.LiveSessionBackendTarget
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Ordinary API calls follow settings; a live session keeps its creation origin. */
class BackendOriginInterceptor(private val configuredBaseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = (request.tag(LiveSessionBackendTarget::class.java)?.baseUrl
            ?: configuredBaseUrl()).toHttpUrlOrNull() ?: request.url
        val url = request.url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
