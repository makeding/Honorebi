package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.AppContentStore
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val watchHistoryRepository: WatchHistoryRepository,
    private val channelLogoCache: ChannelLogoCache,
    private val appContentStore: AppContentStore
) : ViewModel() {

    val isLoading: StateFlow<Boolean> = appContentStore.isChannelsLoading

    val liveRows: StateFlow<List<LiveRowState>> = appContentStore.liveRows

    val groupedChannels: StateFlow<Map<String, List<Channel>>> = appContentStore.groupedChannels

    /**
     * Recently watched stations in history order, enriched with the current EPG data whenever
     * the station is still present in the live channel catalogue.
     */
    val lastWatchedChannels: StateFlow<List<Channel>> =
        combine(watchHistoryRepository.getLastChannels(), groupedChannels) { history, grouped ->
            val liveChannels = grouped.values.flatten()
            history.map { entity ->
                liveChannels.firstOrNull { channel ->
                    channel.networkId == entity.networkId && channel.serviceId == entity.serviceId
                } ?: Channel(
                    id = entity.channelId,
                    displayChannelId = entity.channelId,
                    name = entity.name,
                    channelNumber = entity.channelNumber.orEmpty(),
                    networkId = entity.networkId,
                    serviceId = entity.serviceId,
                    type = entity.type,
                    isWatchable = true,
                    isDisplay = true,
                    programPresent = null,
                    programFollowing = null,
                    remocon_Id = 0
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val recentRecordings: StateFlow<List<RecordedProgram>> = appContentStore.recentRecordings

    val isRecordingLoading: StateFlow<Boolean> = appContentStore.isRecordingsLoading

    val connectionError: StateFlow<Boolean> = appContentStore.connectionError

    private var isPollingPaused = false

    val channelLogoUrls: StateFlow<Map<String, String>> = channelLogoCache.channelLogoUrls

    fun setPollingPaused(paused: Boolean) {
        if (isPollingPaused != paused) {
            isPollingPaused = paused
            appContentStore.setPollingPaused(paused)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun fetchChannels() {
        appContentStore.refreshChannels()
    }

    fun fetchRecentRecordings() {
        appContentStore.refreshRecentRecordings()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPolling() {
        appContentStore.setPollingPaused(false)
    }

    fun stopPolling() {
        appContentStore.setPollingPaused(true)
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            val entity = KonomiDataMapper.toEntity(program)
            watchHistoryRepository.saveToLocalHistory(entity)
        }
    }

    fun prefetchChannelLogoUrls(channels: Collection<Channel>) {
        viewModelScope.launch(Dispatchers.IO) {
            channelLogoCache.prefetchChannelLogoUrls(channels)
        }
    }

    suspend fun getChannelLogoUrl(channel: Channel): String =
        channelLogoCache.getChannelLogoUrl(channel)

    suspend fun getChannelLogoUrl(channelId: String): String =
        channelLogoCache.getChannelLogoUrl(channelId)
}
