package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelLogoCache @Inject constructor(
    private val liveProvider: LiveProvider,
    private val settingsRepository: SettingsRepository
) {
    private val logoCache = ConcurrentHashMap<String, String>()
    private val inFlightLogoIds = ConcurrentHashMap.newKeySet<String>()
    private val _channelLogoUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val channelLogoUrls: StateFlow<Map<String, String>> = _channelLogoUrls.asStateFlow()

    suspend fun prefetchChannelLogoUrls(channels: Collection<Channel>) {
        val backend = settingsRepository.backendType.first()
        val pendingChannels = channels.filter { channel ->
            val candidateIds = channelLogoCandidateIds(channel, backend)
            val preferredId = candidateIds.firstOrNull()
            if (preferredId != null && logoCache.containsKey(preferredId)) {
                return@filter false
            }

            val keys = channel.logoKeys()
            keys.map { inFlightLogoIds.add(it) }.any { it }
        }
        if (pendingChannels.isEmpty()) return

        pendingChannels.forEach { channel ->
            try {
                getChannelLogoUrl(channel)
            } finally {
                channel.logoKeys().forEach { inFlightLogoIds.remove(it) }
            }
        }
    }

    suspend fun getChannelLogoUrl(channel: Channel): String {
        val keys = channel.logoKeys()
        val candidateIds = channelLogoCandidateIds(channel, settingsRepository.backendType.first())
        for (candidateId in candidateIds) {
            val logoUrl = getChannelLogoUrl(candidateId)
            if (logoUrl.isNotBlank()) {
                rememberLogoUrl(keys + candidateIds, logoUrl)
                return logoUrl
            }
        }
        return ""
    }

    suspend fun getChannelLogoUrl(channelId: String): String {
        logoCache[channelId]?.let { return it }
        val logoUrl = liveProvider.getChannelLogoUrl(channelId)
        rememberLogoUrl(listOf(channelId), logoUrl)
        return logoUrl
    }

    private fun Channel.logoKeys(): List<String> =
        listOf(id, displayChannelId).filter { it.isNotBlank() }.distinct()

    private fun channelLogoCandidateIds(channel: Channel, backend: String): List<String> {
        val primary =
            if (backend == "MIRAKURUN_ONLY") channel.id else channel.displayChannelId
        val secondary =
            if (backend == "MIRAKURUN_ONLY") channel.displayChannelId else channel.id
        return listOf(primary, secondary).filter { it.isNotBlank() }.distinct()
    }

    private fun rememberLogoUrl(keys: Collection<String>, logoUrl: String) {
        if (logoUrl.isBlank()) return
        val cleanKeys = keys.filter { it.isNotBlank() }.distinct()
        cleanKeys.forEach { logoCache[it] = logoUrl }
        _channelLogoUrls.update { current ->
            val additions = cleanKeys
                .filter { current[it] != logoUrl }
                .associateWith { logoUrl }
            if (additions.isEmpty()) current else current + additions
        }
    }
}
