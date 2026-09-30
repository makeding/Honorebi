package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.NHKChannelClassifier
import com.beeregg2001.komorebi.data.model.NHKExclusionMode
import com.beeregg2001.komorebi.data.model.NHKExclusionState
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class NHKExclusionRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeMutex = Mutex()
    private val _state = MutableStateFlow(NHKExclusionState())
    val state: StateFlow<NHKExclusionState> = _state.asStateFlow()
    @Volatile private var excludedChannelIds: Set<String> = emptySet()
    @Volatile private var excludedServices: Set<Pair<Long, Long>> = emptySet()

    init {
        scope.launch {
            settingsRepository.nhkExclusionPreferences.collectLatest { preferences ->
                writeMutex.withLock {
                    val current = settingsRepository.nhkExclusionPreferences.first()
                    if (current != preferences) return@collectLatest
                    val loaded = preferences.copy(isLoaded = true).atTime(System.currentTimeMillis())
                    _state.update { loaded.copy(channelRevision = it.channelRevision) }
                }
                if (preferences.mode == NHKExclusionMode.TEMPORARY) {
                    val deadline = preferences.expiresAtMillis ?: 0L
                    delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
                    writeMutex.withLock {
                        val current = settingsRepository.nhkExclusionPreferences.first()
                        if (current.mode == NHKExclusionMode.TEMPORARY && current.expiresAtMillis == preferences.expiresAtMillis) {
                            settingsRepository.saveNHKExclusion(NHKExclusionState(isLoaded = true))
                            _state.update { NHKExclusionState(isLoaded = true, channelRevision = it.channelRevision) }
                        }
                    }
                }
            }
        }
    }

    suspend fun setMode(mode: NHKExclusionMode): NHKExclusionState = writeMutex.withLock {
        val next = if (mode == NHKExclusionMode.TEMPORARY) {
            NHKExclusionState().temporarilyEnabled(System.currentTimeMillis())
        } else NHKExclusionState(mode = mode, isLoaded = true)
        save(next)
    }

    suspend fun enableTemporaryNHKHide(): NHKExclusionState = writeMutex.withLock {
        val current = settingsRepository.nhkExclusionPreferences.first().copy(isLoaded = true)
            .atTime(System.currentTimeMillis())
        save(current.temporarilyEnabled(System.currentTimeMillis()))
    }

    suspend fun refreshExpiry() = writeMutex.withLock {
        val current = settingsRepository.nhkExclusionPreferences.first().copy(isLoaded = true)
        val effective = current.atTime(System.currentTimeMillis())
        if (effective != current) save(effective)
        else _state.update { effective.copy(channelRevision = it.channelRevision) }
    }

    private suspend fun save(next: NHKExclusionState): NHKExclusionState {
        settingsRepository.saveNHKExclusion(next)
        _state.update { next.copy(channelRevision = it.channelRevision) }
        return _state.value
    }

    fun updateChannels(channels: Collection<Channel>) {
        val excluded = channels.filter { NHKChannelClassifier.isNHKChannel(it.name) }
        val identities = excluded.flatMap { listOf(it.id, it.displayChannelId) }.toSet()
        val services = excluded.mapNotNull { channel ->
            channel.networkId?.let { network -> channel.serviceId?.let { service -> network to service } }
        }.toSet()
        if (identities != excludedChannelIds || services != excludedServices) {
            excludedChannelIds = identities
            excludedServices = services
            _state.update { it.copy(channelRevision = it.channelRevision + 1) }
        }
    }

    private fun matches(name: String?, id: String?, displayId: String?, networkId: Long?, serviceId: Long?): Boolean =
        state.value.isActive && (
            NHKChannelClassifier.isNHKChannel(name) || id in excludedChannelIds || displayId in excludedChannelIds ||
                (networkId != null && serviceId != null && networkId to serviceId in excludedServices)
            )

    fun isExcludedChannelId(id: String): Boolean = state.value.isActive && id in excludedChannelIds

    fun isExcluded(channel: Channel): Boolean = matches(
        channel.name, channel.id, channel.displayChannelId, channel.networkId, channel.serviceId,
    )

    fun isExcluded(channel: EpgChannel): Boolean = matches(
        channel.name, channel.id, channel.display_channel_id, channel.network_id?.toLong(), channel.service_id?.toLong(),
    )

    fun isExcluded(channel: RecordedChannel?): Boolean = channel != null && matches(
        channel.name, channel.id, channel.displayChannelId, channel.networkId?.toLong(), channel.serviceId?.toLong(),
    )

    fun isExcluded(program: RecordedProgram): Boolean = isExcluded(program.channel)

    fun isExcluded(reservation: ReserveItem): Boolean = reservation.channel.let {
        matches(it.name, it.id, it.displayChannelId, it.network_Id, it.service_Id)
    }
}
