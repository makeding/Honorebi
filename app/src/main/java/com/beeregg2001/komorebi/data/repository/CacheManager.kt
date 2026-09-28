package com.beeregg2001.komorebi.data.repository

import android.content.Context
import coil.imageLoader
import com.beeregg2001.komorebi.MainApplication
import com.beeregg2001.komorebi.data.local.AppDatabase
import com.beeregg2001.komorebi.data.repository.edcb.EdcbEpgCacheManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 設定画面の「キャッシュ管理」から呼ばれる、端末内キャッシュの一括/個別削除を担う。
 *
 * 対象:
 * - 局ロゴキャッシュ（ChannelLogoCache のメモリ + Coil 専用ローダー + EDCB の channel_logos ファイル）
 * - サムネイルキャッシュ（Coil 標準ローダーのメモリ/ディスク）
 * - 番組表(EPG)キャッシュ（gzip ファイル + epg_cache テーブル + EDCB メモリキャッシュ）
 *
 * 録画リストのキャッシュ削除は同期エンジン（SettingsViewModel.triggerFullSync）側で行う。
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val edcbEpgCacheManager: EdcbEpgCacheManager,
    private val channelLogoCache: ChannelLogoCache,
) {
    private val appImageLoader
        get() = context.imageLoader

    private val channelLogoImageLoader
        get() = (context.applicationContext as? MainApplication)?.channelLogoImageLoader

    suspend fun clearChannelLogoCache() = withContext(Dispatchers.IO) {
        channelLogoCache.clear()
        channelLogoImageLoader?.memoryCache?.clear()
        channelLogoImageLoader?.diskCache?.clear()
        File(context.cacheDir, CHANNEL_LOGO_DIR).deleteRecursively()
    }

    suspend fun clearThumbnailCache() = withContext(Dispatchers.IO) {
        appImageLoader.memoryCache?.clear()
        appImageLoader.diskCache?.clear()
    }

    suspend fun clearEpgCache() = withContext(Dispatchers.IO) {
        edcbEpgCacheManager.clearCache()
        context.cacheDir.listFiles { file ->
            file.name.startsWith(EPG_CACHE_PREFIX) && file.name.endsWith(EPG_CACHE_SUFFIX)
        }?.forEach { it.delete() }
        db.epgCacheDao().clearAll()
    }

    suspend fun clearAll() {
        clearChannelLogoCache()
        clearThumbnailCache()
        clearEpgCache()
    }

    private companion object {
        const val CHANNEL_LOGO_DIR = "channel_logos"
        const val EPG_CACHE_PREFIX = "epg_cache_"
        const val EPG_CACHE_SUFFIX = ".json.gz"
    }
}
