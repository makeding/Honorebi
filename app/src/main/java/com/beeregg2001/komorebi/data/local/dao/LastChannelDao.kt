package com.beeregg2001.komorebi.data.local.dao

import androidx.room.*
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LastChannelDao {
    // ★ 修正: channelId(文字列)ではなく、全国共通の networkId と serviceId の組み合わせで重複判定を行う
    @Query("DELETE FROM last_watched_channel WHERE channelId NOT LIKE 'tif:%' AND networkId = :networkId AND serviceId = :serviceId")
    suspend fun deleteByNetworkAndServiceId(networkId: Long, serviceId: Long)

    @Query("DELETE FROM last_watched_channel WHERE channelId = :channelId")
    suspend fun deleteByChannelId(channelId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LastChannelEntity)

    // リポジトリから呼ぶためのトランザクション
    @Transaction
    suspend fun insertOrUpdate(entity: LastChannelEntity) {
        if (entity.channelId.startsWith("tif:")) deleteByChannelId(entity.channelId)
        else deleteByNetworkAndServiceId(entity.networkId, entity.serviceId)
        insert(entity)
    }

    @Query("SELECT * FROM last_watched_channel ORDER BY updatedAt DESC LIMIT 10")
    fun getLastChannels(): Flow<List<LastChannelEntity>>

    @Query("SELECT * FROM last_watched_channel WHERE (:tif AND channelId LIKE 'tif:%') OR (NOT :tif AND channelId NOT LIKE 'tif:%') ORDER BY updatedAt DESC LIMIT 10")
    fun getLastChannelsForBackend(tif: Boolean): Flow<List<LastChannelEntity>>

    @Query("DELETE FROM last_watched_channel")
    suspend fun clearAll()
}

// AppDatabase.kt に DAO を追加
@Database(
    entities = [WatchHistoryEntity::class, LastChannelEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun lastChannelDao(): LastChannelDao
}
