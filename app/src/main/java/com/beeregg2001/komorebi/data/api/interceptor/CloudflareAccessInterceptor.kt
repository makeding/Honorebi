package com.beeregg2001.komorebi.data.api.interceptor

import android.util.Log
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.data.SettingsRepository
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** Adds Access headers only after the backend URL rewrite has selected a trusted origin. */
@Singleton
class CloudflareAccessInterceptor internal constructor(
    private val configuration: () -> CloudflareAccessConfiguration,
) : Interceptor {
    @Inject constructor(settingsRepository: SettingsRepository) : this(
        { settingsRepository.cloudflareAccessConfiguration.value }
    )
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val snapshot = request.tag(CloudflareAccessConfiguration::class.java) ?: configuration()
        val headers = snapshot.headersFor(request.url.toString())
        if (BuildConfig.DEBUG) {
            // Host/port only; never the token values.
            Log.d(
                TAG,
                if (headers.isEmpty()) {
                    "no Access headers for ${request.url.scheme}://${request.url.host}:${request.url.port} " +
                        "(credentialsSet=${snapshot.clientId.isNotBlank()}, allowed=${snapshot.allowedOrigins})"
                } else {
                    "Access headers attached for ${request.url.host}:${request.url.port}"
                },
            )
        }
        val scoped = request.newBuilder().apply {
            tag(CloudflareAccessConfiguration::class.java, snapshot)
            // A redirect follow-up can carry copied request headers. Remove them before
            // deciding again for its exact destination.
            removeHeader(CloudflareAccessConfiguration.CLIENT_ID_HEADER)
            removeHeader(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER)
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return chain.proceed(scoped)
    }

    private companion object {
        const val TAG = "CFAccess"
    }
}
