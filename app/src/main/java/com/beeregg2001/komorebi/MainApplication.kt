package com.beeregg2001.komorebi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.beeregg2001.komorebi.data.worker.RecordSyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // ★ 最新の WorkManager に合わせてプロパティとしてオーバーライドします
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
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

    override fun onCreate() {
        super.onCreate()

        // バックグラウンド同期スケジュールを登録
        RecordSyncWorker.schedule(this)
    }

    private companion object {
        const val IMAGE_CACHE_DIRECTORY = "image_cache"
        const val IMAGE_MEMORY_CACHE_SIZE_BYTES = 24 * 1024 * 1024
        const val IMAGE_DISK_CACHE_SIZE_BYTES = 32L * 1024 * 1024
    }
}
