package com.beeregg2001.komorebi.data.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Builds a purpose-scoped client that can reuse idle connections without sharing request
 * scheduling with unrelated traffic such as images, API calls, or long-lived streams.
 */
internal fun purposeScopedHttpClient(
    connectionPool: ConnectionPool,
    configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient = OkHttpClient.Builder()
    .connectionPool(connectionPool)
    .apply(configure)
    .build()
