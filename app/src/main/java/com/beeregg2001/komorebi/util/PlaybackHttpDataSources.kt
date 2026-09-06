package com.beeregg2001.komorebi.util

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.beeregg2001.komorebi.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaybackHttpEntryPoint {
    @Named("access") fun accessClient(): OkHttpClient
    fun settingsRepository(): SettingsRepository
}

/** Uses the same per-origin policy for manifests, segments, encryption keys and byte ranges. */
fun playbackHttpDataSourceFactory(context: Context, readTimeoutMs: Long = 60_000): HttpDataSource.Factory {
    val client = EntryPointAccessors.fromApplication(
        context.applicationContext, PlaybackHttpEntryPoint::class.java
    ).accessClient()
    return playbackHttpDataSourceFactory(client, readTimeoutMs)
}

internal fun playbackHttpDataSourceFactory(client: OkHttpClient, readTimeoutMs: Long = 60_000): HttpDataSource.Factory =
    OkHttpDataSource.Factory(client.newBuilder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build())
        .setUserAgent("DTVClient/1.0")
