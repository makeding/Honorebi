package com.beeregg2001.komorebi.data.api.interceptor

import com.beeregg2001.komorebi.data.SettingsRepository
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Adds Access headers only after the backend URL rewrite has selected a trusted origin. */
@Singleton
class CloudflareAccessInterceptor @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val headers = settingsRepository.cloudflareAccessConfiguration.value.headersFor(request.url.toString())
        val scoped = request.newBuilder().apply {
            // A redirect follow-up can carry copied request headers. Remove them before
            // deciding again for its exact destination.
            removeHeader(CloudflareAccessConfiguration.CLIENT_ID_HEADER)
            removeHeader(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER)
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return chain.proceed(scoped)
    }
}
