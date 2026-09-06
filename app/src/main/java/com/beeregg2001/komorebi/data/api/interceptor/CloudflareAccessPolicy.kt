package com.beeregg2001.komorebi.data.api.interceptor

import java.net.URI

/**
 * Keeps Cloudflare Access credentials scoped to explicitly configured HTTPS origins.
 * This policy deliberately has no wildcard, sub-domain, or redirect inheritance.
 */
data class CloudflareAccessConfiguration(
    val clientId: String = "",
    val clientSecret: String = "",
    val allowedOrigins: Set<Origin> = emptySet(),
) {
    data class Origin(val host: String, val port: Int)

    fun headersFor(url: String): Map<String, String> = headersFor(URI(url))

    fun headersFor(uri: URI): Map<String, String> {
        if (clientId.isBlank() || clientSecret.isBlank() || !uri.scheme.equals("https", ignoreCase = true)) {
            return emptyMap()
        }
        val host = uri.host?.lowercase() ?: return emptyMap()
        val port = if (uri.port == -1) 443 else uri.port
        if (Origin(host, port) !in allowedOrigins) return emptyMap()
        return mapOf(CLIENT_ID_HEADER to clientId, CLIENT_SECRET_HEADER to clientSecret)
    }

    override fun toString(): String = "CloudflareAccessConfiguration(clientIdSet=${clientId.isNotBlank()}, clientSecretSet=${clientSecret.isNotBlank()}, allowedOrigins=$allowedOrigins)"

    companion object {
        const val CLIENT_ID_HEADER = "CF-Access-Client-Id"
        const val CLIENT_SECRET_HEADER = "CF-Access-Client-Secret"

        fun origin(url: String, fallbackPort: String): Origin? = runCatching {
            val uri = URI(url)
            if (!uri.scheme.equals("https", ignoreCase = true)) return@runCatching null
            val host = uri.host?.lowercase() ?: return@runCatching null
            val port = if (uri.port == -1) 443 else uri.port
            Origin(host, port)
        }.getOrNull()
    }
}
