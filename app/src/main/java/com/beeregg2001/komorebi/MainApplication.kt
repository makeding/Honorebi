package com.beeregg2001.komorebi

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import com.beeregg2001.komorebi.data.previewchannels.PreviewChannelSyncCoordinator
import com.beeregg2001.komorebi.data.watchnext.WatchNextSyncCoordinator
import com.beeregg2001.komorebi.data.worker.RecordSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var apiHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    @Named("image")
    lateinit var imageHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var watchNextSync: dagger.Lazy<WatchNextSyncCoordinator>

    @Inject
    lateinit var previewChannelSync: dagger.Lazy<PreviewChannelSyncCoordinator>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ImageLoader.Builder(this)
            .okHttpClient {
                imageHttpClient.get()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(IMAGE_MEMORY_CACHE_SIZE_BYTES)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIRECTORY))
                    .maxSizeBytes(IMAGE_DISK_CACHE_SIZE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    val channelLogoImageLoader: ImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        appImageLoader.newBuilder()
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(CHANNEL_LOGO_MEMORY_CACHE_SIZE_BYTES)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(CHANNEL_LOGO_CACHE_DIRECTORY))
                    .maxSizeBytes(CHANNEL_LOGO_DISK_CACHE_SIZE_BYTES)
                    .build()
            }
            .crossfade(false)
            .build()
    }

    // ★ 最新の WorkManager に合わせてプロパティとしてオーバーライドします
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = appImageLoader

    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            runCatching {
                apiHttpClient.get()
                imageHttpClient.get()
            }
        }
        appScope.launch {
            delay(RECORD_SYNC_SCHEDULE_DELAY_MS)
            // バックグラウンド同期スケジュールを登録
            runCatching { RecordSyncWorker.schedule(this@MainApplication) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { watchNextSync.get().start(appScope) }
                runCatching { previewChannelSync.get().start(appScope) }
            }
        }
    }

    private companion object {
        const val RECORD_SYNC_SCHEDULE_DELAY_MS = 10_000L
        const val IMAGE_CACHE_DIRECTORY = "image_cache"
        const val IMAGE_MEMORY_CACHE_SIZE_BYTES = 12 * 1024 * 1024
        const val IMAGE_DISK_CACHE_SIZE_BYTES = 32L * 1024 * 1024
        const val CHANNEL_LOGO_CACHE_DIRECTORY = "channel_logo_cache"
        const val CHANNEL_LOGO_MEMORY_CACHE_SIZE_BYTES = 4 * 1024 * 1024
        const val CHANNEL_LOGO_DISK_CACHE_SIZE_BYTES = 24L * 1024 * 1024
    }
}
