package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.LastChannelEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.KonomiRepository
import com.beeregg2001.komorebi.data.repository.EpgRepository
import com.beeregg2001.komorebi.data.repository.LauncherAppRepository
import com.beeregg2001.komorebi.data.repository.LastChannelRepository
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.util.AppUpdater
import com.beeregg2001.komorebi.util.UpdateState
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

private val PINNED_SYSTEM_APP_PACKAGES = setOf("com.mitv.livetv")
private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val liveProvider: LiveProvider,
    private val konomiRepository: KonomiRepository,
    private val epgRepository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    private val lastChannelRepository: LastChannelRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val launcherAppRepository: LauncherAppRepository,
    private val appUpdater: AppUpdater
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val backendType: StateFlow<String> = settingsRepository.backendType
        .stateIn(viewModelScope, SharingStarted.Eagerly, "KONOMITV")

    var lastClickedSection: String? = null
    var lastClickedItemId: String? = null

    private val _isFallbackTriggered = MutableStateFlow(false)
    val isFallbackTriggered: StateFlow<Boolean> = _isFallbackTriggered.asStateFlow()

    private val _rawLauncherApps = MutableStateFlow<List<LauncherApp>>(emptyList())
    val launcherApps: StateFlow<List<LauncherApp>> = combine(
        _rawLauncherApps,
        settingsRepository.launcherAppOrder,
        settingsRepository.launcherAppHidden
    ) { apps, orderJson, hiddenJson ->
        val hidden = parseStringList(hiddenJson).toSet()
        val order = parseStringList(orderJson)
        val orderIndex = order.withIndex().associate { it.value to it.index }

        apps.filterNot { it.stableId in hidden }
            .sortedWith(
                compareBy<LauncherApp> { orderIndex[it.stableId] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hiddenLauncherApps: StateFlow<List<LauncherApp>> = combine(
        _rawLauncherApps,
        settingsRepository.launcherAppOrder,
        settingsRepository.launcherAppHidden
    ) { apps, orderJson, hiddenJson ->
        val hidden = parseStringList(hiddenJson).toSet()
        val order = parseStringList(orderJson)
        val orderIndex = order.withIndex().associate { it.value to it.index }

        apps.filter { it.stableId in hidden }
            .sortedWith(
                compareBy<LauncherApp> { orderIndex[it.stableId] ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // This scan now publishes metadata/resource URIs only. Artwork itself is
        // decoded lazily and cached by Coil when a launcher card becomes visible.
        refreshLauncherApps()
    }

    fun clearFocusMemory() {
        lastClickedSection = null
        lastClickedItemId = null
    }

    fun refreshLauncherApps() {
        viewModelScope.launch {
            _rawLauncherApps.value = launcherAppRepository.loadLauncherApps()
        }
    }

    fun launchApp(app: LauncherApp): Boolean = launcherAppRepository.launch(app)

    fun launchInputSourcePicker(fallbackApp: LauncherApp?): Boolean =
        launcherAppRepository.launchInputSourcePicker(fallbackApp)

    fun launchSystemSettings(): Boolean = launcherAppRepository.launchSystemSettings()

    fun launchWifiSettings(): Boolean = launcherAppRepository.launchWifiSettings()

    fun launchAppDetails(app: LauncherApp): Boolean = launcherAppRepository.launchAppDetails(app)

    fun isPinnedSystemApp(app: LauncherApp): Boolean =
        app.packageName in PINNED_SYSTEM_APP_PACKAGES

    fun hideLauncherApp(app: LauncherApp) {
        viewModelScope.launch {
            val current = parseStringList(settingsRepository.launcherAppHidden.first())
            if (app.stableId !in current) {
                settingsRepository.saveString(
                    SettingsRepository.LAUNCHER_APP_HIDDEN,
                    Gson().toJson(current + app.stableId)
                )
            }
        }
    }

    fun restoreLauncherApp(app: LauncherApp) {
        viewModelScope.launch {
            val current = parseStringList(settingsRepository.launcherAppHidden.first())
            settingsRepository.saveString(
                SettingsRepository.LAUNCHER_APP_HIDDEN,
                Gson().toJson(current.filterNot { it == app.stableId })
            )
        }
    }

    fun moveLauncherApp(app: LauncherApp, delta: Int) {
        viewModelScope.launch {
            val visibleIds = launcherApps.value
                .filterNot { isPinnedSystemApp(it) }
                .map { it.stableId }
                .toMutableList()
            val currentIndex = visibleIds.indexOf(app.stableId)
            if (currentIndex == -1) return@launch
            val targetIndex = (currentIndex + delta).coerceIn(0, visibleIds.lastIndex)
            if (targetIndex == currentIndex) return@launch

            val item = visibleIds.removeAt(currentIndex)
            visibleIds.add(targetIndex, item)
            settingsRepository.saveString(
                SettingsRepository.LAUNCHER_APP_ORDER,
                Gson().toJson(visibleIds)
            )
        }
    }

    fun showAllLauncherApps() {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.LAUNCHER_APP_HIDDEN, "[]")
        }
    }

    private fun parseStringList(json: String): List<String> =
        runCatching {
            Gson().fromJson<List<String>>(json, STRING_LIST_TYPE) ?: emptyList()
        }.getOrDefault(emptyList())

    fun dismissFallbackWarning() {
        _isFallbackTriggered.value = false
    }

    val watchHistory: StateFlow<List<KonomiHistoryProgram>> =
        watchHistoryRepository.getLocalWatchHistory()
            .map { entities -> entities.map { KonomiDataMapper.toUiModel(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastWatchedChannelFlow: StateFlow<List<Channel>> = watchHistoryRepository.getLastChannels()
        .map { entities ->
            entities.map { entity ->
                Channel(
                    id = entity.channelId, name = entity.name, type = entity.type,
                    channelNumber = entity.channelNumber ?: "", displayChannelId = entity.channelId,
                    networkId = entity.networkId, serviceId = entity.serviceId,
                    isWatchable = true, isDisplay = true, programPresent = null,
                    programFollowing = null, remocon_Id = 0
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pickupGenreLabel = settingsRepository.homePickupGenre
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "アニメ")

    val excludePaidBroadcasts = settingsRepository.excludePaidBroadcasts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ON")

    val pickupTimeSetting = settingsRepository.homePickupTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "自動")

    private val _genrePickupPrograms = MutableStateFlow<List<Pair<EpgProgram, String>>>(emptyList())
    val genrePickupPrograms: StateFlow<List<Pair<EpgProgram, String>>> =
        _genrePickupPrograms.asStateFlow()

    private val _genrePickupTimeSlot = MutableStateFlow("夜")
    val genrePickupTimeSlot: StateFlow<String> = _genrePickupTimeSlot.asStateFlow()

    private val _sharedEpgData = MutableStateFlow<List<EpgChannelWrapper>>(emptyList())

    val updateState: StateFlow<UpdateState> = appUpdater.updateState

    @Volatile
    private var isRefreshingHomeData = false
    private var lastBackendHealthCheckMillis = 0L

    fun getHotChannels(liveRows: List<LiveRowState>): List<UiChannelState> {
        return liveRows.flatMap { it.channels }
            .filter { !it.channel.is_subchannel }
            .filter { (it.jikkyoForce ?: 0) > 0 }
            .sortedByDescending { it.jikkyoForce }
            .take(5)
    }

    fun getUpcomingReserves(reserves: List<ReserveItem>): List<ReserveItem> {
        val now = OffsetDateTime.now()
        return reserves.filter {
            val start = runCatching { OffsetDateTime.parse(it.program.startTime) }.getOrNull()
            start != null && start.isAfter(now)
        }.sortedBy { it.program.startTime }.take(5)
    }

    fun updateEpgData(data: List<EpgChannelWrapper>) {
        _sharedEpgData.value = data
    }

    private fun fetchAllTypeGenrePickup() {
        // Room/JSON flows for three broadcast types resume into this parent scope.
        // Keep flattening and list construction off the UI thread as well as the
        // filtering below; this job starts shortly after app launch.
        viewModelScope.launch(Dispatchers.Default) {
            val genre = pickupGenreLabel.value
            val timeSetting = pickupTimeSetting.value
            val isExcludePaid = excludePaidBroadcasts.value == "ON"

            val now = OffsetDateTime.now()
            val startSearch = now.minusHours(1)
            val endSearch = now.plusHours(24)

            val types = listOf("GR", "BS", "CS")

            val genreCandidates = mutableListOf<Pair<EpgProgram, String>>()

            // Process one broadcast type at a time and discard its full EPG graph
            // immediately after extracting the small Home-screen result.  Expanding
            // GR/BS/CS concurrently caused a >200 MB allocation spike on 32-bit TVs.
            for (type in types) {
                val typePrograms = epgRepository.getEpgDataStream(startSearch, endSearch, type)
                    .take(1)
                    .map { it.getOrNull() ?: emptyList() }
                    .firstOrNull() ?: emptyList()

                genreCandidates += filterGenrePickup(
                    typePrograms,
                    genre,
                    timeSetting,
                    isExcludePaid,
                )
            }

            _genrePickupPrograms.value = genreCandidates
                .sortedBy { it.first.start_time }
                .take(15)
        }
    }

    private suspend fun filterGenrePickup(
        allPrograms: List<EpgChannelWrapper>,
        genre: String,
        timeSetting: String,
        isExcludePaid: Boolean
    ): List<Pair<EpgProgram, String>> = withContext(Dispatchers.Default) {
        if (allPrograms.isEmpty()) return@withContext emptyList()

        val now = OffsetDateTime.now()
        val actualTimeSlot = if (timeSetting == "自動") {
            val h = now.hour
            if (h in 5..10) "朝" else if (h in 11..17) "昼" else "夜"
        } else {
            timeSetting
        }
        _genrePickupTimeSlot.value = actualTimeSlot

        allPrograms.flatMap { wrapper ->
            wrapper.programs.map { it to wrapper.channel.name }
        }.filter { (prog, _) ->
            val isGenre = prog.genres?.any { it.major.contains(genre) } == true
            if (!isGenre) return@filter false

            val isFreeCheckOk = if (isExcludePaid) prog.is_free else true
            if (!isFreeCheckOk) return@filter false

            val start = runCatching { OffsetDateTime.parse(prog.start_time) }.getOrNull()
                ?: return@filter false

            val t = start.toLocalTime()
            val isTimeMatch = when (actualTimeSlot) {
                "朝" -> !t.isBefore(LocalTime.of(5, 0)) && t.isBefore(LocalTime.of(11, 0))
                "昼" -> !t.isBefore(LocalTime.of(11, 0)) && t.isBefore(LocalTime.of(18, 0))
                else -> !t.isBefore(LocalTime.of(18, 0)) || t.isBefore(LocalTime.of(5, 0))
            }

            val isWithin24Hours = start.isBefore(now.plusHours(24))

            isTimeMatch && start.isAfter(now) && isWithin24Hours
        }.sortedBy { it.first.start_time }.take(15)
    }

    private suspend fun performBackendHealthCheck() {
        val currentBackend = settingsRepository.backendType.first()

        if (currentBackend == "KONOMITV") return
        val now = System.currentTimeMillis()
        if (now - lastBackendHealthCheckMillis < 60_000L) return
        lastBackendHealthCheckMillis = now

        try {
            liveProvider.getChannels()
            Log.i("Komorebi_Failsafe", "Health check passed for backend: $currentBackend")
        } catch (e: Throwable) {
            Log.e("Komorebi_Failsafe", "Health check FAILED for backend: $currentBackend", e)
            _isFallbackTriggered.value = true
            Log.w(
                "Komorebi_Failsafe",
                "Backend health check failed. Showing warning without overwriting settings."
            )
        }
    }

    init {
        viewModelScope.launch {
            combine(
                pickupGenreLabel,
                pickupTimeSetting,
                excludePaidBroadcasts
            ) { genre, time, excludePaid ->
                listOf(genre, time, excludePaid)
            }
                .distinctUntilChanged()
                .debounce(1500L)
                .collectLatest {
                    fetchAllTypeGenrePickup()
                }
        }

        // ★ 修正: アプリアップデート確認は急がないので、UI描画後（3秒後）に実行
        viewModelScope.launch {
            delay(3000)
            val receiveBeta = settingsRepository.receiveBetaUpdates.first()
            appUpdater.checkForUpdates(receiveBetaUpdates = receiveBeta)
        }

        // ★ 修正: バックエンドのヘルスチェックも、UIが立ち上がってから（1.5秒後）実行
        viewModelScope.launch {
            delay(1500)
            performBackendHealthCheck()
        }
    }

    fun startUpdateDownload(apkUrl: String) {
        viewModelScope.launch {
            appUpdater.downloadAndInstallUpdate(apkUrl)
        }
    }

    fun dismissUpdate() {
        appUpdater.resetState()
    }

    fun refreshHomeData() {
        if (isRefreshingHomeData) return
        isRefreshingHomeData = true
        viewModelScope.launch {
            _isLoading.value = true
            try {
                performBackendHealthCheck()

                try {
                    val backend = settingsRepository.backendType.first()
                    if (backend == "KONOMITV" || backend == "MIRAKURUN_ONLY") {
                        konomiRepository.getWatchHistory().onSuccess { apiHistoryList ->
                            val programIds =
                                apiHistoryList.mapNotNull { it.program.id.toIntOrNull() }
                            val existingEntitiesMap =
                                watchHistoryRepository.getHistoryEntitiesByIds(programIds)
                                    .associateBy { it.id }
                            val entitiesToSave = apiHistoryList.mapNotNull { history ->
                                val programId =
                                    history.program.id.toIntOrNull() ?: return@mapNotNull null
                                val existingEntity = existingEntitiesMap[programId]
                                var newEntity = KonomiDataMapper.toEntity(history)
                                if (existingEntity != null) {
                                    newEntity = newEntity.copy(
                                        videoId = existingEntity.videoId,
                                        tileColumns = existingEntity.tileColumns,
                                        tileRows = existingEntity.tileRows,
                                        tileInterval = existingEntity.tileInterval,
                                        tileWidth = existingEntity.tileWidth,
                                        tileHeight = existingEntity.tileHeight
                                    )
                                }
                                newEntity
                            }
                            if (entitiesToSave.isNotEmpty()) watchHistoryRepository.saveAllToLocalHistory(
                                entitiesToSave
                            )
                        }
                        konomiRepository.refreshUser()
                    }
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Failed to sync KonomiTV data. Skipping.", e)
                }

                fetchAllTypeGenrePickup()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error refreshing home data", e)
            } finally {
                isRefreshingHomeData = false
                _isLoading.value = false
            }
        }
    }

    fun saveLastChannel(channel: Channel) {
        viewModelScope.launch {
            lastChannelRepository.saveLastChannel(
                LastChannelEntity(
                    channelId = channel.id, name = channel.name, type = channel.type,
                    channelNumber = channel.channelNumber, networkId = channel.networkId,
                    serviceId = channel.serviceId, updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun clearLastChannelHistory() {
        viewModelScope.launch {
            try {
                lastChannelRepository.clearLastChannels()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to clear last channels", e)
            }
        }
    }
}
