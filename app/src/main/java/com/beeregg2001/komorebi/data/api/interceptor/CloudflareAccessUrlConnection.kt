package com.beeregg2001.komorebi.data.api.interceptor

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Opens HTTP connections without inheriting Access credentials across redirects. */
object CloudflareAccessUrlConnection {
    private const val MAX_REDIRECTS = 5

    fun open(
        initialUrl: String,
        configuration: () -> CloudflareAccessConfiguration,
        requestHeaders: Map<String, String> = emptyMap(),
        configure: HttpURLConnection.() -> Unit = {},
    ): HttpURLConnection {
        var url = URL(initialUrl)
        require(url.protocol == "http" || url.protocol == "https") { "Only HTTP(S) URLs are supported" }
        fun origin(value: URL) = "${value.protocol}://${value.host.lowercase()}:${if (value.port == -1) value.defaultPort else value.port}"
        val initialOrigin = origin(url)
        repeat(MAX_REDIRECTS + 1) {
            val headers = requestHeaders.filterKeys { name ->
                !name.equals(CloudflareAccessConfiguration.CLIENT_ID_HEADER, true) &&
                    !name.equals(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER, true) &&
                    (origin(url) == initialOrigin ||
                        !name.equals("Authorization", true) && !name.equals("Cookie", true))
            }
            val connection = url.openConnection() as HttpURLConnection
            try {
                with(connection) {
                configure()
                instanceFollowRedirects = false
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                configuration().headersFor(url.toString()).forEach { (name, value) -> setRequestProperty(name, value) }
                }
                val code = connection.responseCode
                if (code !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: return connection
                url = URL(url, location)
                require(url.protocol == "http" || url.protocol == "https") { "Redirected to non-HTTP(S) URL" }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many HTTP redirects")
    }
}
